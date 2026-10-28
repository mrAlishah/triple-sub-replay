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
    fun encodesSpacesPunctuationAndUnicode() {
        val pair = GoogleTranslatePair("de", "fa")
        assertEquals(
            "https://translate.google.com/?sl=de&tl=fa&text=Ich%20bin%2C%20%E2%80%9Ehier%E2%80%9C%3F%20a%26b%3Dc%2Bd&op=translate",
            url("Ich bin, „hier“? a&b=c+d", pair),
        )
        assertEquals("%D8%B3%D9%84%D8%A7%D9%85", url("سلام", pair).substringAfter("text=").substringBefore("&"))
    }
}
