package com.olcayertas.tripsplit.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivedOpVectorTest {
    @Test
    fun cases() {
        val v = Vectors.load("received_op")
        val group = v.getValue("group").str()
        for ((i, c) in v.getValue("cases").objs().withIndex()) {
            val want = c.getValue("result").str().uppercase()
            assertEquals(OpCheck.valueOf(want), checkReceivedOp(c.getValue("op"), group), "case $i")
        }
    }

    @Test
    fun nonObjectIsShape() {
        assertEquals(OpCheck.SHAPE, checkReceivedOp(kotlinx.serialization.json.JsonPrimitive(1), "g"))
    }
}
