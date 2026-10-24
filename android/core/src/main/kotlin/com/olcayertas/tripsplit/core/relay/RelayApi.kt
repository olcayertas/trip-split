package com.olcayertas.tripsplit.core.relay

import java.io.IOException
import java.net.HttpURLConnection
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class RelayBatch(val cursor: Long, val blob: ByteArray)

/** [epoch] is null when the relay has no mailbox for this id (SPEC §10.4.3). */
class RelayPage(val batches: List<RelayBatch>, val more: Boolean, val epoch: String?)

/** The answer to a `POST`: the cursor of the stored blob and the epoch of the mailbox. */
class RelayPosted(val cursor: Long, val epoch: String)

/** What went wrong with a relay request (SPEC §10.4.3). Only [RETRY] is worth a later try with the same data. */
enum class RelayFailure { RETRY, FORBIDDEN, FULL, TOO_LARGE, BAD }

class RelayException(val failure: RelayFailure, message: String) : Exception(message)

/** The two requests of SPEC §10.4.3. Each one throws [RelayException]. */
interface RelayApi {
    /** `POST /v1/m/<mailboxId>/batches`. */
    suspend fun push(mailboxId: String, token: String, blob: ByteArray): RelayPosted

    /** `GET /v1/m/<mailboxId>/batches?after=..&limit=..`. */
    suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage
}

/** The relay over HTTPS, with the JDK client. It needs no library. */
class HttpRelayApi(baseUrl: String, private val timeoutMs: Int = 15_000) : RelayApi {
    private val base = baseUrl.trimEnd('/')

    override suspend fun push(mailboxId: String, token: String, blob: ByteArray): RelayPosted {
        val body = request("POST", "$base/v1/m/$mailboxId/batches", token, blob, 201)
        return parse {
            val o = Json.parseToJsonElement(body).jsonObject
            RelayPosted(o.getValue("cursor").jsonPrimitive.long, o.getValue("epoch").jsonPrimitive.content)
        }
    }

    override suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage {
        val body = request("GET", "$base/v1/m/$mailboxId/batches?after=$after&limit=$limit", token, null, 200)
        return parse {
            val root: JsonObject = Json.parseToJsonElement(body).jsonObject
            val batches = (root.getValue("batches") as JsonArray).map {
                val o = it.jsonObject
                RelayBatch(o.getValue("cursor").jsonPrimitive.long, Base64.getUrlDecoder().decode(o.getValue("blob").jsonPrimitive.content))
            }
            val epoch = root.getValue("epoch").let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
            RelayPage(batches, root.getValue("more").jsonPrimitive.boolean, epoch)
        }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        // A relay that answers with a body that does not match the spec is down or wrong. Try again later.
        throw RelayException(RelayFailure.RETRY, "bad answer: ${e.javaClass.simpleName}")
    }

    private suspend fun request(method: String, url: String, token: String, body: ByteArray?, ok: Int): String =
        withContext(Dispatchers.IO) {
            val conn = try {
                java.net.URI(url).toURL().openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw RelayException(RelayFailure.RETRY, "network: ${e.javaClass.simpleName}")
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code == ok) return@withContext conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                throw RelayException(failureOf(code), "status $code")
            } catch (e: IOException) {
                throw RelayException(RelayFailure.RETRY, "network: ${e.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }
}

/** SPEC §10.4.3. `429` and `5xx` are a later try, except `507`, which means that the mailbox is full. */
fun failureOf(status: Int): RelayFailure = when (status) {
    403 -> RelayFailure.FORBIDDEN
    413 -> RelayFailure.TOO_LARGE
    507 -> RelayFailure.FULL
    429 -> RelayFailure.RETRY
    in 500..599 -> RelayFailure.RETRY
    in 400..499 -> RelayFailure.BAD
    else -> RelayFailure.RETRY
}
