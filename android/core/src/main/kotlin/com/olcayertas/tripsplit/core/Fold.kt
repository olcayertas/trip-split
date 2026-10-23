package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class GroupInfo(val name: String, val baseCurrency: String)

data class MemberState(val id: String, val name: String, val removed: Boolean, val claimedBy: String?)

data class ExpenseState(
    val id: String,
    val title: String,
    val date: String,
    val category: String,
    val paidBy: String,
    val money: JsonObject,
    val deleted: Boolean,
    val createdBy: String,
    val needsAttention: Boolean,
    val baseAmountMinor: Long?,
    val baseShares: Map<String, Long>?,
)

data class SettlementState(
    val id: String,
    val fromMember: String,
    val toMember: String,
    val amountMinor: Long,
    val currency: String,
    val rate: String,
    val date: String,
    val deleted: Boolean,
    val needsAttention: Boolean,
    val baseAmountMinor: Long?,
)

data class FoldState(
    val group: GroupInfo?,
    val members: List<MemberState>,
    val expenses: List<ExpenseState>,
    val settlements: List<SettlementState>,
    val balances: Map<String, Long>,
    val suggestedPayments: List<Payment>,
    val invalidOps: List<String>,
    val unknownOps: List<String>,
) {
    /** The shape of the vectors' `expected` object (SPEC §4.4). */
    fun toJson(): JsonObject = buildJsonObject {
        put("group", group?.let { g ->
            buildJsonObject {
                put("name", g.name)
                put("baseCurrency", g.baseCurrency)
            }
        } ?: JsonNull)
        put("members", JsonArray(members.map { m ->
            buildJsonObject {
                put("id", m.id)
                put("name", m.name)
                put("removed", m.removed)
                put("claimedBy", m.claimedBy.toJson())
            }
        }))
        put("expenses", JsonArray(expenses.map { e ->
            buildJsonObject {
                put("id", e.id)
                put("title", e.title)
                put("date", e.date)
                put("category", e.category)
                put("paidBy", e.paidBy)
                put("money", e.money)
                put("deleted", e.deleted)
                put("createdBy", e.createdBy)
                put("needsAttention", e.needsAttention)
                put("baseAmountMinor", e.baseAmountMinor.toJson())
                put("baseShares", e.baseShares?.let { longMap(it) } ?: JsonNull)
            }
        }))
        put("settlements", JsonArray(settlements.map { s ->
            buildJsonObject {
                put("id", s.id)
                put("fromMember", s.fromMember)
                put("toMember", s.toMember)
                put("amountMinor", s.amountMinor)
                put("currency", s.currency)
                put("rate", s.rate)
                put("date", s.date)
                put("deleted", s.deleted)
                put("needsAttention", s.needsAttention)
                put("baseAmountMinor", s.baseAmountMinor.toJson())
            }
        }))
        put("balances", longMap(balances))
        put("suggestedPayments", JsonArray(suggestedPayments.map { p ->
            buildJsonObject {
                put("from", p.from)
                put("to", p.to)
                put("amountMinor", p.amountMinor)
            }
        }))
        put("invalidOps", JsonArray(invalidOps.map { JsonPrimitive(it) }))
        put("unknownOps", JsonArray(unknownOps.map { JsonPrimitive(it) }))
    }
}

private fun String?.toJson(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)
private fun Long?.toJson(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)
private fun longMap(m: Map<String, Long>): JsonObject = JsonObject(m.mapValues { JsonPrimitive(it.value) })

private class Member(var name: String, var removed: Boolean = false, var claimedBy: String? = null)

private class Expense(
    var title: String,
    var date: String,
    var category: String,
    var paidBy: String,
    var money: JsonObject,
    var deleted: Boolean,
    val createdBy: String,
)

private class Settlement(
    val fromMember: String,
    val toMember: String,
    val amountMinor: Long,
    val currency: String,
    val rate: String,
    val date: String,
    var deleted: Boolean,
)

private fun JsonObject.str(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.long(key: String): Long = (getValue(key) as JsonPrimitive).content.toLong()

/** SPEC §4.4. The result does not depend on the input order of [ops]. */
fun fold(groupId: String, ops: List<JsonObject>): FoldState {
    val invalid = ArrayList<String>()
    val unknown = ArrayList<String>()
    val candidates = ArrayList<JsonObject>()
    for (op in ops) {
        val type = strOrNull(op["type"])
        if (intOrNull(op["v"]) != 1L || type == null || type !in KNOWN_TYPES) {
            unknown.add(opRef(op))
            continue
        }
        if (strOrNull(op["group"]) != groupId) {
            invalid.add(opRef(op))
            continue
        }
        try {
            checkBody(type, op["body"])
        } catch (_: Invalid) {
            invalid.add(opRef(op))
            continue
        }
        candidates.add(op)
    }

    candidates.sortWith { a, b ->
        var c = byteCompare(strOrNull(a["hlc"]) ?: "", strOrNull(b["hlc"]) ?: "")
        if (c == 0) c = byteCompare(opAuthor(a), opAuthor(b))
        if (c == 0) c = opSeq(a).compareTo(opSeq(b))
        c
    }

    var group: GroupInfo? = null
    val members = LinkedHashMap<String, Member>()
    val claimOfDevice = HashMap<String, String>()
    val expenses = LinkedHashMap<String, Expense>()
    val settlements = LinkedHashMap<String, Settlement>()

    fun claim(memberId: String, device: String) {
        val member = members[memberId]
        if (member == null || member.removed || member.claimedBy != null) return
        val previous = claimOfDevice[device]
        if (previous != null) members.getValue(previous).claimedBy = null
        member.claimedBy = device
        claimOfDevice[device] = memberId
    }

    for (op in candidates) {
        val t = strOrNull(op["type"])!!
        val b = op["body"] as JsonObject
        val author = opAuthor(op)
        when (t) {
            "group.create" -> {
                if (group != null || opSeq(op) != 1L) {
                    invalid.add(opRef(op))
                    continue
                }
                group = GroupInfo(b.str("name"), b.str("baseCurrency"))
                val creator = b.str("creatorMemberId")
                if (creator !in members) members[creator] = Member(b.str("creatorName"))
                claim(creator, author)
            }
            "group.rename" -> {
                val g = group
                if (g != null) group = g.copy(name = b.str("name"))
            }
            "member.add" -> {
                val id = b.str("memberId")
                if (id !in members) members[id] = Member(b.str("name"))
            }
            "member.rename" -> members[b.str("memberId")]?.name = b.str("name")
            "member.claim" -> claim(b.str("memberId"), author)
            "member.unclaim" -> {
                val member = members[b.str("memberId")]
                // Any device may release any claim (SPEC §4.4).
                val holder = member?.claimedBy
                if (member != null && holder != null) {
                    member.claimedBy = null
                    claimOfDevice.remove(holder)
                }
            }
            "member.remove" -> {
                val member = members[b.str("memberId")]
                if (member != null && !member.removed) {
                    member.removed = true
                    val holder = member.claimedBy
                    if (holder != null) {
                        claimOfDevice.remove(holder)
                        member.claimedBy = null
                    }
                }
            }
            "expense.add" -> {
                val id = b.str("expenseId")
                if (id !in expenses) {
                    expenses[id] = Expense(
                        b.str("title"), b.str("date"), b.str("category"), b.str("paidBy"),
                        b.getValue("money") as JsonObject, false, author,
                    )
                }
            }
            "expense.edit" -> {
                val e = expenses[b.str("expenseId")]
                if (e != null && !e.deleted) {
                    for ((key, value) in b.getValue("fields") as JsonObject) {
                        when (key) {
                            "title" -> e.title = (value as JsonPrimitive).content
                            "date" -> e.date = (value as JsonPrimitive).content
                            "category" -> e.category = (value as JsonPrimitive).content
                            "paidBy" -> e.paidBy = (value as JsonPrimitive).content
                            "money" -> e.money = value as JsonObject
                        }
                    }
                }
            }
            "expense.delete" -> expenses[b.str("expenseId")]?.deleted = true
            "settlement.add" -> {
                val id = b.str("settlementId")
                if (id !in settlements) {
                    settlements[id] = Settlement(
                        b.str("fromMember"), b.str("toMember"), b.long("amountMinor"),
                        b.str("currency"), b.str("rate"), b.str("date"), false,
                    )
                }
            }
            "settlement.delete" -> settlements[b.str("settlementId")]?.deleted = true
        }
    }

    // End-of-fold validity and balances (SPEC §4.4 rule 5, §6).
    val base = group?.baseCurrency
    val balances = LinkedHashMap<String, Long>()
    if (group != null) for (id in members.keys) balances[id] = 0L

    fun moneyBase(currency: String, rate: String, amount: Long): Long? {
        if (base == null) return null
        if (currency == base && rate != "1") return null
        return try {
            convert(amount, currency, rate, base)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    val expenseOut = ArrayList<ExpenseState>()
    for (id in expenses.keys.sortedWith(BYTE_ORDER)) {
        val e = expenses.getValue(id)
        val money = e.money
        val amount = (money.getValue("amountMinor") as JsonPrimitive).content.toLong()
        val split = money.getValue("split") as JsonObject
        val baseAmount = moneyBase(money.str("currency"), money.str("rate"), amount)
        val involved = listOf(e.paidBy) + splitMembers(split)
        val ok = baseAmount != null && involved.all { it in members }
        var shares: Map<String, Long>? = null
        if (ok && baseAmount != null) {
            val s = splitShares(baseAmount, amount, split)
            shares = s
            if (!e.deleted) {
                balances[e.paidBy] = balances.getValue(e.paidBy) + baseAmount
                for ((m, share) in s) balances[m] = balances.getValue(m) - share
            }
        }
        expenseOut.add(
            ExpenseState(
                id, e.title, e.date, e.category, e.paidBy, money, e.deleted, e.createdBy,
                !ok, if (ok) baseAmount else null, shares,
            ),
        )
    }

    val settlementOut = ArrayList<SettlementState>()
    for (id in settlements.keys.sortedWith(BYTE_ORDER)) {
        val s = settlements.getValue(id)
        val baseAmount = moneyBase(s.currency, s.rate, s.amountMinor)
        val ok = baseAmount != null && s.fromMember in members && s.toMember in members
        if (ok && baseAmount != null && !s.deleted) {
            balances[s.fromMember] = balances.getValue(s.fromMember) + baseAmount
            balances[s.toMember] = balances.getValue(s.toMember) - baseAmount
        }
        settlementOut.add(
            SettlementState(
                id, s.fromMember, s.toMember, s.amountMinor, s.currency, s.rate, s.date, s.deleted,
                !ok, if (ok) baseAmount else null,
            ),
        )
    }

    check(balances.values.sum() == 0L) { "balances must sum to zero" }
    return FoldState(
        group = group,
        members = members.keys.sortedWith(BYTE_ORDER).map {
            val m = members.getValue(it)
            MemberState(it, m.name, m.removed, m.claimedBy)
        },
        expenses = expenseOut,
        settlements = settlementOut,
        balances = balances,
        suggestedPayments = settle(balances),
        invalidOps = invalid.sortedWith(BYTE_ORDER),
        unknownOps = unknown.sortedWith(BYTE_ORDER),
    )
}

