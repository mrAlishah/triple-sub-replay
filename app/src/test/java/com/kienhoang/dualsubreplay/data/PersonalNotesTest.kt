package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PersonalNotesTest {
    private val notes = "Grammar: Plural.\n\nدستور زبان — نکته\nMixed Deutsch + فارسی"
    private val word =
        SavedWord(
            "id",
            "Haus",
            null,
            "de",
            "en",
            "house",
            "Das Haus.",
            "The house.",
            "dQw4w9WgXcQ",
            1000,
            2000,
            false,
            dueAt = 5_000,
            intervalMs = 7_000,
            meanings = mapOf("fa" to "خانه"),
            personalNotes = notes,
        )

    @Test
    fun notesSurviveStorageAndALegacyPayloadDecodesWithEmptyNotes() {
        assertEquals(notes, decodeWord(JSONObject(encodeWord(word).toString())).personalNotes)
        val legacy = encodeWord(word.copy(personalNotes = ""))
        assertFalse(legacy.has("personalNotes"))
        assertEquals("", decodeWord(legacy).personalNotes)
    }

    @Test
    fun savingTheWordAgainKeepsItsNotesUnlessNewOnesAreGiven() {
        assertEquals(notes, mergeResaved(word, word.copy(personalNotes = "", meaning = "home")).personalNotes)
        assertEquals("new", mergeResaved(word, word.copy(personalNotes = "new")).personalNotes)
    }

    @Test
    fun editingNotesKeepsMeaningsAndEditingMeaningsKeepsNotesAndReviewHistory() {
        val edited = word.copy(personalNotes = "changed")
        assertEquals(word.meanings, edited.meanings)
        assertEquals(word.dueAt, edited.dueAt)
        assertEquals(word.intervalMs, edited.intervalMs)
        assertEquals(notes, word.copy(meaning = "home", meanings = emptyMap()).personalNotes)
        val reviewed = reviewWord(word, ReviewRating.GOOD, 10_000)
        assertEquals(notes, reviewed.personalNotes)
    }

    @Test
    fun backupRoundTripsNotesWithNewlinesAndPersian() {
        val document = parseTransfer(exportBackup(listOf(word)))
        val restored = previewImport(document, defaultTransferMapping(document), "de", "en", emptyList()).words.single()
        assertEquals(notes, restored.personalNotes)
        assertEquals(word.dueAt, restored.dueAt)
    }

    @Test
    fun tsvRoundTripsMultilineNotesAndOldTsvFilesHaveNone() {
        val document = parseTransfer(exportAnkiTsv(listOf(word)))
        val restored = previewImport(document, defaultTransferMapping(document), "de", "en", emptyList()).words.single()
        assertEquals(notes, restored.personalNotes)
        val old = parseTransfer("#separator:Tab\n#columns:word\tmeaning\nHaus\thouse\n")
        assertEquals("", previewImport(old, defaultTransferMapping(old), "de", "en", emptyList()).words.single().personalNotes)
    }
}
