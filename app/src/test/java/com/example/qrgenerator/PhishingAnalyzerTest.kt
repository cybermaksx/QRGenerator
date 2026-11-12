package com.example.qrgenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the offline phishing checks. Each case is a real-world trick or a legit site. */
class PhishingAnalyzerTest {

    private fun report(url: String) = PhishingAnalyzer.analyze(url)

    private inline fun <reified T : Finding> assertHas(url: String) {
        val r = report(url)
        assertTrue("Expected ${T::class.simpleName} for $url, got ${r.findings}", r.findings.any { it is T })
    }

    private fun assertClean(url: String) {
        val r = report(url)
        assertNull("Expected no findings for $url, got ${r.findings}", r.risk)
    }

    // ----- legit sites must stay clean

    @Test fun legitSites() {
        assertClean("https://paypal.com")
        assertClean("https://www.google.com/search?q=test")
        assertClean("https://accounts.google.com/")
        assertClean("https://google.de")
        assertClean("https://www.bbc.co.uk/news")
        assertClean("https://pineapple.com")
        assertClean("https://apply.com")
        assertClean("https://finance.ru")
        assertClean("https://t.me/somechannel")
        assertClean("https://telegra.ph/article")
        assertClean("https://online.sberbank.ru")
        assertClean("www.wikipedia.org")
    }

    @Test fun cyrillicZoneIsOnlyInformational() {
        val r = report("https://госуслуги.рф")
        assertEquals(Severity.LOW, r.risk)
        assertTrue(r.findings.single() is Finding.NonLatinDomain)
        assertTrue(r.chars.none { it.suspicious })
        assertEquals("Cyrillic", r.singleScript)
    }

    // ----- look-alike characters

    @Test fun oneCyrillicLetterInLatinDomain() {
        val url = "https://pаypal.com/login" // Cyrillic "а"
        assertHas<Finding.MixedScripts>(url)
        assertHas<Finding.ImitatesBrand>(url)
        val bad = report(url).chars.filter { it.suspicious }
        assertEquals(1, bad.size)
        assertEquals("a", bad[0].looksLike)
        assertEquals("Cyrillic", bad[0].script)
    }

    @Test fun fullyCyrillicApple() {
        // Classic 2017 demo: xn--80ak6aa92e.com renders as "аррӏе.com".
        assertHas<Finding.AllLookalikes>("https://xn--80ak6aa92e.com")
        assertHas<Finding.ImitatesBrand>("https://xn--80ak6aa92e.com")
        assertEquals(Severity.HIGH, report("https://аррӏе.com").risk)
    }

    @Test fun greekAndAccentedLetters() {
        assertHas<Finding.ImitatesBrand>("https://gοοgle.com") // Greek omicrons
        assertHas<Finding.ImitatesBrand>("https://páypal.com")       // a with acute
    }

    @Test fun digitsAndLetterCombos() {
        assertHas<Finding.ImitatesBrand>("https://paypa1.com")
        assertHas<Finding.ImitatesBrand>("https://g00gle.com")
        assertHas<Finding.ImitatesBrand>("https://rnicrosoft.com")
        assertTrue(report("https://paypa1.com").chars.any { it.text == "1" && it.suspicious })
    }

    @Test fun fullwidthIsNormalizedLikeBrowsers() {
        // Fullwidth letters become plain ASCII in the browser, so this IS paypal.com.
        assertClean("https://ｐａｙｐａｌ.com")
    }

    // ----- structure tricks

    @Test fun typosquatting() {
        assertHas<Finding.Typosquat>("https://sberbnak.ru")
        assertHas<Finding.Typosquat>("https://gosuslugy.ru")
        assertHas<Finding.Typosquat>("https://wildberies.ru")
    }

    @Test fun brandAsSubdomain() {
        val r = report("https://paypal.com.secure-check.ru/login")
        assertTrue(r.findings.any { it is Finding.BrandInSubdomain })
        assertEquals("secure-check.ru", r.registrableDomain)
    }

    @Test fun brandWithExtraWords() {
        assertHas<Finding.BrandOnWrongDomain>("https://paypal-secure.xyz")
        assertHas<Finding.SuspiciousTld>("https://paypal-secure.xyz")
        assertHas<Finding.BrandOnWrongDomain>("https://sberbank-online.com")
    }

    @Test fun atSignTrick() {
        val r = report("https://google.com@evil.ru/")
        assertTrue(r.findings.any { it is Finding.UserInfo })
        assertEquals("evil.ru", r.host)
    }

    @Test fun backslashEndsHostLikeInBrowsers() {
        assertEquals("evil.ru", report("https://evil.ru\\@google.com").host)
    }

    @Test fun ipAddresses() {
        assertHas<Finding.IpAddress>("http://192.168.1.1/admin")
        assertHas<Finding.ObfuscatedIp>("http://3232235777/")
        assertHas<Finding.ObfuscatedIp>("http://0x7f.1/")
    }

    @Test fun invisibleCharacters() {
        assertHas<Finding.InvisibleChars>("https://exa​mple.com")
        assertHas<Finding.InvisibleChars>("https://evil.com/‮gpj.exe")
    }

    @Test fun percentEncodedHost() {
        assertEquals("paypal.com", report("https://%70aypal.com").host)
    }

    @Test fun schemesAndTransport() {
        assertHas<Finding.UnsafeScheme>("javascript:alert(1)")
        assertHas<Finding.UnsafeScheme>("intent://scan/#Intent;scheme=zxing;end")
        assertHas<Finding.NoEncryption>("http://example.com")
        assertHas<Finding.Shortener>("https://bit.ly/abc")
        assertHas<Finding.FreeHosting>("https://paypal-verify.web.app")
        assertHas<Finding.UnusualPort>("https://example.com:8443/")
    }

    @Test fun extractsLinksFromText() {
        val links = PhishingAnalyzer.extractLinks("Go to https://paypa1.com/x, or www.site.ru.")
        assertEquals(listOf("https://paypa1.com/x", "www.site.ru"), links)
    }
}
