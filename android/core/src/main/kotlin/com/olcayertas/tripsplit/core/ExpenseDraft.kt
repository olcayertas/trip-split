package com.olcayertas.tripsplit.core

import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SplitMode { EQUAL, WEIGHTS, EXACT }

/** What is wrong with a draft. The UI shows each one next to its field. */
enum class DraftError { TITLE, AMOUNT, RATE, DATE, PAID_BY, SPLIT_EMPTY, WEIGHT, EXACT_AMOUNT, EXACT_SUM }

/** A valid draft, as the pieces of an `expense.add` body. */
data class ExpenseValues(
    val title: String,
    val date: String,
    val category: String,
    val paidBy: String,
    val money: JsonObject,
) {
    val amountMinor: Long get() = money.getValue("amountMinor").jsonPrimitive.content.toLong()
    val currency: String get() = money.getValue("currency").jsonPrimitive.content
}

sealed interface DraftResult {
    data class Ok(val values: ExpenseValues) : DraftResult
    data class Invalid(val errors: Set<DraftError>) : DraftResult
}

/** What the user typed in the expense form. Text fields stay text until [validate]. */
data class ExpenseDraft(
    val baseCurrency: String,
    val title: String = "",
    val amountText: String = "",
    val currency: String = baseCurrency,
    val rateText: String = "1",
    val paidBy: String? = null,
    val date: String = LocalDate.now().toString(),
    val category: String = "food",
    val mode: SplitMode = SplitMode.EQUAL,
    val selected: Set<String> = emptySet(),
    val weightText: Map<String, String> = emptyMap(),
    val exactText: Map<String, String> = emptyMap(),
) {
    val foreign: Boolean get() = currency != baseCurrency

    fun validate(): DraftResult {
        val errors = LinkedHashSet<DraftError>()
        val cleanTitle = title.trim()
        if (cleanTitle.codePointCount(0, cleanTitle.length) !in 1..100) errors.add(DraftError.TITLE)
        val exponent = CURRENCIES[currency] ?: 2
        val amount = (parseAmount(amountText, exponent) as? InputResult.Ok)?.value
        if (amount == null) errors.add(DraftError.AMOUNT)
        val rate = if (foreign) (parseRateInput(rateText) as? InputResult.Ok)?.value else "1"
        if (rate == null) errors.add(DraftError.RATE)
        if (!isDate(date)) errors.add(DraftError.DATE)
        if (paidBy == null) errors.add(DraftError.PAID_BY)
        if (selected.isEmpty()) errors.add(DraftError.SPLIT_EMPTY)

        val ordered = selected.sortedWith(BYTE_ORDER)
        var split: JsonObject? = null
        if (selected.isNotEmpty()) {
            when (mode) {
                SplitMode.EQUAL -> split = OpBodies.equalSplit(ordered)
                SplitMode.WEIGHTS -> {
                    val weights = ordered.associateWith { id ->
                        weightText[id]?.trim()?.toLongOrNull()?.takeIf { it in 1..1000 }
                    }
                    if (weights.values.any { it == null }) errors.add(DraftError.WEIGHT)
                    else split = OpBodies.weightsSplit(weights.mapValues { it.value!! })
                }
                SplitMode.EXACT -> {
                    val shares = ordered.associateWith { id ->
                        (parseAmount(exactText[id].orEmpty(), exponent, allowZero = true) as? InputResult.Ok)?.value
                    }
                    if (shares.values.any { it == null }) {
                        errors.add(DraftError.EXACT_AMOUNT)
                    } else if (amount != null && shares.values.sumOf { it!! } != amount) {
                        errors.add(DraftError.EXACT_SUM)
                    } else if (amount != null) {
                        split = OpBodies.exactSplit(shares.mapValues { it.value!! })
                    }
                }
            }
        }
        if (errors.isNotEmpty() || amount == null || rate == null || split == null) return DraftResult.Invalid(errors)
        return DraftResult.Ok(
            ExpenseValues(cleanTitle, date, category, paidBy!!, OpBodies.money(amount, currency, rate, split)),
        )
    }

    companion object {
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        fun isDate(s: String): Boolean = DATE.matches(s) && try {
            LocalDate.parse(s)
            true
        } catch (_: Exception) {
            false
        }

        /** Fills the form from an expense of the fold, to edit it. */
        fun from(e: ExpenseState, baseCurrency: String): ExpenseDraft {
            val money = e.money
            val currency = money.getValue("currency").jsonPrimitive.content
            val exponent = CURRENCIES[currency] ?: 2
            val split = money.getValue("split").jsonObject
            val mode = when (split.getValue("mode").jsonPrimitive.content) {
                "weights" -> SplitMode.WEIGHTS
                "exact" -> SplitMode.EXACT
                else -> SplitMode.EQUAL
            }
            val weights = if (mode == SplitMode.WEIGHTS) {
                split.getValue("weights").jsonObject.mapValues { it.value.jsonPrimitive.content }
            } else emptyMap()
            val shares = if (mode == SplitMode.EXACT) {
                split.getValue("shares").jsonObject.mapValues { formatAmountPlain(it.value.jsonPrimitive.content.toLong(), exponent) }
            } else emptyMap()
            val selected = when (mode) {
                SplitMode.EQUAL -> split.getValue("members").jsonArray.map { it.jsonPrimitive.content }.toSet()
                SplitMode.WEIGHTS -> weights.keys
                SplitMode.EXACT -> shares.keys
            }
            return ExpenseDraft(
                baseCurrency = baseCurrency,
                title = e.title,
                amountText = formatAmountPlain(money.getValue("amountMinor").jsonPrimitive.content.toLong(), exponent),
                currency = currency,
                rateText = money.getValue("rate").jsonPrimitive.content,
                paidBy = e.paidBy,
                date = e.date,
                category = e.category,
                mode = mode,
                selected = selected,
                weightText = weights,
                exactText = shares,
            )
        }
    }
}

/** The `fields` of an `expense.edit`: only what changed. `money` is one field (SPEC §4.4). Empty when nothing changed. */
fun editFields(old: ExpenseState, new: ExpenseValues): JsonObject {
    val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    if (old.title != new.title) fields["title"] = kotlinx.serialization.json.JsonPrimitive(new.title)
    if (old.date != new.date) fields["date"] = kotlinx.serialization.json.JsonPrimitive(new.date)
    if (old.category != new.category) fields["category"] = kotlinx.serialization.json.JsonPrimitive(new.category)
    if (old.paidBy != new.paidBy) fields["paidBy"] = kotlinx.serialization.json.JsonPrimitive(new.paidBy)
    if (canonical(old.money) != canonical(new.money)) fields["money"] = new.money
    return JsonObject(fields)
}
