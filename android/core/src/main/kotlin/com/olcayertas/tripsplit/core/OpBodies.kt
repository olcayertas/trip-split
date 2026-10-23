package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders for the op bodies of SPEC §4.3, so the UI never writes raw JSON keys. */
object OpBodies {
    fun groupRename(name: String) = buildJsonObject { put("name", name) }

    fun memberAdd(memberId: String, name: String) = buildJsonObject { put("memberId", memberId); put("name", name) }

    fun memberRename(memberId: String, name: String) = memberAdd(memberId, name)

    fun memberRef(memberId: String) = buildJsonObject { put("memberId", memberId) }

    fun equalSplit(members: List<String>) = buildJsonObject {
        put("mode", "equal")
        put("members", JsonArray(members.map { JsonPrimitive(it) }))
    }

    fun weightsSplit(weights: Map<String, Long>) = buildJsonObject {
        put("mode", "weights")
        put("weights", JsonObject(weights.mapValues { JsonPrimitive(it.value) }))
    }

    fun exactSplit(shares: Map<String, Long>) = buildJsonObject {
        put("mode", "exact")
        put("shares", JsonObject(shares.mapValues { JsonPrimitive(it.value) }))
    }

    fun money(amountMinor: Long, currency: String, rate: String, split: JsonObject) = buildJsonObject {
        put("amountMinor", amountMinor)
        put("currency", currency)
        put("rate", rate)
        put("split", split)
    }

    fun expenseAdd(
        expenseId: String, title: String, date: String, category: String, paidBy: String, money: JsonObject,
    ) = buildJsonObject {
        put("expenseId", expenseId)
        put("title", title)
        put("date", date)
        put("category", category)
        put("paidBy", paidBy)
        put("money", money)
    }

    fun expenseEdit(expenseId: String, fields: JsonObject) = buildJsonObject {
        put("expenseId", expenseId)
        put("fields", fields)
    }

    fun expenseDelete(expenseId: String) = buildJsonObject { put("expenseId", expenseId) }

    fun settlementAdd(
        settlementId: String, from: String, to: String, amountMinor: Long, currency: String, rate: String, date: String,
    ) = buildJsonObject {
        put("settlementId", settlementId)
        put("fromMember", from)
        put("toMember", to)
        put("amountMinor", amountMinor)
        put("currency", currency)
        put("rate", rate)
        put("date", date)
    }

    fun settlementDelete(settlementId: String) = buildJsonObject { put("settlementId", settlementId) }
}

val EXPENSE_CATEGORIES = listOf("food", "transport", "lodging", "activities", "shopping", "other")
