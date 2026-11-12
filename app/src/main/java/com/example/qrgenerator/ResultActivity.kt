package com.example.qrgenerator

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
        renderReports(content.linkReports)
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

    /** One block per link: verdict, real site, findings and the letter-by-letter view. */
    private fun renderReports(reports: List<PhishingReport>) {
        if (reports.isEmpty()) return
        findViewById<View>(R.id.checksCard).visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.warningsContainer)

        for (report in reports) {
            // Show which link the block is about when the text contains several.
            if (reports.size > 1) {
                container.addView(text(report.url, 14f, R.color.text_secondary, top = 16, mono = true))
            }

            container.addView(text(getString(FindingText.verdict(report.risk)), 20f, FindingText.colorRes(report.risk), top = 8, bold = true))

            if (report.registrableDomain.isNotEmpty()) {
                container.addView(text(getString(R.string.real_site, report.registrableDomain), 15f, R.color.text_primary, top = 4))
            }

            for (finding in report.findings) {
                container.addView(findingBox(FindingText.of(this, finding), finding.severity))
            }

            if (report.chars.isNotEmpty()) renderLetters(container, report.chars, report.singleScript)
        }

        container.addView(text(getString(R.string.checks_disclaimer), 12f, R.color.text_secondary, top = 14))
    }

    /**
     * The domain printed large with every suspicious character highlighted,
     * followed by an explanation of each one (code point, alphabet, Unicode name).
     */
    private fun renderLetters(container: LinearLayout, chars: List<CharInfo>, singleScript: String?) {
        container.addView(text(getString(R.string.letters_title), 15f, R.color.text_primary, top = 16, bold = true))

        val errorColor = getColor(R.color.error)
        val highlight = (errorColor and 0x00FFFFFF) or 0x33000000
        val domain = SpannableStringBuilder()
        for (c in chars) {
            val start = domain.length
            domain.append(c.text)
            if (c.suspicious) {
                domain.setSpan(BackgroundColorSpan(highlight), start, domain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                domain.setSpan(ForegroundColorSpan(errorColor), start, domain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        container.addView(text("", 22f, R.color.text_primary, top = 6, mono = true).apply {
            text = domain
            setTextIsSelectable(true)
        })

        val suspicious = chars.filter { it.suspicious }
        if (suspicious.isEmpty()) {
            val message = if (singleScript != null) {
                getString(R.string.letters_one_script, chars.size, singleScript)
            } else {
                getString(R.string.letters_ok, chars.size)
            }
            container.addView(text(message, 14f, R.color.ok, top = 6))
            return
        }

        container.addView(text(getString(R.string.letters_bad, suspicious.size, chars.size), 14f, R.color.error, top = 6))
        for (c in suspicious.distinctBy { it.codePoint }) {
            val looks = c.looksLike?.let { "  " + getString(R.string.letter_looks_like, it) } ?: ""
            val line = "${c.text}   U+%04X   ${c.script}$looks\n${c.name}".format(c.codePoint)
            container.addView(text(line, 13f, R.color.text_primary, top = 8, mono = true))
        }
    }

    private fun findingBox(message: String, severity: Severity): TextView {
        val color = getColor(FindingText.colorRes(severity))
        return text(message, 14f, FindingText.colorRes(severity), top = 8).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor((color and 0x00FFFFFF) or 0x1F000000)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun text(
        value: String,
        sizeSp: Float,
        colorRes: Int,
        top: Int = 0,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        setTextColor(getColor(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold || mono) typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
    }

    private fun setupPrimaryAction(action: PrimaryAction?) {
        if (action == null) return
        val button = findViewById<Button>(R.id.primaryButton)
        button.visibility = View.VISIBLE
        button.setText(action.label)
        button.setOnClickListener {
            when {
                action.copyText != null -> copy(action.copyText)
                action.intent != null && action.confirm -> confirmThenLaunch(action.intent)
                action.intent != null -> launch(action.intent)
            }
        }
    }

    /** Extra step before opening a link the analyzer considers suspicious or dangerous. */
    private fun confirmThenLaunch(intent: Intent) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_open_title)
            .setMessage(R.string.confirm_open_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.confirm_open_yes) { _, _ -> launch(intent) }
            .show()
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
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
