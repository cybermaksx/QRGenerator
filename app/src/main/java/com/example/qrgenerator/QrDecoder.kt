package com.example.qrgenerator

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds and decodes a QR code in grayscale image data.
 * Two modes: fast for live camera frames, thorough for still images from the gallery.
 */
object QrDecoder {

    private val fastHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
    )

    private val thoroughHints: Map<DecodeHintType, Any> = fastHints + mapOf(
        DecodeHintType.TRY_HARDER to true
    )

    /**
     * Returns the decoded text, or null if no readable QR code was found.
     *
     * Both the normal and the inverted image are tried, so light-on-dark codes
     * work too. Thorough mode adds a second binarizer that handles evenly lit
     * photos better than the default one.
     */
    fun decode(source: LuminanceSource, thorough: Boolean): String? {
        val hints = if (thorough) thoroughHints else fastHints
        // QRCodeReader keeps internal state, so a new instance per call keeps this thread safe.
        val reader = QRCodeReader()

        for (candidate in listOf(source, source.invert())) {
            val binarizers = if (thorough) {
                listOf(HybridBinarizer(candidate), GlobalHistogramBinarizer(candidate))
            } else {
                listOf(HybridBinarizer(candidate))
            }
            for (binarizer in binarizers) {
                try {
                    return reader.decode(BinaryBitmap(binarizer), hints).text
                } catch (e: ReaderException) {
                    // Not found or damaged with this setup: try the next one.
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    /** Decodes a QR code from a bitmap, for example a screenshot or a photo. */
    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decode(RGBLuminanceSource(width, height, pixels), thorough = true)
    }
}
