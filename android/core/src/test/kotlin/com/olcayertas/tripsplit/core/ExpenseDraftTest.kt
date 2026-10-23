package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExpenseDraftTest {
    private val a = "20000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"

    private fun base() = ExpenseDraft(
        baseCurrency = "EUR", title = " Dinner ", amountText = "12,50", paidBy = a, date = "2026-10-23",
        selected = setOf(b, a),
    )

    private fun errors(d: ExpenseDraft) = (d.validate() as DraftResult.Invalid).errors

    @Test
    fun equalSplitWithCommaAmount() {
        val ok = assertIs<DraftResult.Ok>(base().validate()).values
        assertEquals("Dinner", ok.title)
        assertEquals(1250, ok.amountMinor)
        assertEquals(listOf(a, b), ok.money.getValue("split").jsonObject["members"]!!.let { m -> (m as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content } })
        // The body is valid by SPEC §4.3.
        checkBody("expense.add", OpBodies.expenseAdd("30000000-0000-4000-8000-000000000001", ok.title, ok.date, ok.category, ok.paidBy, ok.money))
    }

    @Test
    fun foreignCurrencyNeedsARate() {
        val d = base().copy(currency = "TRY", rateText = "")
        assertEquals(setOf(DraftError.RATE), errors(d))
        val ok = assertIs<DraftResult.Ok>(d.copy(rateText = "34,75").validate()).values
        assertEquals("34.75", ok.money.getValue("rate").jsonPrimitive.content)
        // Same currency as the base: rate is always "1", whatever the field holds.
        val same = assertIs<DraftResult.Ok>(base().copy(rateText = "5").validate()).values
        assertEquals("1", same.money.getValue("rate").jsonPrimitive.content)
    }

    @Test
    fun weightsAndExact() {
        val w = base().copy(mode = SplitMode.WEIGHTS, weightText = mapOf(a to "2", b to "3"))
        assertIs<DraftResult.Ok>(w.validate())
        assertEquals(setOf(DraftError.WEIGHT), errors(w.copy(weightText = mapOf(a to "0", b to "3"))))
        assertEquals(setOf(DraftError.WEIGHT), errors(w.copy(weightText = mapOf(a to "2"))))
        val e = base().copy(mode = SplitMode.EXACT, exactText = mapOf(a to "10", b to "2,50"))
        assertIs<DraftResult.Ok>(e.validate())
        assertEquals(setOf(DraftError.EXACT_SUM), errors(e.copy(exactText = mapOf(a to "10", b to "2"))))
        assertEquals(setOf(DraftError.EXACT_AMOUNT), errors(e.copy(exactText = mapOf(a to "10", b to "x"))))
        // A zero share is allowed, and an empty field counts as zero.
        assertIs<DraftResult.Ok>(e.copy(exactText = mapOf(a to "12,50")).validate())
    }

    @Test
    fun missingPieces() {
        val d = ExpenseDraft(baseCurrency = "EUR", date = "2026-02-31x")
        assertEquals(
            setOf(DraftError.TITLE, DraftError.AMOUNT, DraftError.DATE, DraftError.PAID_BY, DraftError.SPLIT_EMPTY),
            errors(d),
        )
        assertTrue(!ExpenseDraft.isDate("2026-13-01") && ExpenseDraft.isDate("2026-02-28"))
        assertEquals(setOf(DraftError.TITLE), errors(base().copy(title = "   ")))
        assertEquals(setOf(DraftError.TITLE), errors(base().copy(title = "x".repeat(101))))
    }

    @Test
    fun roundTripFromStateAndEditFields() {
        val ok = assertIs<DraftResult.Ok>(base().copy(mode = SplitMode.WEIGHTS, weightText = mapOf(a to "2", b to "3")).validate()).values
        val state = ExpenseState("e", ok.title, ok.date, ok.category, ok.paidBy, ok.money, false, "d", false, null, null)
        val back = ExpenseDraft.from(state, "EUR")
        assertEquals(ok, assertIs<DraftResult.Ok>(back.validate()).values)
        assertEquals(0, editFields(state, ok).size)
        val changed = assertIs<DraftResult.Ok>(back.copy(title = "Lunch", amountText = "13").validate()).values
        assertEquals(setOf("title", "money"), editFields(state, changed).keys)
        checkBody("expense.edit", OpBodies.expenseEdit("30000000-0000-4000-8000-000000000001", editFields(state, changed)))
        // exact split round trip keeps decimal text
        val ex = assertIs<DraftResult.Ok>(base().copy(mode = SplitMode.EXACT, exactText = mapOf(a to "10", b to "2.5")).validate()).values
        val exState = state.copy(money = ex.money)
        assertEquals(ex, assertIs<DraftResult.Ok>(ExpenseDraft.from(exState, "EUR").validate()).values)
    }

    @Test
    fun earlierEditBodyIsValid() {
        val c = "20000000-0000-4000-8000-00000000000c"
        val ok = assertIs<DraftResult.Ok>(base().validate()).values
        val state = ExpenseState("30000000-0000-4000-8000-000000000001", ok.title, ok.date, ok.category, ok.paidBy, ok.money, false, "d", false, null, null)
        val plan = planEarlierExpenses(listOf(state), c)
        assertEquals(1, plan.edits.size)
        checkBody("expense.edit", OpBodies.expenseEdit(state.id, plan.edits[0].fields()))
    }
}
