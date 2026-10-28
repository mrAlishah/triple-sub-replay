package com.kienhoang.dualsubreplay.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTranslateLaunchTest {
    @Test
    fun theLaunchIntentIsAimedAtTheBrowserOnly() {
        val url = googleTranslateUrl("Badeanzug", GoogleTranslatePair("de", "en"))
        val intent = googleTranslateIntent(url, "com.android.chrome")

        assertEquals("https://translate.google.com/?sl=de&tl=en&text=Badeanzug&op=translate", intent.data.toString())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        // A set package keeps the Google Translate app from intercepting the link.
        assertEquals("com.android.chrome", intent.`package`)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
