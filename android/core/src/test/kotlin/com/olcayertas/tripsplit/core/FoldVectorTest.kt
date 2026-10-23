package com.olcayertas.tripsplit.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FoldVectorTest {
    @Test
    fun foldsMatchInGivenAndReversedOrder() {
        for (c in Vectors.load("fold").getValue("cases").objs()) {
            val name = c.getValue("name").str()
            val group = c.getValue("group").str()
            val ops = c.getValue("ops").objs()
            val expected = c.getValue("expected")
            for ((label, input) in listOf("given" to ops, "reversed" to ops.reversed())) {
                val state = fold(group, input)
                assertEquals(expected, state.toJson(), "$name ($label)")
                assertEquals(0L, state.balances.values.sum(), "$name ($label) balances sum")
            }
        }
    }
}
