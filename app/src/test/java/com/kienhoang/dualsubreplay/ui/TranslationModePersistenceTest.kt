package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_ONLY_PREFERENCE
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_PREFERENCE
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import com.kienhoang.dualsubreplay.translation.saveTranslationMode
import com.kienhoang.dualsubreplay.translation.storedTranslationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings → Captions & translation → Translate subtitles with: the chosen mode must come back after the app restarts. */
class TranslationModePersistenceTest {
    /** What a restart keeps: only the stored values, not any object the app held. */
    private fun restart(before: FakePrefs): FakePrefs =
        FakePrefs().also {
            it.map.putAll(before.map)
            it.booleans.putAll(before.booleans)
        }

    @Test
    fun aNewInstallationStartsOnGoogleTranslate() {
        val mode = FakePrefs().storedTranslationMode()
        assertEquals(SubtitleTranslator.GOOGLE, mode.translator)
        assertFalse(mode.ai)
        assertFalse(mode.aiOnly)
    }

    @Test
    fun everyModeIsStillSelectedAfterTheAppRestarts() {
        SubtitleTranslator.entries.forEach { chosen ->
            val prefs = FakePrefs()
            prefs.saveTranslationMode(chosen)
            val restored = restart(prefs).storedTranslationMode()
            assertEquals("$chosen must survive a restart", chosen, restored.translator)
            assertEquals(chosen.engine, restored.engine)
            assertEquals("AI flag of $chosen", chosen.usesAi, restored.ai)
            assertEquals("AI-only flag of $chosen", chosen == SubtitleTranslator.AI_ONLY, restored.aiOnly)
        }
    }

    @Test
    fun aiTranslationAndAiOnlyAreTheValuesTheOldCodeLostAtStartup() {
        val prefs = FakePrefs()
        prefs.saveTranslationMode(SubtitleTranslator.AI_WITH_GOOGLE)
        assertEquals(true, prefs.booleans[AI_TRANSLATION_PREFERENCE])
        assertEquals(false, prefs.booleans[AI_TRANSLATION_ONLY_PREFERENCE])
        // Restoring only the engine, as before the fix, would read this as Google Translate.
        assertEquals("google_web", prefs.map[TRANSLATION_ENGINE_PREFERENCE])
        assertEquals(SubtitleTranslator.AI_WITH_GOOGLE, restart(prefs).storedTranslationMode().translator)
        prefs.saveTranslationMode(SubtitleTranslator.AI_ONLY)
        assertEquals(SubtitleTranslator.AI_ONLY, restart(prefs).storedTranslationMode().translator)
    }

    @Test
    fun changingTheModeLeavesNoStaleFlagBehind() {
        val prefs = FakePrefs()
        prefs.saveTranslationMode(SubtitleTranslator.AI_ONLY)
        prefs.saveTranslationMode(SubtitleTranslator.AI_WITH_GOOGLE)
        assertEquals(SubtitleTranslator.AI_WITH_GOOGLE, restart(prefs).storedTranslationMode().translator)
        prefs.saveTranslationMode(SubtitleTranslator.GOOGLE)
        val google = restart(prefs).storedTranslationMode()
        assertEquals(SubtitleTranslator.GOOGLE, google.translator)
        assertFalse(google.ai)
        assertFalse(google.aiOnly)
        prefs.saveTranslationMode(SubtitleTranslator.ON_DEVICE)
        assertEquals(SubtitleTranslator.ON_DEVICE, restart(prefs).storedTranslationMode().translator)
        prefs.saveTranslationMode(SubtitleTranslator.AI_ONLY)
        assertEquals(SubtitleTranslator.AI_ONLY, restart(prefs).storedTranslationMode().translator)
    }

    @Test
    fun readingTheSavedModeNeverWritesAnything() {
        val prefs = FakePrefs()
        prefs.saveTranslationMode(SubtitleTranslator.AI_ONLY)
        val strings = prefs.map.toMap()
        val flags = prefs.booleans.toMap()
        repeat(3) { prefs.storedTranslationMode() }
        // A Google or on-device fallback after an AI failure is shown on screen only; nothing on the read side stores it.
        assertEquals(strings, prefs.map)
        assertEquals(flags, prefs.booleans)
    }

    @Test
    fun theAssistantsEngineSwitchOnlyTouchesTheEngineAndTheAiChoiceComesBack() {
        val prefs = FakePrefs()
        prefs.saveTranslationMode(SubtitleTranslator.AI_ONLY)
        prefs.edit().putString(TRANSLATION_ENGINE_PREFERENCE, "on_device").apply()
        assertEquals(SubtitleTranslator.ON_DEVICE, restart(prefs).storedTranslationMode().translator)
        prefs.edit().putString(TRANSLATION_ENGINE_PREFERENCE, "google_web").apply()
        assertEquals(SubtitleTranslator.AI_ONLY, restart(prefs).storedTranslationMode().translator)
    }

    @Test
    fun theStartupStateRestoresAllThreeValuesNotJustTheEngine() {
        // AppViewModel cannot be built on the JVM; this pins the exact lines whose loss reset AI translation to Google.
        val source = File("src/main/java/com/kienhoang/dualsubreplay/ui/AppViewModel.kt").readText()
        listOf(
            "translationEngine = preferences.storedTranslationMode().engine",
            "aiTranslation = preferences.storedTranslationMode().ai",
            "aiTranslationOnly = preferences.storedTranslationMode().aiOnly",
            "preferences.saveTranslationMode(translator)",
        ).forEach { line -> assertTrue("AppViewModel must contain: $line", line in source) }
    }
}
