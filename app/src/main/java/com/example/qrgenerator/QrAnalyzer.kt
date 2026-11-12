package com.example.qrgenerator

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.PlanarYUVLuminanceSource

/**
 * CameraX analyzer that looks for a QR code in every camera frame.
 * Only the Y (brightness) plane is used: QR decoding does not need color.
 */
class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val width = frame.width
            val height = frame.height

            // Rows can be padded (rowStride > width), and the last row may be shorter.
            // Copy into a full rowStride * height array so every row index is valid.
            val data = ByteArray(rowStride * height)
            buffer.rewind()
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))

            val source = PlanarYUVLuminanceSource(
                data, rowStride, height, 0, 0, width, height, false
            )
            QrDecoder.decode(source, thorough = false)?.let(onFound)
        }
    }
}
