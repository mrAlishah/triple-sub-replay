package com.kienhoang.dualsubreplay.ui

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTranslateLaunchTest {
    private val translateApp = "com.google.android.apps.translate"

    private fun intentFor(text: String): Intent = googleTranslateSendIntent(text)!!

    @Test
    fun theIntentSharesPlainTextToTheTranslateAppOnly() {
        val intent = intentFor("Badeanzug")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Badeanzug", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(translateApp, intent.`package`)
        // No web address and no browser: only the share contract.
        // A fresh launch, so an already open Translate app reads the new text.
        val fresh = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(fresh, intent.flags and fresh)
        assertNull(intent.data)
        assertFalse(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun theExactSelectionArrivesUnchanged() {
        listOf(
            "Badeanzug",
            "Größe, süß & Straße",
            "Ich habe einen neuen Badeanzug gekauft.",
            "می‌خواهم یک مایو بخرم؟",
            "  zwei  Wörter\tund\nZeile ",
        ).forEach { assertEquals(it, intentFor(it).getStringExtra(Intent.EXTRA_TEXT)) }
        assertTrue(intentFor("می‌خواهم").getStringExtra(Intent.EXTRA_TEXT)!!.contains('‌'))
    }

    @Test
    fun launchingNeverThrowsWhateverTheDeviceHas() {
        // Not installed, no exported receiver and a launch failure all return a result instead of opening a web page.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = translateSendResult(installed = false, hasExportedReceiver = false)
        assertEquals(TranslateSendResult.NOT_INSTALLED, result)
        assertEquals(TranslateSendResult.CANNOT_RECEIVE, sendToGoogleTranslate(context, "  "))
    }

    /** Read-only: reports whether this device's Translate app really has an exported ACTION_SEND text/plain receiver. */
    @Test
    fun reportsTheInstalledTranslateAppReceiver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receivers = context.packageManager.queryIntentActivities(intentFor("x"), 0)
        val found = receivers.map { "${it.activityInfo.name} exported=${it.activityInfo.exported}" }
        println("GoogleTranslateLaunchTest: ${receivers.size} receiver(s): $found")
    }
}
