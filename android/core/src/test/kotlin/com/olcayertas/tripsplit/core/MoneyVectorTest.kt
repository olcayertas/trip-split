package com.olcayertas.tripsplit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyVectorTest {
    private val v = Vectors.load("money")

    @Test
    fun conversions() {
        for (c in v.getValue("convert").objs()) {
            val amount = c.getValue("amountMinor").long()
            val cur = c.getValue("currency").str()
            val rate = c.getValue("rate").str()
            val base = c.getValue("base").str()
            if (c.containsKey("error")) {
                assertFailsWith<IllegalArgumentException>("$amount $cur @$rate -> $base") { convert(amount, cur, rate, base) }
            } else {
                assertEquals(c.getValue("baseMinor").long(), convert(amount, cur, rate, base), "$amount $cur @$rate -> $base")
            }
        }
    }

    @Test
    fun rates() {
        for (c in v.getValue("rates").objs()) {
            val rate = c.getValue("rate").str()
            if (c.getValue("valid").str() == "true") {
                val r = parseRate(rate)
                assertEquals(c.getValue("mantissa").long(), r.mantissa, rate)
                assertEquals(c.getValue("scale").long().toInt(), r.scale, rate)
            } else {
                assertFailsWith<IllegalArgumentException>("rate '$rate'") { parseRate(rate) }
            }
        }
    }
}
