package com.example.qrgenerator

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shows exactly what a scanned QR code contains: detected type, parsed fields,
 * link safety checks and the raw text. Nothing is opened automatically.
 */
class ResultActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        findViewById<View>(R.id.root).padForSystemBars()

        val raw = intent.getStringExtra(EXTRA_CONTENT)
        if (raw == null) {
            finish()
            return
        }

        val content = ContentDescriber.describe(this, raw)

        findViewById<TextView>(R.id.typeText).setText(content.type)
        findViewById<TextView>(R.id.rawText).text = raw
        renderFields(content.fields)
        renderWarnings(content.linkWarnings)
        setupPrimaryAction(content.action)

        findViewById<Button>(R.id.copyButton).setOnClickListener { copy(raw) }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, raw)
            startActivity(Intent.createChooser(send, null))
        }
        findViewById<Button>(R.id.scanAgainButton).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }
    }

    private fun renderFields(fields: List<Field>) {
        val container = findViewById<LinearLayout>(R.id.fieldsContainer)
        if (fields.isEmpty()) {
            findViewById<View>(R.id.detailsCard).visibility = View.GONE
            return
        }
        for (field in fields) {
            container.addView(TextView(this).apply {
                setText(field.label)
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(12), 0, 0)
            })
            container.addView(TextView(this).apply {
                text = field.value
                setTextColor(getColor(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextIsSelectable(true)
            })
        }
    }

    private fun renderWarnings(warnings: List<String>?) {
        // Null means the content is not a link, so the card stays hidden.
        if (warnings == null) return
        findViewById<View>(R.id.checksCard).visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.warningsContainer)

        if (warnings.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.result_checks_ok)
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(8), 0, 0)
            })
            return
        }

        for (warning in warnings) {
            container.addView(TextView(this).apply {
                text = warning
                setTextColor(getColor(R.color.error))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(R.drawable.bg_warning)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
        }
    }

    private fun setupPrimaryAction(action: PrimaryAction?) {
        if (action == null) return
        val button = findViewById<Button>(R.id.primaryButton)
        button.visibility = View.VISIBLE
        button.setText(action.label)
        button.setOnClickListener {
            when {
                action.copyText != null -> copy(action.copyText)
                action.intent != null -> try {
                    startActivity(action.intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CONTENT = "com.example.qrgenerator.CONTENT"
    }
}
