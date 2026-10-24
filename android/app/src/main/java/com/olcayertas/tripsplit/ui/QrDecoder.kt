package com.olcayertas.tripsplit.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads one QR code from the luminance (Y) plane of a camera frame with ZXing core. It is pure Java, works
 * offline and adds no native library to the APK. A QR code has finder patterns in three corners, so the frame
 * rotation does not matter.
 *
 * [rowStride] is the number of bytes in one row of [luma]. It can be larger than [width] (padding at the end of
 * each row). One instance is not thread-safe: use it from one analysis thread.
 */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        require(rowStride >= width && luma.size >= rowStride * (height - 1) + width) { "luma is too small" }
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
