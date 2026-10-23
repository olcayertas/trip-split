package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SplitVectorTest {
    @Test
    fun sharesMatch() {
        for ((i, c) in Vectors.load("split").getValue("cases").objs().withIndex()) {
            val total = c.getValue("baseTotal").long()
            val shares = splitShares(total, c.getValue("amountMinor").long(), c.getValue("split").jsonObject)
            assertEquals(c.getValue("shares").jsonObject.longs(), shares, "case $i")
            assertEquals(total, shares.values.sum(), "case $i sum")
        }
    }
}
