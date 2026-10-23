package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputAndBuildersTest {
    private fun ok(v: Long) = InputResult.Ok(v)
    private fun bad(e: InputError) = InputResult.Bad(e)

    @Test
    fun amountAcceptsCommaAndDotAsDecimalMark() {
        assertEquals(ok(1250), parseAmount("12.50", 2))
        assertEquals(ok(1250), parseAmount("12,50", 2))
        assertEquals(ok(1250), parseAmount(" 12,5 ", 2))
        assertEquals(ok(5), parseAmount("0,05", 2))
        assertEquals(ok(5), parseAmount(",05", 2))
        assertEquals(ok(1200), parseAmount("12", 2))
        assertEquals(ok(12), parseAmount("12", 0))
    }

    @Test
    fun amountGroupingMarks() {
        assertEquals(ok(123456), parseAmount("1.234,56", 2))
        assertEquals(ok(123456), parseAmount("1,234.56", 2))
        assertEquals(ok(123456700), parseAmount("1.234.567", 2))
        assertEquals(ok(1000), parseAmount("1.000", 0), "three digits after a mark, no minor units")
        assertEquals(ok(1000000), parseAmount("1,000,000", 0))
        assertEquals(ok(100000), parseAmount("1 000", 2))
    }

    @Test
    fun amountErrors() {
        assertEquals(bad(InputError.EMPTY), parseAmount("  ", 2))
        assertEquals(bad(InputError.SYNTAX), parseAmount("12a", 2))
        assertEquals(bad(InputError.SYNTAX), parseAmount("-5", 2))
        assertEquals(bad(InputError.SYNTAX), parseAmount(".", 2))
        assertEquals(bad(InputError.TOO_MANY_DECIMALS), parseAmount("1.234", 2), "never a silent rounding")
        assertEquals(bad(InputError.TOO_MANY_DECIMALS), parseAmount("5,5", 0))
        assertEquals(bad(InputError.OUT_OF_RANGE), parseAmount("0", 2))
        assertEquals(bad(InputError.OUT_OF_RANGE), parseAmount("10000000000,01", 2))
        assertEquals(ok(1_000_000_000_000), parseAmount("10000000000", 2))
    }

    @Test
    fun amountThreeDecimalCurrency() {
        assertEquals(ok(1234), parseAmount("1,234", 3))
        assertEquals(ok(1234), parseAmount("1.234", 3))
    }

    @Test
    fun formatPlain() {
        assertEquals("12.50", formatAmountPlain(1250, 2))
        assertEquals("0.05", formatAmountPlain(5, 2))
        assertEquals("12", formatAmountPlain(12, 0))
        assertEquals("-0.50", formatAmountPlain(-50, 2))
        assertEquals("1.234", formatAmountPlain(1234, 3))
    }

    @Test
    fun rateInput() {
        assertEquals(InputResult.Ok("34.75"), parseRateInput("34,75"))
        assertEquals(InputResult.Ok("34.75"), parseRateInput("34.750"))
        assertEquals(InputResult.Ok("0.5"), parseRateInput(",5"))
        assertEquals(InputResult.Ok("2"), parseRateInput("2."))
        assertEquals(bad(InputError.OUT_OF_RANGE), parseRateInput("0"))
        assertEquals(bad(InputError.OUT_OF_RANGE), parseRateInput("1.123456789"))
        assertEquals(bad(InputError.OUT_OF_RANGE), parseRateInput("1234567890"))
        assertEquals(bad(InputError.SYNTAX), parseRateInput("1,2,3"))
        assertEquals(bad(InputError.EMPTY), parseRateInput(""))
    }

    private fun expense(id: String, split: JsonObject, deleted: Boolean = false) = ExpenseState(
        id, "t", "2026-10-23", "food", "m1", OpBodies.money(1000, "EUR", "1", split), deleted, "d", false, 1000, null,
    )

    @Test
    fun earlierExpensePlan() {
        val m = "20000000-0000-4000-8000-000000000009"
        val plan = planEarlierExpenses(
            listOf(
                expense("e1", OpBodies.equalSplit(listOf("m1", "m2"))),
                expense("e2", OpBodies.weightsSplit(mapOf("m1" to 2L, "m2" to 3L))),
                expense("e3", OpBodies.exactSplit(mapOf("m1" to 400L, "m2" to 600L))),
                expense("e4", OpBodies.equalSplit(listOf("m1")), deleted = true),
                expense("e5", OpBodies.equalSplit(listOf("m1", m))),
                expense("e6", OpBodies.equalSplit((1..100).map { "m$it" })),
            ),
            m,
        )
        assertEquals(listOf("e1", "e2"), plan.edits.map { it.expenseId })
        assertEquals(listOf("e3", "e6"), plan.untouched)
        val e1 = plan.edits[0].money.getValue("split").jsonObject.getValue("members").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("m1", "m2", m), e1)
        val e2 = plan.edits[1].money.getValue("split").jsonObject.getValue("weights").jsonObject
        assertEquals("1", e2.getValue(m).jsonPrimitive.content)
        assertEquals("2", e2.getValue("m1").jsonPrimitive.content)
        // The other money values stay as they were.
        assertEquals("1000", plan.edits[0].money.getValue("amountMinor").jsonPrimitive.content)
    }

    @Test
    fun exactSplitThatAlreadyHasTheMemberIsNotListed() {
        val plan = planEarlierExpenses(listOf(expense("e1", OpBodies.exactSplit(mapOf("m1" to 1000L, "mx" to 0L)))), "mx")
        assertTrue(plan.edits.isEmpty() && plan.untouched.isEmpty())
    }

    @Test
    fun historyIsNewestFirstAndKeepsTitles() {
        fun op(seq: Int, ms: Long, type: String, body: JsonObject): JsonObject {
            val author = "aaaaaaaa-0000-4000-8000-000000000001"
            val u = buildJsonObject {
                put("v", kotlinx.serialization.json.JsonPrimitive(1)); put("group", kotlinx.serialization.json.JsonPrimitive("g"))
                put("author", kotlinx.serialization.json.JsonPrimitive(author)); put("seq", kotlinx.serialization.json.JsonPrimitive(seq))
                put("hlc", kotlinx.serialization.json.JsonPrimitive(hlcFormat(ms, 0, author)))
                put("type", kotlinx.serialization.json.JsonPrimitive(type)); put("body", body)
            }
            return JsonObject(u + ("hash" to kotlinx.serialization.json.JsonPrimitive(opHash(u))))
        }
        val m = "20000000-0000-4000-8000-000000000002"
        val e = "30000000-0000-4000-8000-000000000003"
        val ops = listOf(
            op(2, 2000, "expense.add", OpBodies.expenseAdd(e, "Dinner", "2026-10-23", "food", m, OpBodies.money(1000, "EUR", "1", OpBodies.equalSplit(listOf(m))))),
            op(1, 1000, "member.add", OpBodies.memberAdd(m, "Ayşe")),
            op(3, 3000, "expense.delete", OpBodies.expenseDelete(e)),
            op(4, 4000, "poll.add", buildJsonObject { }),
        )
        val h = buildHistory(ops)
        assertEquals(listOf("expense.delete", "expense.add", "member.add"), h.map { it.type })
        assertEquals("Dinner", h[0].subject)
        assertEquals("Ayşe", h[2].subject)
    }
}
