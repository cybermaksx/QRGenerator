package com.example.qrgenerator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.annotation.StringRes
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.EmailAddressParsedResult
import com.google.zxing.client.result.GeoParsedResult
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.SMSParsedResult
import com.google.zxing.client.result.TelParsedResult
import com.google.zxing.client.result.URIParsedResult
import com.google.zxing.client.result.WifiParsedResult
import java.text.DateFormat
import java.util.Date

/** One labeled value shown on the result screen. */
data class Field(@StringRes val label: Int, val value: String)

/**
 * Main action for the content type. Either launches [intent] or copies [copyText].
 */
data class PrimaryAction(
    @StringRes val label: Int,
    val intent: Intent? = null,
    val copyText: String? = null,
    /** Ask "open anyway?" first, used for links that look like phishing. */
    val confirm: Boolean = false
)

/**
 * Human readable description of a QR payload.
 *
 * @property linkReports phishing analysis for every link found in the content.
 */
data class DescribedContent(
    @StringRes val type: Int,
    val fields: List<Field>,
    val linkReports: List<PhishingReport>,
    val action: PrimaryAction?
)

/**
 * Detects what a QR code contains (link, Wi-Fi, contact, phone, ...) and splits
 * it into readable fields. Parsing is done by the ZXing "client.result" parsers,
 * which understand the common QR payload formats (WIFI:, MECARD:, BEGIN:VCARD, ...).
 */
object ContentDescriber {

    fun describe(context: Context, raw: String): DescribedContent {
        val parsed = ResultParser.parseResult(Result(raw, null, null, BarcodeFormat.QR_CODE))
        return when (parsed) {
            is URIParsedResult -> describeLink(raw)
            is WifiParsedResult -> describeWifi(context, parsed)
            is AddressBookParsedResult -> describeContact(parsed)
            is TelParsedResult -> describePhone(parsed)
            is SMSParsedResult -> describeSms(parsed)
            is EmailAddressParsedResult -> describeEmail(parsed)
            is GeoParsedResult -> describeGeo(parsed)
            is CalendarParsedResult -> describeEvent(parsed)
            else -> describeText(context, raw)
        }
    }

    private fun describeLink(raw: String): DescribedContent {
        // Analyze the original text, not ZXing's cleaned up URI, so no trick is hidden.
        val link = raw.trim()
        val report = PhishingAnalyzer.analyze(link)
        val fields = mutableListOf(Field(R.string.field_address, link))

        if (report.asciiHost.isNotEmpty()) {
            fields += Field(R.string.field_domain, report.asciiHost)
            if (report.host != report.asciiHost) fields += Field(R.string.field_domain_unicode, report.host)
        }

        // Only web links get an "Open" button; other schemes can trigger app actions.
        val action = if (report.isWeb && report.host.isNotEmpty()) {
            val target = if (report.scheme == null) "https://$link" else link
            PrimaryAction(
                R.string.action_open_link,
                Intent(Intent.ACTION_VIEW, Uri.parse(target)),
                confirm = (report.risk ?: Severity.LOW) >= Severity.MEDIUM
            )
        } else {
            null
        }
        return DescribedContent(R.string.type_link, fields, listOf(report), action)
    }

    private fun describeWifi(context: Context, r: WifiParsedResult): DescribedContent {
        val fields = mutableListOf(Field(R.string.field_network, r.ssid.orEmpty()))
        val password = r.password.orEmpty()
        fields += Field(
            R.string.field_password,
            password.ifEmpty { context.getString(R.string.value_none) }
        )
        r.networkEncryption?.takeIf { it.isNotEmpty() }?.let {
            fields += Field(R.string.field_security, it)
        }
        if (r.isHidden) fields += Field(R.string.field_hidden, context.getString(R.string.value_yes))

        val action = if (password.isNotEmpty()) {
            PrimaryAction(R.string.action_copy_password, copyText = password)
        } else {
            null
        }
        return DescribedContent(R.string.type_wifi, fields, emptyList(), action)
    }

    private fun describeContact(r: AddressBookParsedResult): DescribedContent {
        val fields = mutableListOf<Field>()
        r.names?.forEach { fields += Field(R.string.field_name, it) }
        r.phoneNumbers?.forEach { fields += Field(R.string.field_phone, it) }
        r.emails?.forEach { fields += Field(R.string.field_email, it) }
        r.org?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_company, it) }
        r.addresses?.forEach { fields += Field(R.string.field_postal, it) }
        r.getURLs()?.forEach { fields += Field(R.string.field_website, it) }

        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            r.names?.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            r.phoneNumbers?.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            r.emails?.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            r.org?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        }
        val reports = r.getURLs()?.map { PhishingAnalyzer.analyze(it) }.orEmpty()
        return DescribedContent(
            R.string.type_contact, fields, reports,
            PrimaryAction(R.string.action_add_contact, intent)
        )
    }

    private fun describePhone(r: TelParsedResult): DescribedContent {
        // ACTION_DIAL only opens the dialer; the user still has to press call.
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", r.number, null))
        return DescribedContent(
            R.string.type_phone,
            listOf(Field(R.string.field_number, r.number)),
            emptyList(),
            PrimaryAction(R.string.action_call, intent)
        )
    }

    private fun describeSms(r: SMSParsedResult): DescribedContent {
        val numbers = r.numbers?.toList().orEmpty()
        val fields = numbers.map { Field(R.string.field_number, it) }.toMutableList()
        r.body?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_message, it) }

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("smsto", numbers.joinToString(","), null)
        ).putExtra("sms_body", r.body.orEmpty())
        return DescribedContent(
            R.string.type_sms, fields, emptyList(),
            PrimaryAction(R.string.action_send_sms, intent)
        )
    }

    private fun describeEmail(r: EmailAddressParsedResult): DescribedContent {
        val tos = r.tos?.toList().orEmpty()
        val fields = tos.map { Field(R.string.field_to, it) }.toMutableList()
        r.subject?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_subject, it) }
        r.body?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_message, it) }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, tos.toTypedArray())
            r.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            r.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        return DescribedContent(
            R.string.type_email, fields, emptyList(),
            PrimaryAction(R.string.action_send_email, intent)
        )
    }

    private fun describeGeo(r: GeoParsedResult): DescribedContent {
        val lat = r.latitude
        val lon = r.longitude
        val fields = listOf(
            Field(R.string.field_latitude, lat.toString()),
            Field(R.string.field_longitude, lon.toString())
        )
        // The "q" parameter makes most map apps drop a pin at the point.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))
        return DescribedContent(
            R.string.type_geo, fields, emptyList(),
            PrimaryAction(R.string.action_open_map, intent)
        )
    }

    private fun describeEvent(r: CalendarParsedResult): DescribedContent {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val fields = mutableListOf<Field>()
        r.summary?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_title, it) }
        fields += Field(R.string.field_start, format.format(Date(r.startTimestamp)))
        // ZXing uses a negative value when the event has no end time.
        if (r.endTimestamp >= 0) {
            fields += Field(R.string.field_end, format.format(Date(r.endTimestamp)))
        }
        r.location?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_location, it) }
        r.description?.takeIf { it.isNotEmpty() }?.let { fields += Field(R.string.field_message, it) }

        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, r.summary)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, r.startTimestamp)
            if (r.endTimestamp >= 0) putExtra(CalendarContract.EXTRA_EVENT_END_TIME, r.endTimestamp)
            putExtra(CalendarContract.Events.EVENT_LOCATION, r.location)
            putExtra(CalendarContract.Events.DESCRIPTION, r.description)
        }
        return DescribedContent(
            R.string.type_event, fields, emptyList(),
            PrimaryAction(R.string.action_add_event, intent)
        )
    }

    private fun describeText(context: Context, raw: String): DescribedContent {
        // Fallback for plain text and rare types (ISBN, product codes, ...).
        val fields = listOf(
            Field(R.string.field_length, context.getString(R.string.value_chars, raw.length))
        )
        // Links inside plain text are checked too, e.g. "Win a prize: https://...".
        val reports = PhishingAnalyzer.extractLinks(raw).map { PhishingAnalyzer.analyze(it) }
        return DescribedContent(R.string.type_text, fields, reports, null)
    }
}
