package com.example.qrgenerator

import java.net.IDN
import java.text.Normalizer
import java.util.Locale

/** How dangerous a single finding is. Ordered from least to most severe. */
enum class Severity { LOW, MEDIUM, HIGH }

/** One problem found in a link. Each subtype carries the data needed for its message. */
sealed class Finding(val severity: Severity) {
    data class UnsafeScheme(val scheme: String) : Finding(Severity.HIGH)
    data class UnknownScheme(val scheme: String) : Finding(Severity.MEDIUM)
    data object InvisibleChars : Finding(Severity.HIGH)
    data object Unparseable : Finding(Severity.HIGH)
    data class MixedScripts(val label: String, val scripts: List<String>) : Finding(Severity.HIGH)
    data class AllLookalikes(val label: String, val readsAs: String) : Finding(Severity.HIGH)
    data class ImitatesBrand(val brandDomain: String) : Finding(Severity.HIGH)
    data class Typosquat(val label: String, val brandDomain: String) : Finding(Severity.HIGH)
    data class BrandInSubdomain(val brand: String, val realDomain: String) : Finding(Severity.HIGH)
    data class UserInfo(val ignoredPart: String, val realHost: String) : Finding(Severity.HIGH)
    data class ObfuscatedIp(val host: String) : Finding(Severity.HIGH)
    data class BrandOnWrongDomain(
        val brand: String,
        val realDomain: String,
        val officialDomains: List<String>
    ) : Finding(Severity.MEDIUM)
    data object IpAddress : Finding(Severity.MEDIUM)
    data object NoEncryption : Finding(Severity.MEDIUM)
    data class Shortener(val domain: String) : Finding(Severity.MEDIUM)
    data object NonLatinDomain : Finding(Severity.LOW)
    data class FreeHosting(val platform: String) : Finding(Severity.LOW)
    data class SuspiciousTld(val tld: String) : Finding(Severity.LOW)
    data class UnusualPort(val port: String) : Finding(Severity.LOW)
    data class ManySubdomains(val levels: Int) : Finding(Severity.LOW)
    data class LoginWords(val words: List<String>) : Finding(Severity.LOW)
}

/** One character of the domain, explained for the letter-by-letter view. */
data class CharInfo(
    val text: String,
    val codePoint: Int,
    val name: String,
    val script: String,
    /** Latin character this one imitates, if any. */
    val looksLike: String?,
    /** True for anything that is not a plain a-z, 0-9, "-" or "." (or a digit used as a letter). */
    val suspicious: Boolean
)

/** Full result of analyzing one link. */
data class PhishingReport(
    val url: String,
    /** Lowercase scheme, or null when the link was written without one ("www.site.com"). */
    val scheme: String?,
    /** Host as Unicode, after the same normalization a browser applies. Empty if none. */
    val host: String,
    /** Host as ASCII (punycode). This is what is actually resolved in DNS. */
    val asciiHost: String,
    /** The part of the host that identifies who owns the site, for example "evil.ru". */
    val registrableDomain: String,
    val findings: List<Finding>,
    val chars: List<CharInfo>,
    /** Set when the whole host, zone included, uses one non-Latin alphabet (e.g. "Cyrillic"). */
    val singleScript: String? = null
) {
    /** Worst severity among findings, or null when nothing was found. */
    val risk: Severity? get() = findings.maxOfOrNull { it.severity }

    val isWeb: Boolean get() = scheme == null || scheme == "http" || scheme == "https"
}

/**
 * Offline phishing analyzer for links found in QR codes.
 *
 * It looks at the domain character by character the way a browser would see it:
 * Unicode normalization, punycode, look-alike letters from other alphabets,
 * digits posing as letters, brand imitation, typos, subdomain and "@" tricks.
 *
 * Pure Kotlin/Java with no Android dependencies, so it is covered by JVM unit tests.
 * It cannot know whether a site is malicious; it detects the disguise techniques
 * that phishing links rely on.
 */
object PhishingAnalyzer {

    // ---------------------------------------------------------------- reference data

    private class Brand(
        val name: String,
        val domains: List<String>,
        /** Brand owns its name in most country zones (google.de, amazon.co.uk, ...). */
        val anyTld: Boolean = false
    )

    private val BRANDS = listOf(
        Brand("google", listOf("google.com"), anyTld = true),
        Brand("youtube", listOf("youtube.com", "youtu.be")),
        Brand("gmail", listOf("gmail.com")),
        Brand("apple", listOf("apple.com")),
        Brand("icloud", listOf("icloud.com")),
        Brand("microsoft", listOf("microsoft.com")),
        Brand("outlook", listOf("outlook.com")),
        Brand("paypal", listOf("paypal.com", "paypal.me")),
        Brand("amazon", listOf("amazon.com"), anyTld = true),
        Brand("facebook", listOf("facebook.com", "fb.com")),
        Brand("instagram", listOf("instagram.com")),
        Brand("whatsapp", listOf("whatsapp.com", "whatsapp.net", "wa.me")),
        Brand("telegram", listOf("telegram.org", "telegram.me", "t.me", "telegra.ph")),
        Brand("netflix", listOf("netflix.com")),
        Brand("twitter", listOf("twitter.com", "x.com")),
        Brand("linkedin", listOf("linkedin.com")),
        Brand("github", listOf("github.com")),
        Brand("dropbox", listOf("dropbox.com")),
        Brand("discord", listOf("discord.com", "discord.gg", "discordapp.com")),
        Brand("steamcommunity", listOf("steamcommunity.com")),
        Brand("steampowered", listOf("steampowered.com")),
        Brand("binance", listOf("binance.com")),
        Brand("coinbase", listOf("coinbase.com")),
        Brand("metamask", listOf("metamask.io")),
        Brand("bybit", listOf("bybit.com")),
        Brand("yandex", listOf("yandex.ru", "ya.ru"), anyTld = true),
        Brand("vk", listOf("vk.com", "vk.ru", "vk.me", "vk.cc", "vkontakte.ru")),
        Brand("vkontakte", listOf("vkontakte.ru")),
        Brand("sberbank", listOf("sberbank.ru", "sberbank.com", "sber.ru")),
        Brand("sber", listOf("sber.ru", "sberbank.ru")),
        Brand("tinkoff", listOf("tinkoff.ru")),
        Brand("tbank", listOf("tbank.ru")),
        Brand("alfabank", listOf("alfabank.ru")),
        Brand("vtb", listOf("vtb.ru")),
        Brand("gosuslugi", listOf("gosuslugi.ru")),
        Brand("ozon", listOf("ozon.ru", "ozon.com")),
        Brand("wildberries", listOf("wildberries.ru")),
        Brand("avito", listOf("avito.ru")),
        Brand("kaspi", listOf("kaspi.kz"))
    )

    private val OFFICIAL_DOMAINS: Set<String> = BRANDS.flatMap { it.domains }.toSet()

    /** Two-level public suffixes: "bbc.co.uk" belongs to "bbc", not to "co". */
    private val MULTI_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au",
        "co.jp", "ne.jp", "or.jp", "com.br", "com.tr", "com.cn", "com.hk", "com.tw",
        "com.mx", "com.ar", "co.in", "co.kr", "co.za", "co.nz", "com.sg", "com.ua",
        "com.ru", "net.ru", "org.ru", "msk.ru", "spb.ru", "com.kz", "co.il", "com.pl"
    )

    /** Platforms where anyone gets a free subdomain; each subdomain is a separate site. */
    private val HOSTING_PLATFORMS = setOf(
        "github.io", "gitlab.io", "web.app", "firebaseapp.com", "netlify.app",
        "vercel.app", "pages.dev", "workers.dev", "herokuapp.com", "appspot.com",
        "azurewebsites.net", "blogspot.com", "glitch.me", "ngrok-free.app", "ngrok.io",
        "repl.co", "wixsite.com", "weebly.com", "square.site", "000webhostapp.com", "tilda.ws"
    )

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "ow.ly",
        "buff.ly", "tiny.cc", "shorturl.at", "clck.ru", "s.id", "v.gd", "t.ly",
        "rebrand.ly", "qrco.de", "short.io", "u.to", "vk.cc"
    )

    private val SUSPICIOUS_TLDS = setOf(
        "zip", "mov", "xyz", "top", "click", "link", "tk", "ml", "ga", "cf", "gq",
        "work", "support", "rest", "cam", "icu", "buzz", "lol", "fit", "quest", "sbs",
        "cfd", "monster"
    )

    /** Cyrillic country zones, where all-Cyrillic names are normal. */
    private val CYRILLIC_TLDS = setOf("рф", "рус", "бел", "укр", "қаз", "срб", "мкд", "мон", "москва")

    /** Schemes that can run code or open arbitrary app components. */
    private val DANGEROUS_SCHEMES = setOf(
        "javascript", "data", "vbscript", "file", "intent", "content", "jar", "blob"
    )

    /** Ordinary words that happen to be one letter away from a brand ("finance" vs "binance"). */
    private val NOT_TYPOSQUATS = setOf("finance", "amazing", "outlooks", "discogs")

    private val LOGIN_WORDS = listOf(
        "login", "signin", "sign-in", "verify", "account", "secure", "update", "confirm",
        "password", "wallet", "unlock", "recover", "auth", "bank"
    )

    /**
     * Characters that are rendered almost identically to a Latin letter.
     * Accented letters are handled separately by stripping diacritics.
     */
    private val CONFUSABLES: Map<Int, String> = mapOf(
        // Cyrillic
        0x0430 to "a", 0x0435 to "e", 0x043E to "o", 0x0440 to "p", 0x0441 to "c",
        0x0443 to "y", 0x0445 to "x", 0x043A to "k", 0x044C to "b", 0x043F to "n",
        0x0455 to "s", 0x0456 to "i", 0x0458 to "j", 0x0501 to "d", 0x04BB to "h",
        0x04CF to "l", 0x051B to "q", 0x051D to "w", 0x04AF to "y",
        // Greek
        0x03B1 to "a", 0x03BF to "o", 0x03C1 to "p", 0x03BD to "v", 0x03B9 to "i",
        0x03BA to "k", 0x03C4 to "t", 0x03C5 to "u", 0x03C7 to "x", 0x03B3 to "y",
        0x03C9 to "w", 0x03B5 to "e", 0x03B7 to "n",
        // Latin extensions and IPA
        0x0131 to "i", 0x0251 to "a", 0x0261 to "g", 0x0269 to "i", 0x0237 to "j",
        0x0142 to "l", 0x00F8 to "o", 0x0111 to "d", 0x0127 to "h",
        // Armenian
        0x0585 to "o", 0x057D to "u", 0x0570 to "h", 0x0566 to "q", 0x0581 to "g", 0x0578 to "n"
    )

    /** Digits commonly used in place of letters ("paypa1", "g00gle"). */
    private val DIGIT_LOOKALIKES = mapOf('0' to "o", '1' to "l", '3' to "e", '4' to "a", '5' to "s")

    /** Scripts that contain Latin look-alikes; mixing two of them in one label is a red flag. */
    private val CONFUSABLE_SCRIPTS = setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.GREEK, Character.UnicodeScript.ARMENIAN,
        Character.UnicodeScript.CHEROKEE, Character.UnicodeScript.COPTIC
    )

    /** Zero-width, soft hyphen and bidirectional control characters. */
    private fun isInvisible(cp: Int): Boolean =
        cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2064 ||
            cp in 0x2066..0x2069 || cp == 0xFEFF || cp == 0x00AD || cp == 0x180E

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val NUMERIC_PART = Regex("^(0x[0-9a-f]*|\\d+)$")
    private val LINK_IN_TEXT = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'«»]+")

    // ---------------------------------------------------------------- public API

    /** Finds web links inside free text, plus a dangerous-scheme payload if the whole text is one. */
    fun extractLinks(text: String): List<String> {
        val links = LINK_IN_TEXT.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
            .toMutableList()
        val trimmed = text.trim()
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme in DANGEROUS_SCHEMES && trimmed !in links) links.add(0, trimmed)
        return links.distinct()
    }

    fun analyze(rawUrl: String): PhishingReport {
        val url = rawUrl.trim()
        val findings = mutableListOf<Finding>()

        if (url.codePoints().anyMatch { isInvisible(it) }) findings += Finding.InvisibleChars
        val cleaned = buildString {
            url.codePoints().filter { !isInvisible(it) }.forEach { appendCodePoint(it) }
        }

        // Scheme. "site.com:8080" must not be taken as scheme "site.com".
        var scheme: String? = null
        var rest = cleaned
        SCHEME.find(cleaned)?.let { match ->
            val candidate = match.groupValues[1].lowercase()
            val after = cleaned.substring(match.range.last + 1)
            if (after.startsWith("//") || !candidate.contains('.')) {
                scheme = candidate
                rest = after
            }
        }

        val s = scheme
        if (s != null && s != "http" && s != "https") {
            findings += if (s in DANGEROUS_SCHEMES) Finding.UnsafeScheme(s) else Finding.UnknownScheme(s)
            return PhishingReport(url, s, "", "", "", findings, emptyList())
        }
        if (s == "http") findings += Finding.NoEncryption

        // Authority ends at the first "/", "?", "#" or "\" (browsers treat "\" as "/").
        val noSlashes = rest.trimStart('/', '\\')
        val authorityEnd = noSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        val authority = if (authorityEnd >= 0) noSlashes.substring(0, authorityEnd) else noSlashes
        val pathAndQuery = if (authorityEnd >= 0) noSlashes.substring(authorityEnd) else ""

        // "https://paypal.com@evil.ru" opens evil.ru: everything before the last "@" is login data.
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority

        val (rawHost, port) = splitHostPort(hostPort)
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) {
            findings += Finding.Unparseable
            return PhishingReport(url, s, "", "", "", findings, emptyList())
        }

        val ascii = toAscii(host)
        if (ascii == null) {
            findings += Finding.Unparseable
            return PhishingReport(url, s, host, host, host, findings, describeChars(host, false))
        }
        val unicode = toUnicode(ascii)

        if (userInfo != null) findings += Finding.UserInfo(userInfo, unicode)
        if (port != null && port != "80" && port != "443") findings += Finding.UnusualPort(port)

        // IP addresses: no domain name to check.
        if (unicode.startsWith("[") || IPV4.matches(unicode)) {
            findings += Finding.IpAddress
            return PhishingReport(url, s, unicode, ascii, unicode, findings, describeChars(unicode, false))
        }
        val labelsAscii = ascii.split('.')
        if (labelsAscii.all { NUMERIC_PART.matches(it) }) {
            // Decimal, hex or octal forms like 3232235777 or 0x7f.1 still open an IP.
            findings += Finding.ObfuscatedIp(unicode)
            return PhishingReport(url, s, unicode, ascii, unicode, findings, describeChars(unicode, false))
        }

        val labels = unicode.split('.')
        val (regIndex, platform) = registrableIndex(labelsAscii)
        val registrable = labels.drop(regIndex).joinToString(".")
        val registrableAscii = labelsAscii.drop(regIndex).joinToString(".")
        val regLabel = labels[regIndex]
        val subLabels = labels.take(regIndex)
        val tld = labels.last()

        if (platform != null) findings += Finding.FreeHosting(platform)
        if (registrableAscii in SHORTENERS) findings += Finding.Shortener(registrableAscii)
        if (tld in SUSPICIOUS_TLDS) findings += Finding.SuspiciousTld(tld)
        if (labels.size >= 5) findings += Finding.ManySubdomains(labels.size)

        // Alphabet checks per label.
        var nonLatin = false
        for (label in labels) {
            if (label.all { it.code < 128 }) continue
            nonLatin = true
            val scripts = scriptsOf(label).filter { it in CONFUSABLE_SCRIPTS }
            val skel = skeleton(label)
            if (scripts.size > 1) {
                findings += Finding.MixedScripts(label, scripts.map(::scriptName))
            } else if (tld !in CYRILLIC_TLDS && skel != label && skel.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
                // Every letter is a look-alike: "аррӏе" in Cyrillic reads as "apple".
                findings += Finding.AllLookalikes(label, skel)
            }
        }

        // Brand checks.
        var brandImitation = false
        val official = registrableAscii in OFFICIAL_DOMAINS ||
            BRANDS.any { it.anyTld && regLabel == it.name && platform == null }
        if (!official) {
            val folded = fold(skeleton(regLabel))
            val parts = regLabel.split('-').map { fold(skeleton(it)) }
            for (brand in BRANDS) {
                val target = fold(brand.name)
                val main = brand.domains.first()
                val finding = when {
                    regLabel == brand.name ->
                        Finding.BrandOnWrongDomain(brand.name, registrable, brand.domains)
                    folded == target ->
                        Finding.ImitatesBrand(main)
                    brand.name.length >= 6 && regLabel !in NOT_TYPOSQUATS &&
                        osaDistance(folded, target) <= (if (brand.name.length >= 9) 2 else 1) ->
                        Finding.Typosquat(regLabel, main)
                    brand.name.length >= 4 && parts.size > 1 && target in parts ->
                        Finding.BrandOnWrongDomain(brand.name, registrable, brand.domains)
                    brand.name.length >= 6 && folded.startsWith(target) ->
                        Finding.BrandOnWrongDomain(brand.name, registrable, brand.domains)
                    else -> null
                }
                if (finding != null) {
                    findings += finding
                    brandImitation = finding !is Finding.BrandOnWrongDomain
                    break
                }
            }

            // "paypal.com.secure-login.ru": the brand is only decoration in front of the real domain.
            val subHit = BRANDS.firstOrNull { brand ->
                val target = fold(brand.name)
                subLabels.any { sub ->
                    val parts = sub.split('-').map { fold(skeleton(it)) }
                    fold(skeleton(sub)) == target || (brand.name.length >= 4 && target in parts)
                }
            }
            if (subHit != null) findings += Finding.BrandInSubdomain(subHit.name, registrable)

            val path = pathAndQuery.lowercase(Locale.ROOT)
            val words = LOGIN_WORDS.filter { path.contains(it) || (subLabels + regLabel).any { l -> l.contains(it) } }
            if (words.isNotEmpty()) findings += Finding.LoginWords(words.take(3))
        }

        // "госуслуги.рф": one alphabet everywhere, including the zone, is a normal national domain.
        val hostScripts = scriptsOf(unicode)
        val nativeScript = hostScripts.singleOrNull()?.takeIf { it != Character.UnicodeScript.LATIN }

        if (nonLatin && findings.none { it.severity == Severity.HIGH }) findings += Finding.NonLatinDomain

        return PhishingReport(
            url = url,
            scheme = s,
            host = unicode,
            asciiHost = ascii,
            registrableDomain = registrable,
            findings = findings.sortedByDescending { it.severity },
            chars = describeChars(unicode, brandImitation, nativeScript),
            singleScript = nativeScript?.let(::scriptName)
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun splitHostPort(hostPort: String): Pair<String, String?> {
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return hostPort to null
            val port = hostPort.substring(end + 1).removePrefix(":").ifEmpty { null }
            return hostPort.substring(0, end + 1) to port
        }
        val colon = hostPort.lastIndexOf(':')
        return if (colon >= 0) hostPort.substring(0, colon) to hostPort.substring(colon + 1).ifEmpty { null }
        else hostPort to null
    }

    /**
     * Applies what browsers do to a host before resolving it: percent-decoding,
     * NFKC (fullwidth and styled letters become plain), Unicode dots become ".",
     * lowercase, trailing dot removed.
     */
    private fun normalizeHost(raw: String): String {
        var h = percentDecode(raw)
        h = Normalizer.normalize(h, Normalizer.Form.NFKC)
        h = h.replace('。', '.').replace('．', '.').replace('｡', '.')
        return h.lowercase(Locale.ROOT).trimEnd('.')
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val isEscape = c == '%' && i + 2 < s.length &&
                s.substring(i + 1, i + 3).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (isEscape) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    private fun toAscii(host: String): String? = try {
        if (host.startsWith("[")) host else IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun toUnicode(ascii: String): String = try {
        if (ascii.startsWith("[")) ascii else IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED)
    } catch (e: IllegalArgumentException) {
        ascii
    }

    /**
     * Index of the label that names the site owner, and the hosting platform if one matched.
     * "a.b.evil.co.uk" -> index of "evil"; "me.github.io" -> index of "me".
     */
    private fun registrableIndex(labels: List<String>): Pair<Int, String?> {
        if (labels.size < 2) return 0 to null
        for (len in 3 downTo 2) {
            if (labels.size > len) {
                val suffix = labels.takeLast(len).joinToString(".")
                if (suffix in HOSTING_PLATFORMS) return (labels.size - len - 1) to suffix
            }
        }
        val lastTwo = labels.takeLast(2).joinToString(".")
        val suffixLen = if (labels.size > 2 && lastTwo in MULTI_LEVEL_SUFFIXES) 2 else 1
        return maxOf(0, labels.size - suffixLen - 1) to null
    }

    private fun scriptsOf(label: String): Set<Character.UnicodeScript> {
        val result = mutableSetOf<Character.UnicodeScript>()
        label.codePoints().forEach { cp ->
            val script = Character.UnicodeScript.of(cp)
            if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
                result += script
            }
        }
        return result
    }

    /** Replaces look-alike characters with the Latin letters they imitate and strips accents. */
    fun skeleton(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder()
        decomposed.codePoints().forEach { cp ->
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) return@forEach
            val mapped = CONFUSABLES[cp]
            if (mapped != null) sb.append(mapped) else sb.appendCodePoint(cp)
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }

    /**
     * Folds visually similar ASCII sequences together so "paypa1", "rnicrosoft"
     * and "g00gle" compare equal to the real names. Applied to both sides of a comparison.
     */
    private fun fold(s: String): String {
        var r = s
        for ((digit, letter) in DIGIT_LOOKALIKES) r = r.replace(digit.toString(), letter)
        return r.replace("rn", "m").replace("vv", "w").replace('i', 'l').replace("cl", "d")
    }

    /** Optimal string alignment distance: insert, delete, substitute, swap neighbors. */
    private fun osaDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    private fun scriptName(script: Character.UnicodeScript): String =
        script.name.lowercase().replaceFirstChar(Char::titlecase)

    private fun describeChars(
        host: String,
        markDigits: Boolean,
        nativeScript: Character.UnicodeScript? = null
    ): List<CharInfo> {
        val result = mutableListOf<CharInfo>()
        host.codePoints().forEach { cp ->
            val text = String(Character.toChars(cp))
            val script = Character.UnicodeScript.of(cp)
            val plain = cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code || cp == '-'.code ||
                cp == '.'.code || (nativeScript != null && script == nativeScript)
            val digitLook = if (markDigits) DIGIT_LOOKALIKES[cp.toChar()] else null
            val looksLike = when {
                !plain -> skeleton(text).takeIf { it != text && it.all { c -> c.code < 128 } }
                else -> digitLook
            }
            result += CharInfo(
                text = text,
                codePoint = cp,
                name = Character.getName(cp) ?: "U+%04X".format(cp),
                script = scriptName(script),
                looksLike = looksLike,
                suspicious = !plain || digitLook != null
            )
        }
        return result
    }
}
