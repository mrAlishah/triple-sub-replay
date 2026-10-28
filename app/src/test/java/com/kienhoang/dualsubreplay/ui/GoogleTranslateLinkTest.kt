package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleTranslateLinkTest {
    private fun url(
        text: String,
        pair: GoogleTranslatePair,
    ) = googleTranslateUrl(text, pair)

    @Test
    fun germanOriginalGoesToEnglishSubtitle1() {
        val pair = googleTranslatePair("de", "en", translated = false, lineLanguage = null)
        assertEquals("https://translate.google.com/?sl=de&tl=en&text=Badeanzug&op=translate", url("Badeanzug", pair))
    }

    @Test
    fun germanToPersianAndBack() {
        assertEquals(GoogleTranslatePair("de", "fa"), googleTranslatePair("de", "fa", false, null))
        // Subtitle 1 (Persian here) and Subtitle 2 text both return to the original language.
        assertEquals(GoogleTranslatePair("fa", "de"), googleTranslatePair("de", "fa", true, null))
        assertEquals(GoogleTranslatePair("fa", "de"), googleTranslatePair("de", "en", false, "fa"))
    }

    @Test
    fun englishOriginalToGerman() {
        assertEquals(GoogleTranslatePair("en", "de"), googleTranslatePair("en", "de", false, null))
    }

    @Test
    fun unresolvedOrAutoSourceLetsGoogleDetect() {
        assertEquals(GoogleTranslatePair("auto", "en"), googleTranslatePair(null, "en", false, null))
        assertEquals(GoogleTranslatePair("auto", "en"), googleTranslatePair("auto", "en", false, null))
    }

    @Test
    fun sameLanguageFallsBackToDetection() {
        assertEquals(GoogleTranslatePair("auto", "en"), googleTranslatePair("en", "en", false, null))
    }

    @Test
    fun persianToGermanKeepsPersianText() {
        val pair = googleTranslatePair("de", "fa", translated = true, lineLanguage = null)
        assertEquals("https://translate.google.com/?sl=fa&tl=de&text=%D8%B3%D9%84%D8%A7%D9%85&op=translate", url("سلام", pair))
    }

    @Test
    fun phrasesAndSentencesKeepEveryWord() {
        val pair = googleTranslatePair("de", "en", false, null)

        fun textOf(value: String) = url(value, pair).substringAfter("text=").substringBefore("&")
        assertEquals("guten%20Morgen%2C%20Welt", textOf("guten Morgen, Welt"))
        assertEquals("Ich%20habe%20einen%20neuen%20Badeanzug%20gekauft.", textOf("Ich habe einen neuen Badeanzug gekauft."))
    }

    @Test
    fun configuredLanguageChangeChangesTheUrl() {
        assertEquals(
            "https://translate.google.com/?sl=en&tl=fa&text=hello&op=translate",
            url("hello", googleTranslatePair("en", "fa", false, null)),
        )
    }

    @Test
    fun browserPrefersTheDefaultAndNeverTheTranslateApp() {
        val translateApp = "com.google.android.apps.translate"
        val firefox = "org.mozilla.firefox"
        assertEquals(firefox, pickBrowserPackage(firefox, listOf("com.android.chrome", firefox)))
        assertEquals("com.android.chrome", pickBrowserPackage(null, listOf(translateApp, "com.android.chrome")))
        // A chooser ("android") or the Translate app as default is not a browser.
        assertEquals("com.android.chrome", pickBrowserPackage("android", listOf("com.android.chrome")))
        assertEquals("com.android.chrome", pickBrowserPackage(translateApp, listOf(translateApp, "com.android.chrome")))
    }

    @Test
    fun noBrowserMeansNoLaunch() {
        assertEquals(null, pickBrowserPackage(null, emptyList()))
        val translateApp = "com.google.android.apps.translate"
        assertEquals(null, pickBrowserPackage(translateApp, listOf(translateApp)))
    }

    @Test
    fun encodesSpacesPunctuationAndUnicode() {
        val pair = GoogleTranslatePair("de", "fa")
        assertEquals(
            "https://translate.google.com/?sl=de&tl=fa&text=Ich%20bin%2C%20%E2%80%9Ehier%E2%80%9C%3F%20a%26b%3Dc%2Bd&op=translate",
            url("Ich bin, „hier“? a&b=c+d", pair),
        )
        assertEquals("%D8%B3%D9%84%D8%A7%D9%85", url("سلام", pair).substringAfter("text=").substringBefore("&"))
    }
}
