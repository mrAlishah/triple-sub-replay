package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleTranslateLinkTest {
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
    fun resultNamesTheFirstMissingStep() {
        assertEquals(TranslateSendResult.NOT_INSTALLED, translateSendResult(installed = false, hasExportedReceiver = false))
        assertEquals(TranslateSendResult.CANNOT_RECEIVE, translateSendResult(installed = true, hasExportedReceiver = false))
        assertEquals(TranslateSendResult.SENT, translateSendResult(installed = true, hasExportedReceiver = true))
    }

    @Test
    fun blankTextBuildsNoIntent() {
        assertEquals(null, googleTranslateSendIntent(""))
        assertEquals(null, googleTranslateSendIntent("  \n"))
    }
}
