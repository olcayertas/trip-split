package com.olcayertas.tripsplit.core

import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanonicalJsonException(message: String) : IllegalArgumentException(message)

private val MAX_SAFE_INT: BigInteger = BigInteger.valueOf((1L shl 53) - 1)
private val INTEGER_LITERAL = Regex("-?[0-9]+")

/** Compare two strings by the bytes of their UTF-8 encoding (unsigned). */
fun byteCompare(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    val n = minOf(x.size, y.size)
    for (i in 0 until n) {
        val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return x.size - y.size
}

/** Orders strings by their UTF-8 bytes. */
val BYTE_ORDER: Comparator<String> = Comparator { a, b -> byteCompare(a, b) }

/** SPEC §2. Throws [CanonicalJsonException] for floats, exponents and out-of-range integers. */
fun canonical(value: JsonElement): String {
    val sb = StringBuilder()
    write(value, sb)
    return sb.toString()
}

fun canonicalBytes(value: JsonElement): ByteArray = canonical(value).toByteArray(Charsets.UTF_8)

private fun write(value: JsonElement, sb: StringBuilder) {
    when (value) {
        is JsonNull -> sb.append("null")
        is JsonPrimitive -> writePrimitive(value, sb)
        is JsonArray -> {
            sb.append('[')
            value.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                write(v, sb)
            }
            sb.append(']')
        }
        is JsonObject -> {
            // String.compareTo orders by UTF-16 code units, as the spec requires.
            sb.append('{')
            value.keys.sorted().forEachIndexed { i, k ->
                if (i > 0) sb.append(',')
                writeString(k, sb)
                sb.append(':')
                write(value.getValue(k), sb)
            }
            sb.append('}')
        }
    }
}

private fun writePrimitive(p: JsonPrimitive, sb: StringBuilder) {
    if (p.isString) {
        writeString(p.content, sb)
        return
    }
    val raw = p.content
    when {
        raw == "true" || raw == "false" -> sb.append(raw)
        INTEGER_LITERAL.matches(raw) -> {
            val n = BigInteger(raw)
            if (n.abs() > MAX_SAFE_INT) throw CanonicalJsonException("integer out of range")
            sb.append(n.toString())
        }
        else -> throw CanonicalJsonException("numbers must be integers: $raw")
    }
}

private fun writeString(s: String, sb: StringBuilder) {
    sb.append('"')
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000C' -> sb.append("\\f")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> sb.append(ch)
        }
    }
    sb.append('"')
}
