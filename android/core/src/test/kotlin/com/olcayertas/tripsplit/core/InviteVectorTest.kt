package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InviteVectorTest {
    @Test
    fun parseCases() {
        for (c in Vectors.load("invite").getValue("cases").objs()) {
            val url = c.getValue("url").str()
            val invite = parseInvite(url)
            if (c.getValue("valid").jsonPrimitive.content == "true") {
                assertNotNull(invite, url)
                assertEquals(c.getValue("groupId").str(), invite.groupId)
                assertEquals(c.getValue("keyHex").str(), toHex(invite.groupKey))
                assertEquals(url, makeInvite(invite.groupId, invite.groupKey))
            } else {
                assertNull(invite, url)
            }
        }
    }
}
