package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalJsonVectorTest {
    private val v = Vectors.load("canonical_json")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    @Test
    fun casesMatch() {
        for (c in v.getValue("cases").objs()) {
            val input = c.getValue("input")
            assertEquals(c.getValue("canonical").str(), canonical(input))
            assertEquals(c.getValue("utf8Hex").str(), hex(canonicalBytes(input)))
        }
    }

    @Test
    fun opHashMatches() {
        for (c in v.getValue("opHash").objs()) {
            val op = c.getValue("op").jsonObject
            assertEquals(c.getValue("hash").str(), opHash(op))
        }
    }

    @Test
    fun rejectedInputsThrow() {
        for (c in v.getValue("rejected").objs()) {
            val parsed = Json.parseToJsonElement(c.getValue("json").str())
            assertFailsWith<CanonicalJsonException>(c.getValue("note").str()) { canonical(parsed) }
        }
    }
}
