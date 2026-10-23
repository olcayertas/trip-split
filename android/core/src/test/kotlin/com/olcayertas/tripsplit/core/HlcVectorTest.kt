package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HlcVectorTest {
    private val v = Vectors.load("hlc")

    @Test
    fun sequencesReplay() {
        for (seq in v.getValue("sequences").objs()) {
            val name = seq.getValue("name").str()
            val hlc = Hlc(seq.getValue("device").str(), seq.getValue("startMillis").long(), seq.getValue("startCounter").long().toInt())
            for ((i, step) in seq.getValue("steps").objs().withIndex()) {
                val label = "$name step $i"
                when (val event = step.getValue("event").str()) {
                    "send" -> assertEquals(step.getValue("stamp").str(), hlc.send(step.getValue("wall").long()), label)
                    "receive" -> {
                        val refused = hlc.receive(step.getValue("remote").str(), step.getValue("wall").long())
                        assertEquals(step.getValue("refused").jsonPrimitive.content.toBoolean(), refused, label)
                        assertEquals(step.getValue("state").str(), hlc.current(), label)
                    }
                    else -> error("unknown event $event")
                }
            }
        }
    }

    @Test
    fun stampsSortByBytes() {
        val sorted = v.getValue("sortInput").strings().sortedWith(BYTE_ORDER)
        assertEquals(v.getValue("sorted").strings(), sorted)
    }

    @Test
    fun counterOverflowCarriesIntoMillis() {
        val hlc = Hlc("d", 1000, MAX_COUNTER)
        assertEquals(hlcFormat(1001, 0, "d"), hlc.send(500))
    }
}
