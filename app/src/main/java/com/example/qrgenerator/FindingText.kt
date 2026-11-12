package com.example.qrgenerator

import android.content.Context

/** Turns analyzer findings into user facing text. Kept apart so the analyzer stays Android free. */
object FindingText {

    fun of(context: Context, f: Finding): String = when (f) {
        is Finding.UnsafeScheme -> context.getString(R.string.warn_unsafe_scheme, f.scheme)
        is Finding.UnknownScheme -> context.getString(R.string.warn_unknown_scheme, f.scheme)
        Finding.InvisibleChars -> context.getString(R.string.warn_invisible)
        Finding.Unparseable -> context.getString(R.string.warn_unparseable)
        is Finding.MixedScripts ->
            context.getString(R.string.warn_mixed_scripts, f.label, f.scripts.joinToString(" + "))
        is Finding.AllLookalikes -> context.getString(R.string.warn_all_lookalikes, f.label, f.readsAs)
        is Finding.ImitatesBrand -> context.getString(R.string.warn_imitates_brand, f.brandDomain)
        is Finding.Typosquat -> context.getString(R.string.warn_typosquat, f.label, f.brandDomain)
        is Finding.BrandInSubdomain -> context.getString(R.string.warn_brand_subdomain, f.brand, f.realDomain)
        is Finding.UserInfo -> context.getString(R.string.warn_userinfo, f.ignoredPart, f.realHost)
        is Finding.ObfuscatedIp -> context.getString(R.string.warn_obfuscated_ip, f.host)
        is Finding.BrandOnWrongDomain -> context.getString(
            R.string.warn_brand_wrong_domain, f.brand, f.realDomain, f.officialDomains.joinToString(", ")
        )
        Finding.IpAddress -> context.getString(R.string.warn_ip)
        Finding.NoEncryption -> context.getString(R.string.warn_http)
        is Finding.Shortener -> context.getString(R.string.warn_shortener, f.domain)
        Finding.NonLatinDomain -> context.getString(R.string.warn_non_latin)
        is Finding.FreeHosting -> context.getString(R.string.warn_free_hosting, f.platform)
        is Finding.SuspiciousTld -> context.getString(R.string.warn_suspicious_tld, f.tld)
        is Finding.UnusualPort -> context.getString(R.string.warn_port, f.port)
        is Finding.ManySubdomains -> context.getString(R.string.warn_many_subdomains, f.levels)
        is Finding.LoginWords -> context.getString(R.string.warn_login_words, f.words.joinToString("\", \""))
    }

    fun verdict(risk: Severity?): Int = when (risk) {
        Severity.HIGH -> R.string.verdict_high
        Severity.MEDIUM -> R.string.verdict_medium
        Severity.LOW -> R.string.verdict_low
        null -> R.string.verdict_none
    }

    fun colorRes(risk: Severity?): Int = when (risk) {
        Severity.HIGH -> R.color.error
        Severity.MEDIUM -> R.color.warning
        Severity.LOW -> R.color.text_secondary
        null -> R.color.ok
    }
}
