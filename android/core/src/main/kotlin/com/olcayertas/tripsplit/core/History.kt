package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One line of the change log (PLAN §11, History). The UI turns [type] and [subject] into text. */
data class HistoryEntry(
    val hlc: String,
    val millis: Long,
    val author: String,
    val type: String,
    /** The title or name that the op is about, when the op says one. */
    val subject: String?,
    /** The id of the expense, settlement or member that the op is about. */
    val targetId: String?,
)

private val TARGET_KEYS = listOf("expenseId", "settlementId", "memberId")

/**
 * Builds the history, newest first, from the raw ops. Invalid ops and ops of unknown type are left out. For
 * an edit or delete the subject is the title that the expense has in the log (the last add or edit title).
 */
fun buildHistory(ops: List<JsonObject>): List<HistoryEntry> {
    val titles = HashMap<String, String>()
    val sorted = ops.sortedWith { a, b ->
        val c = byteCompare(a.getValue("hlc").jsonPrimitive.content, b.getValue("hlc").jsonPrimitive.content)
        if (c != 0) c else byteCompare(opAuthor(a), opAuthor(b))
    }
    val out = ArrayList<HistoryEntry>()
    for (op in sorted) {
        val type = strOrNull(op["type"]) ?: continue
        if (type !in KNOWN_TYPES) continue
        val body = op["body"] as? JsonObject ?: continue
        try {
            checkBody(type, body)
        } catch (_: Invalid) {
            continue
        }
        val target = TARGET_KEYS.firstNotNullOfOrNull { strOrNull(body[it]) }
        val own = strOrNull(body["title"]) ?: strOrNull(body["name"]) ?: strOrNull(body["fields"]?.jsonObject?.get("title"))
        if (target != null && own != null) titles[target] = own
        val subject = own ?: target?.let { titles[it] }
        val hlc = op.getValue("hlc").jsonPrimitive.content
        out.add(HistoryEntry(hlc, hlcParse(hlc).millis, opAuthor(op), type, subject, target))
    }
    return out.asReversed()
}
