package com.example.qrgenerator

import android.content.Context
import android.net.Uri
import java.net.IDN

/**
 * Basic offline checks for links found in QR codes ("quishing" protection).
 * These are heuristics: they flag common tricks but cannot prove a link is safe.
 */
object UrlInspector {

    /** Popular URL shorteners that hide the real destination. */
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy",
        "ow.ly", "buff.ly", "tiny.cc", "shorturl.at", "clck.ru", "s.id", "v.gd",
        "t.ly", "rebrand.ly", "qrco.de", "short.io"
    )

    private val IPV4 = Regex("^[0-9.]+$")

    /** Host in pure ASCII form (punycode for international names). */
    fun asciiHost(host: String): String = try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
    } catch (e: IllegalArgumentException) {
        host
    }

    /** Host in Unicode form, as a browser address bar would usually show it. */
    fun unicodeHost(host: String): String = try {
        IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED)
    } catch (e: IllegalArgumentException) {
        host
    }

    /** True if the link can be safely handed to a browser (http or https only). */
    fun isWebLink(link: String): Boolean {
        val scheme = Uri.parse(link).scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Returns human readable warnings; an empty list means nothing suspicious was found. */
    fun inspect(context: Context, link: String): List<String> {
        val uri = Uri.parse(link)
        val scheme = uri.scheme?.lowercase()
            ?: return emptyList()

        if (scheme != "http" && scheme != "https") {
            return listOf(context.getString(R.string.warn_scheme, scheme))
        }

        val host = uri.host ?: return emptyList()
        val ascii = asciiHost(host).lowercase()
        val warnings = mutableListOf<String>()

        if (scheme == "http") {
            warnings += context.getString(R.string.warn_http)
        }

        // Non-ASCII or punycode hosts can look identical to real brands (homograph attack).
        if (host.any { it.code > 127 } || ascii.split('.').any { it.startsWith("xn--") }) {
            warnings += context.getString(R.string.warn_idn, ascii)
        }

        // Plain or decimal IPv4 addresses, and IPv6 literals.
        if (IPV4.matches(host) || host.contains(':')) {
            warnings += context.getString(R.string.warn_ip)
        }

        // "https://bank.com@evil.example" opens evil.example, not bank.com.
        if (!uri.encodedUserInfo.isNullOrEmpty()) {
            warnings += context.getString(R.string.warn_userinfo, ascii)
        }

        val bareHost = ascii.removePrefix("www.")
        if (bareHost in SHORTENERS) {
            warnings += context.getString(R.string.warn_shortener, bareHost)
        }

        return warnings
    }
}
