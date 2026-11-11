package com.example.qrgenerator

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Saves QR bitmaps to the gallery and prepares them for sharing.
 * Uses MediaStore (Android 10+), so no storage permission is required.
 */
object ImageExporter {

    private const val GALLERY_FOLDER = "QRGenerator"
    private const val SHARE_DIR = "shared"
    private const val SHARE_FILE = "qr_code.png"

    /**
     * Writes [bitmap] as a PNG into Pictures/QRGenerator.
     *
     * @return true on success, false if the system refused the write.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "qr_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER"
            )
            // Hide the entry from other apps until the file is fully written.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IOException("Output stream is null")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: IOException) {
            // Remove the half-written entry so the gallery does not show a broken image.
            resolver.delete(uri, null, null)
            false
        }
    }

    /**
     * Builds a share intent with the QR image and the original text attached.
     * The image is written to the app cache and exposed through FileProvider.
     *
     * @return the chooser intent, or null if the image could not be written.
     */
    fun buildShareIntent(context: Context, bitmap: Bitmap, text: String, title: String): Intent? {
        val uri = writeToCache(context, bitmap) ?: return null

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    private fun writeToCache(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val file = File(dir, SHARE_FILE)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: IOException) {
            null
        }
    }
}
