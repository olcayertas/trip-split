package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One `expense.edit` that puts a member into the split of an earlier expense. */
class EarlierEdit(val expenseId: String, val money: JsonObject) {
    fun fields(): JsonObject = buildJsonObject { put("money", money) }
}

/**
 * The result of [planEarlierExpenses]. [edits] are written as `expense.edit` ops. [untouched] lists the
 * expenses that the app cannot change on its own: exact splits (the shares must sum to the amount, so the
 * user decides) and full splits. The UI shows them.
 */
class EarlierPlan(val edits: List<EarlierEdit>, val untouched: List<String>)

/**
 * "Also earlier expenses" (PLAN §7.1): for each expense that is not deleted, put [memberId] into the split.
 * equal: add the id. weights: add with weight 1. exact: leave it and list it. An expense that already
 * holds the member, or whose split has no room (100 entries), is left as it is.
 */
fun planEarlierExpenses(expenses: List<ExpenseState>, memberId: String): EarlierPlan {
    val edits = ArrayList<EarlierEdit>()
    val untouched = ArrayList<String>()
    for (e in expenses.sortedBy { it.id }) {
        if (e.deleted) continue
        val split = e.money.getValue("split").jsonObject
        when (split.getValue("mode").jsonPrimitive.content) {
            "equal" -> {
                val members = split.getValue("members").jsonArray.map { it.jsonPrimitive.content }
                if (memberId in members) continue
                if (members.size >= MAX_SPLIT) { untouched.add(e.id); continue }
                edits.add(EarlierEdit(e.id, withSplit(e.money, OpBodies.equalSplit(members + memberId))))
            }
            "weights" -> {
                val weights = split.getValue("weights").jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }
                if (memberId in weights) continue
                if (weights.size >= MAX_SPLIT) { untouched.add(e.id); continue }
                edits.add(EarlierEdit(e.id, withSplit(e.money, OpBodies.weightsSplit(weights + (memberId to 1L)))))
            }
            else -> {
                val shares = split.getValue("shares").jsonObject
                if (memberId !in shares) untouched.add(e.id)
            }
        }
    }
    return EarlierPlan(edits, untouched)
}

private const val MAX_SPLIT = 100

private fun withSplit(money: JsonObject, split: JsonObject) = JsonObject(money + ("split" to split))
