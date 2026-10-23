package com.olcayertas.tripsplit.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.olcayertas.tripsplit.core.makeInvite
import com.olcayertas.tripsplit.core.parseInvite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QrCodeTest {
    private fun decode(text: String, scale: Int): String {
        val m = qrMatrix(text)
        val pixels = qrPixels(m, scale)
        val source = RGBLuminanceSource(m.width * scale, m.height * scale, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun theInviteSurvivesAQrRoundTrip() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val link = makeInvite("00000000-0000-4000-8000-000000000001", key)
        for (scale in listOf(4, 8)) {
            val back = decode(link, scale)
            assertEquals(link, back)
            assertNotNull(parseInvite(back))
            val invite = parseInvite(back)!!
            assertArrayEquals(key, invite.groupKey)
        }
    }

    @Test
    fun theCodeIsAlwaysBlackOnWhite() {
        val m = qrMatrix("tripsplit")
        val px = qrPixels(m, 2)
        assertEquals(setOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), px.toSet())
        assertEquals(0xFFFFFFFF.toInt(), px[0]) // the quiet zone is white
    }
}
