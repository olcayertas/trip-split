package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementVectorTest {
    @Test
    fun paymentsMatch() {
        for ((i, c) in Vectors.load("settlement").getValue("cases").objs().withIndex()) {
            val balances = c.getValue("balances").jsonObject.longs()
            val expected = c.getValue("payments").objs().map {
                Payment(it.getValue("from").str(), it.getValue("to").str(), it.getValue("amountMinor").long())
            }
            val payments = settle(balances)
            assertEquals(expected, payments, "case $i")
            val after = HashMap(balances)
            for (p in payments) {
                after[p.from] = after.getValue(p.from) + p.amountMinor
                after[p.to] = after.getValue(p.to) - p.amountMinor
            }
            assertEquals(true, after.values.all { it == 0L }, "case $i settles to zero")
        }
    }
}
