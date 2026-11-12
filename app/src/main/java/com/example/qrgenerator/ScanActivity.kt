package com.example.qrgenerator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Scanner screen: live camera scanning plus reading a QR code from an image
 * (picked from the gallery or shared from another app).
 */
class ScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hintText: TextView
    private lateinit var torchButton: Button

    /** Background thread for frame analysis and image decoding. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    /** Set once a code is found, so later frames do not open the result twice. */
    private val handled = AtomicBoolean(false)

    private var camera: Camera? = null
    private var torchOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showMessage(R.string.scan_no_permission)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { decodeImage(it, startCameraOnFailure = false) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        previewView = findViewById(R.id.previewView)
        hintText = findViewById(R.id.hintText)
        torchButton = findViewById(R.id.torchButton)
        findViewById<View>(R.id.overlay).padForSystemBars()

        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.galleryButton).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        torchButton.setOnClickListener { toggleTorch() }

        // An image shared from another app is decoded first; the camera is the fallback.
        val sharedImage = if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        if (sharedImage != null && savedInstanceState == null) {
            decodeImage(sharedImage, startCameraOnFailure = true)
        } else {
            startCameraIfAllowed()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startCameraIfAllowed() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 720p is plenty for QR codes and keeps per-frame decoding fast.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // KEEP_ONLY_LATEST drops frames while the decoder is busy instead of queueing them.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(worker, QrAnalyzer { text -> onCodeFound(text) })

            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                camera = bound
                torchButton.visibility =
                    if (bound.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                // No back camera, or the camera is used by another app.
                showMessage(R.string.scan_no_camera)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton.setText(if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on)
    }

    /** Called from the analyzer thread when a frame contains a readable code. */
    private fun onCodeFound(text: String) {
        if (!handled.compareAndSet(false, true)) return
        runOnUiThread {
            previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openResult(text)
        }
    }

    private fun openResult(text: String) {
        startActivity(
            Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_CONTENT, text)
        )
        finish()
    }

    /** Loads an image in the background and looks for a QR code in it. */
    private fun decodeImage(uri: Uri, startCameraOnFailure: Boolean) {
        worker.execute {
            val text = try {
                QrDecoder.decode(loadBitmap(uri))
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (text != null && handled.compareAndSet(false, true)) {
                    openResult(text)
                } else if (text == null) {
                    Toast.makeText(this, R.string.scan_not_found, Toast.LENGTH_SHORT).show()
                    if (startCameraOnFailure) startCameraIfAllowed()
                }
            }
        }
    }

    /**
     * Decodes the image into a software bitmap, downscaled if very large.
     * Big photos are slow to scan and rarely need full resolution.
     */
    private fun loadBitmap(uri: Uri) =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            // Hardware bitmaps cannot be read pixel by pixel.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }

    private fun showMessage(resId: Int) {
        hintText.setText(resId)
        torchButton.visibility = View.GONE
    }

    private companion object {
        const val MAX_IMAGE_SIDE = 2400
    }
}
