package com.example.qrgenerator

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns arbitrary text (plain words, URLs, Wi-Fi strings, anything) into a QR code bitmap.
 * The encoder does not interpret the content: a scanner app decides whether it is a link.
 */
object QrEncoder {

    /** Default output size in pixels. Large enough to stay sharp when shared or printed. */
    private const val DEFAULT_SIZE_PX = 1024

    /** Quiet zone width in modules. The QR spec recommends 4; 2 is enough on a white card. */
    private const val MARGIN_MODULES = 2

    /**
     * Encodes [text] into a square black-on-white bitmap.
     *
     * @throws WriterException if the text does not fit into the largest QR version.
     */
    @Throws(WriterException::class)
    fun encode(text: String, sizePx: Int = DEFAULT_SIZE_PX): Bitmap {
        val hints = mapOf(
            // UTF-8 keeps Cyrillic and other non-Latin text readable after scanning.
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // Level M restores up to ~15% damage while keeping capacity reasonable.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to MARGIN_MODULES
        )

        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height

        // Fill a pixel array row by row; this is much faster than Bitmap.setPixel per pixel.
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
