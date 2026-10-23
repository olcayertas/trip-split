package com.olcayertas.tripsplit.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR matrix of [text] (ZXing core). Error correction M, a quiet zone of 2 modules. */
fun qrMatrix(text: String, size: Int = 0): BitMatrix =
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )

/** Black on white always, so every scanner reads it in dark mode too. */
fun qrPixels(matrix: BitMatrix, scale: Int): IntArray {
    val w = matrix.width * scale
    val h = matrix.height * scale
    return IntArray(w * h) { i -> if (matrix.get((i % w) / scale, (i / w) / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
}

fun qrBitmap(text: String, scale: Int = 8): Bitmap {
    val m = qrMatrix(text)
    val pixels = qrPixels(m, scale)
    return Bitmap.createBitmap(pixels, m.width * scale, m.height * scale, Bitmap.Config.ARGB_8888)
}
