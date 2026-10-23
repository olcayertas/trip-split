package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrenciesVectorTest {
    @Test
    fun tableMatches() {
        val expected = Vectors.load("currencies").getValue("currencies").jsonObject
            .mapValues { it.value.long().toInt() }
        assertEquals(expected, CURRENCIES)
    }
}
