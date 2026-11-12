package com.example.qrgenerator

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.WriterException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main and only screen. Every change in the input field regenerates the QR code
 * after a short pause, so typing stays smooth even for long texts.
 */
class MainActivity : Activity() {

    private lateinit var inputText: EditText
    private lateinit var statusText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var placeholderText: TextView
    private lateinit var shareButton: Button
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button

    /** Last successfully generated QR code, or null when there is nothing to show. */
    private var currentBitmap: Bitmap? = null

    /** Text that [currentBitmap] encodes; used as the share caption. */
    private var currentText: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Encoding runs off the main thread; a single thread keeps results in order. */
    private val encoder: ExecutorService = Executors.newSingleThreadExecutor()

    /** Increases with each request so stale results from slower jobs are dropped. */
    private var requestId = 0

    private val regenerateRunnable = Runnable { regenerate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        statusText = findViewById(R.id.statusText)
        qrImage = findViewById(R.id.qrImage)
        placeholderText = findViewById(R.id.placeholderText)
        shareButton = findViewById(R.id.shareButton)
        saveButton = findViewById(R.id.saveButton)
        clearButton = findViewById(R.id.clearButton)

        applySystemBarInsets()

        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Debounce: wait until the user stops typing for a moment.
                mainHandler.removeCallbacks(regenerateRunnable)
                mainHandler.postDelayed(regenerateRunnable, DEBOUNCE_MS)
            }
        })

        shareButton.setOnClickListener { shareQr() }
        saveButton.setOnClickListener { saveQr() }
        clearButton.setOnClickListener { inputText.text.clear() }
        findViewById<Button>(R.id.scanButton).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        // Text shared from another app (for example a browser link) fills the input.
        if (savedInstanceState == null) handleIncomingIntent(intent)

        // EditText restores its own text after rotation; rebuild the image for it.
        regenerate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(regenerateRunnable)
        encoder.shutdownNow()
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                inputText.setText(it)
                inputText.setSelection(it.length)
            }
        }
    }

    /**
     * Since targetSdk 35 the app draws behind the status and navigation bars,
     * so the root view is padded by the bar and keyboard sizes.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Starts encoding the current input in the background. */
    private fun regenerate() {
        val text = inputText.text.toString()
        val id = ++requestId

        if (text.isEmpty()) {
            showEmpty()
            return
        }

        encoder.execute {
            val result: Bitmap? = try {
                QrEncoder.encode(text)
            } catch (e: WriterException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }

            mainHandler.post {
                // Ignore results for text that has already changed or a closed screen.
                if (id != requestId || isDestroyed) return@post
                if (result != null) showQr(result, text) else showTooLong(text)
            }
        }
    }

    private fun showQr(bitmap: Bitmap, text: String) {
        currentBitmap = bitmap
        currentText = text
        qrImage.setImageBitmap(bitmap)
        qrImage.visibility = View.VISIBLE
        placeholderText.visibility = View.GONE
        statusText.setTextColor(getColor(R.color.text_secondary))
        statusText.text = getString(R.string.char_counter, text.length)
        setActionsEnabled(true)
    }

    private fun showTooLong(text: String) {
        currentBitmap = null
        qrImage.setImageDrawable(null)
        qrImage.visibility = View.GONE
        placeholderText.visibility = View.VISIBLE
        statusText.setTextColor(getColor(R.color.error))
        statusText.text = getString(R.string.error_too_long) + " (" + text.length + ")"
        setActionsEnabled(false)
    }

    private fun showEmpty() {
        currentBitmap = null
        currentText = ""
        qrImage.setImageDrawable(null)
        qrImage.visibility = View.GONE
        placeholderText.visibility = View.VISIBLE
        statusText.text = ""
        setActionsEnabled(false)
    }

    private fun setActionsEnabled(enabled: Boolean) {
        shareButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        shareButton.alpha = alpha
        saveButton.alpha = alpha
    }

    private fun shareQr() {
        val bitmap = currentBitmap ?: return
        val chooser = ImageExporter.buildShareIntent(
            this, bitmap, currentText, getString(R.string.share_title)
        )
        if (chooser != null) {
            startActivity(chooser)
        } else {
            toast(R.string.share_failed)
        }
    }

    private fun saveQr() {
        val bitmap = currentBitmap ?: return
        // PNG compression of a 1024px image is quick, but keep it off the UI thread anyway.
        encoder.execute {
            val ok = ImageExporter.saveToGallery(applicationContext, bitmap)
            mainHandler.post {
                if (!isDestroyed) toast(if (ok) R.string.saved_to_gallery else R.string.save_failed)
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Pause after the last keystroke before the QR code is rebuilt. */
        const val DEBOUNCE_MS = 250L
    }
}
