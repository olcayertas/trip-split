package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionVectorTest {
    private val v = Vectors.load("session")
    private val all: List<JsonObject> = v.getValue("ops").objs()

    private fun pick(refs: List<String>) = all.filter { opRef(it) in refs }

    @Test
    fun opHashesMatch() {
        for (op in all) assertEquals(op.getValue("hash").str(), opHash(op), opRef(op))
    }

    @Test
    fun vectorsAndDiffs() {
        for (c in v.getValue("cases").objs()) {
            val a = pick(c.getValue("sideA").strings())
            val b = pick(c.getValue("sideB").strings())
            val vecA = versionVector(a)
            val vecB = versionVector(b)
            assertEquals(c.getValue("vectorA").jsonObject.longs(), vecA)
            assertEquals(c.getValue("vectorB").jsonObject.longs(), vecB)
            assertEquals(c.getValue("aSends").strings(), missingFor(vecB, a))
            assertEquals(c.getValue("bSends").strings(), missingFor(vecA, b))
        }
    }
}
