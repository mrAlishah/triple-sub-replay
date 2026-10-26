package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.savedWordFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordToSendTest {
    private val selection =
        LearningWordSelection(
            AnalyzedToken("Verletzten", 0, 10, PartOfSpeech.NOUN),
            "de",
            "en",
            "abcdefghijk",
            SubtitleSegment(7, 971_000, 974_000, "Er kümmert sich um die Verletzten.", "He takes care of the injured."),
            false,
            extraMeaningLanguages = listOf("fa"),
        )
    private val id = savedWordFrom(selection, "", false).id

    @Test
    fun sendCarriesWhatTheCardShowsAndTheNotesSavedForTheSameWord() {
        val word =
            wordToSend(
                selection,
                "injured",
                mapOf("fa" to "مجروح شد"),
                true,
                mapOf("fa" to "او از مجروحان مراقبت می‌کند."),
                mapOf(id to "my notes\nline 2"),
            )
        assertEquals("injured", word.meaning)
        assertEquals(mapOf("fa" to "مجروح شد"), word.meanings)
        assertEquals(mapOf("fa" to "او از مجروحان مراقبت می‌کند."), word.sentenceTranslations)
        assertTrue(word.online)
        assertEquals("my notes\nline 2", word.personalNotes)
    }

    @Test
    fun sendWithoutSavedNotesOrAClipHasEmptyNotesAndNoOnlineExample() {
        val word = wordToSend(selection, "injured", emptyMap(), false, emptyMap(), mapOf("other-id" to "not mine"))
        assertEquals("", word.personalNotes)
        assertFalse(word.online)
        assertEquals(emptyMap<String, String>(), word.meanings)
    }

    private fun saved() =
        SavedWord(
            "w",
            "Verletzten",
            null,
            "de",
            "en",
            "injured",
            "s",
            null,
            null,
            0,
            0,
            false,
            meanings = mapOf("fa" to "مجروح شد", "ar" to "جريح"),
            sentenceTranslations = mapOf("fa" to "جمله", "ar" to "جملة"),
        )

    @Test
    fun sendAllKeepsOnlyTheLanguagesThatAreOnNow() {
        val on = DualSubUiState(targetLanguage = "en", thirdLanguage = "fa", tripleSubtitlesEnabled = true)
        val sent = saved().withActiveLanguages(on)
        assertEquals(mapOf("fa" to "مجروح شد"), sent.meanings)
        assertEquals(mapOf("fa" to "جمله"), sent.sentenceTranslations)
        val off = saved().withActiveLanguages(on.copy(tripleSubtitlesEnabled = false))
        assertEquals(emptyMap<String, String>(), off.meanings)
        assertEquals("injured", off.meaning)
    }
}
