package com.olcayertas.tripsplit.ui

import com.olcayertas.tripsplit.core.makeInvite
import com.olcayertas.tripsplit.core.parseInvite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** QrDecoder on frames that look like the Y plane of a camera: grey, noisy, padded rows, any rotation. */
class QrDecoderTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val link = makeInvite("00000000-0000-4000-8000-000000000001", key)

    private class Frame(val luma: ByteArray, val rowStride: Int, val width: Int, val height: Int)

    /**
     * A [width] x [height] frame with [padding] extra bytes at the end of each row. The QR code of [text] sits at
     * ([x], [y]) with [scale] pixels per module, turned by [quarterTurns] x 90 degrees. Noise of +/- [noise].
     */
    private fun frame(
        text: String?, width: Int = 640, height: Int = 480, padding: Int = 0, x: Int = 150, y: Int = 90,
        scale: Int = 5, quarterTurns: Int = 0, noise: Int = 20, seed: Int = 1, damage: Boolean = false,
    ): Frame {
        val rnd = Random(seed)
        val stride = width + padding
        val luma = ByteArray(stride * height) { (150 + rnd.nextInt(-noise, noise + 1)).toByte() }
        if (text != null) {
            val m = qrMatrix(text)
            val side = m.width * scale
            for (py in 0 until side) for (px in 0 until side) {
                var mx = px / scale
                var my = py / scale
                repeat(quarterTurns % 4) { val t = mx; mx = m.width - 1 - my; my = t }
                val dark = m.get(mx, my) && !(damage && mx < 10 && my < 10)  // damage wipes one finder pattern
                val base = if (dark) 30 else 220
                luma[(y + py) * stride + x + px] = (base + rnd.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte()
            }
        }
        return Frame(luma, stride, width, height)
    }

    private fun decode(f: Frame) = QrDecoder().decode(f.luma, f.rowStride, f.width, f.height)

    @Test
    fun readsTheInviteFromACameraLikeFrame() {
        val text = decode(frame(link))
        assertEquals(link, text)
        assertArrayEquals(key, parseInvite(text!!)!!.groupKey)
    }

    @Test
    fun readsTheCodeInEveryRotation() {
        for (turns in 0..3) assertEquals("rotation ${turns * 90}", link, decode(frame(link, quarterTurns = turns)))
    }

    @Test
    fun readsRowsWithPadding() {
        // Many phones give a row stride larger than the width. The decoder must skip the padding bytes.
        assertEquals(link, decode(frame(link, padding = 64)))
    }

    @Test
    fun readsASmallCodeFarFromTheCentre() {
        assertEquals(link, decode(frame(link, scale = 3, x = 400, y = 300, noise = 10)))
    }

    @Test
    fun oneDecoderReadsManyFrames() {
        val d = QrDecoder()
        val frames = listOf(frame(null, seed = 2), frame(link, seed = 3), frame(null, seed = 4), frame(link, seed = 5, quarterTurns = 1))
        assertEquals(listOf(null, link, null, link), frames.map { d.decode(it.luma, it.rowStride, it.width, it.height) })
    }

    @Test
    fun negativeControls() {
        assertNull("a frame with no code", decode(frame(null)))
        assertNull("a code with one finder pattern wiped", decode(frame(link, damage = true)))
        // Wrong stride: the rows shift, so the code falls apart. This shows the test reads the stride for real.
        val padded = frame(link, padding = 64)
        assertNull("wrong row stride", QrDecoder().decode(padded.luma, padded.width + 7, padded.width, padded.height))
    }
}
