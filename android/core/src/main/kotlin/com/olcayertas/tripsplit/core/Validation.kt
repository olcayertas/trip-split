package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown by [checkBody] when an op body breaks SPEC §4.3. */
class Invalid : Exception()

val KNOWN_TYPES: Set<String> = setOf(
    "group.create", "group.rename", "member.add", "member.rename", "member.claim", "member.unclaim",
    "member.remove", "expense.add", "expense.edit", "expense.delete", "settlement.add", "settlement.delete",
)

private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val DATE_RE = Regex("[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])")
private val INT_RE = Regex("-?[0-9]+")
private val CATEGORIES = setOf("food", "transport", "lodging", "activities", "shopping", "other")
private const val MAX_SPLIT_MEMBERS = 100
private const val MAX_WEIGHT = 1000L

private fun need(cond: Boolean) {
    if (!cond) throw Invalid()
}

/** The Long value of a JSON integer literal, or null for anything else (string, float, bool, null). */
internal fun intOrNull(e: JsonElement?): Long? {
    if (e !is JsonPrimitive || e is JsonNull || e.isString) return null
    val raw = e.content
    if (!INT_RE.matches(raw)) return null
    return raw.toLongOrNull()
}

/** The text of a JSON string, or null for anything else. */
internal fun strOrNull(e: JsonElement?): String? =
    if (e is JsonPrimitive && e !is JsonNull && e.isString) e.content else null

internal fun opAuthor(op: JsonObject): String = strOrNull(op["author"]) ?: ""
internal fun opSeq(op: JsonObject): Long = intOrNull(op["seq"]) ?: 0L
internal fun opRef(op: JsonObject): String = "${opAuthor(op)}:${opSeq(op)}"

private fun checkUuid(v: JsonElement?) {
    val s = strOrNull(v)
    need(s != null && UUID_RE.matches(s))
}

private fun checkText(v: JsonElement?, maxLen: Int) {
    val s = strOrNull(v)
    need(s != null && s.codePointCount(0, s.length) in 1..maxLen)
}

private fun checkKeys(obj: JsonElement?, required: Set<String>) {
    need(obj is JsonObject)
    need((obj as JsonObject).keys == required)
}

private fun checkRate(v: JsonElement?) {
    val s = strOrNull(v)
    need(s != null)
    try {
        parseRate(s!!)
    } catch (_: IllegalArgumentException) {
        throw Invalid()
    }
}

private fun checkDate(v: JsonElement?) {
    val s = strOrNull(v)
    need(s != null && DATE_RE.matches(s))
}

private fun checkCurrency(v: JsonElement?) {
    val s = strOrNull(v)
    need(s != null && s in CURRENCIES)
}

private fun checkAmount(v: JsonElement?): Long {
    val n = intOrNull(v)
    need(n != null && n in 1..MAX_AMOUNT_MINOR)
    return n!!
}

private fun checkMoney(m: JsonElement?) {
    checkKeys(m, setOf("amountMinor", "currency", "rate", "split"))
    m as JsonObject
    val amount = checkAmount(m["amountMinor"])
    checkCurrency(m["currency"])
    checkRate(m["rate"])
    val split = m["split"]
    need(split is JsonObject)
    split as JsonObject
    when (strOrNull(split["mode"])) {
        "equal" -> {
            checkKeys(split, setOf("mode", "members"))
            val members = split["members"]
            need(members is JsonArray && members.size in 1..MAX_SPLIT_MEMBERS)
            members as JsonArray
            members.forEach { checkUuid(it) }
            need(members.map { (it as JsonPrimitive).content }.toSet().size == members.size)
        }
        "weights" -> {
            checkKeys(split, setOf("mode", "weights"))
            val weights = split["weights"]
            need(weights is JsonObject && weights.size in 1..MAX_SPLIT_MEMBERS)
            (weights as JsonObject).forEach { (id, w) ->
                checkUuid(JsonPrimitive(id))
                val n = intOrNull(w)
                need(n != null && n in 1..MAX_WEIGHT)
            }
        }
        "exact" -> {
            checkKeys(split, setOf("mode", "shares"))
            val shares = split["shares"]
            need(shares is JsonObject && shares.size in 1..MAX_SPLIT_MEMBERS)
            var sum = 0L
            (shares as JsonObject).forEach { (id, s) ->
                checkUuid(JsonPrimitive(id))
                val n = intOrNull(s)
                need(n != null && n in 0..MAX_AMOUNT_MINOR)
                sum += n!!
            }
            need(sum == amount)
        }
        else -> throw Invalid()
    }
}

private val EXPENSE_SCALARS: Map<String, (JsonElement?) -> Unit> = mapOf(
    "title" to { v -> checkText(v, 100) },
    "date" to { v -> checkDate(v) },
    "category" to { v -> need(strOrNull(v) in CATEGORIES) },
    "paidBy" to { v -> checkUuid(v) },
)

/** Pure body check (SPEC §4.3). Each body has exactly the listed keys. Throws [Invalid]. */
fun checkBody(type: String, body: JsonElement?) {
    need(body is JsonObject)
    body as JsonObject
    when (type) {
        "group.create" -> {
            checkKeys(body, setOf("name", "baseCurrency", "creatorMemberId", "creatorName"))
            checkText(body["name"], 60)
            checkCurrency(body["baseCurrency"])
            checkUuid(body["creatorMemberId"])
            checkText(body["creatorName"], 60)
        }
        "group.rename" -> {
            checkKeys(body, setOf("name"))
            checkText(body["name"], 60)
        }
        "member.add", "member.rename" -> {
            checkKeys(body, setOf("memberId", "name"))
            checkUuid(body["memberId"])
            checkText(body["name"], 60)
        }
        "member.claim", "member.unclaim", "member.remove" -> {
            checkKeys(body, setOf("memberId"))
            checkUuid(body["memberId"])
        }
        "expense.add" -> {
            checkKeys(body, setOf("expenseId", "title", "date", "category", "paidBy", "money"))
            checkUuid(body["expenseId"])
            for ((key, check) in EXPENSE_SCALARS) check(body[key])
            checkMoney(body["money"])
        }
        "expense.edit" -> {
            checkKeys(body, setOf("expenseId", "fields"))
            checkUuid(body["expenseId"])
            val fields = body["fields"]
            need(fields is JsonObject && fields.isNotEmpty())
            fields as JsonObject
            need(fields.keys.all { it in EXPENSE_SCALARS || it == "money" })
            for ((key, value) in fields) {
                if (key == "money") checkMoney(value) else EXPENSE_SCALARS.getValue(key)(value)
            }
        }
        "expense.delete" -> {
            checkKeys(body, setOf("expenseId"))
            checkUuid(body["expenseId"])
        }
        "settlement.add" -> {
            checkKeys(body, setOf("settlementId", "fromMember", "toMember", "amountMinor", "currency", "rate", "date"))
            checkUuid(body["settlementId"])
            checkUuid(body["fromMember"])
            checkUuid(body["toMember"])
            need(strOrNull(body["fromMember"]) != strOrNull(body["toMember"]))
            checkAmount(body["amountMinor"])
            checkCurrency(body["currency"])
            checkRate(body["rate"])
            checkDate(body["date"])
        }
        "settlement.delete" -> {
            checkKeys(body, setOf("settlementId"))
            checkUuid(body["settlementId"])
        }
        else -> throw IllegalArgumentException(type)
    }
}

/** Member ids named by a (valid) split. */
fun splitMembers(split: JsonObject): List<String> = when (strOrNull(split["mode"])) {
    "equal" -> (split.getValue("members") as JsonArray).map { (it as JsonPrimitive).content }
    "weights" -> (split.getValue("weights") as JsonObject).keys.toList()
    else -> (split.getValue("shares") as JsonObject).keys.toList()
}
