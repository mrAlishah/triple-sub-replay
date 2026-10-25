package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningsTest {
    private val word =
        SavedWord(
            "id",
            "Aufstieg",
            null,
            "de",
            "en",
            "Ascension",
            "Der Aufstieg war schwierig.",
            "The ascent was difficult.",
            "dQw4w9WgXcQ",
            1000,
            2000,
            false,
            meanings = mapOf("fa" to "صعود / ترقی"),
        )

    @Test fun backupKeepsEveryMeaningByLanguage() {
        val back = decodeWord(JSONObject(encodeWord(word).toString()))
        assertEquals(mapOf("fa" to "صعود / ترقی"), back.meanings)
        assertEquals("Ascension", back.meaning)
    }

    @Test fun oldPayloadWithoutMeaningsStillDecodes() {
        val old = encodeWord(word.copy(meanings = emptyMap()))
        assertFalse(old.has("meanings"))
        assertEquals(emptyMap<String, String>(), decodeWord(old).meanings)
    }

    @Test fun resaveKeepsEarlierLanguagesAndTheNewEditWins() {
        val merged = mergeResaved(word, word.copy(meaning = "Rise", meanings = mapOf("ar" to "صعود", "fa" to "بالا رفتن")))
        assertEquals("Rise", merged.meaning)
        assertEquals(mapOf("fa" to "بالا رفتن", "ar" to "صعود"), merged.meanings)
    }

    @Test fun savedWordDropsBlankAndPrimaryLanguageMeanings() {
        val selection = LearningWordSelection(AnalyzedToken("Aufstieg", 0, 8, PartOfSpeech.NOUN), "de", "en", null, null, false)
        val saved = savedWordFrom(selection, "Ascension", false, meanings = mapOf("fa" to " صعود ", "en" to "x", "ar" to " "))
        assertEquals(mapOf("fa" to "صعود"), saved.meanings)
    }

    @Test fun savingExtraMeaningsKeepsTheWordId() {
        val selection = LearningWordSelection(AnalyzedToken("Aufstieg", 0, 8, PartOfSpeech.NOUN), "de", "en", null, null, false)
        assertEquals(savedWordFrom(selection, "", false).id, savedWordFrom(selection, "A", false, meanings = mapOf("fa" to "ص")).id)
    }

    @Test fun tsvRoundTripsMeaningsWithPersianNewlinesAndTabs() {
        val tricky = word.copy(meanings = mapOf("fa" to "صعود\nترقی\tپیشرفت", "ar" to "ارتقاء"))
        val tsv = exportAnkiTsv(listOf(tricky))
        val document = parseTransfer(tsv)
        val preview = previewImport(document, defaultTransferMapping(document), "de", "en", emptyList())
        assertEquals(1, preview.words.size)
        assertEquals(mapOf("fa" to "صعود ترقی\tپیشرفت", "ar" to "ارتقاء"), preview.words.single().meanings)
    }

    @Test fun oldTsvWithoutMeaningsColumnStillImports() {
        val header = "#separator:Tab\n#columns:word\tmeaning\nHaus\thouse\n"
        val document = parseTransfer(header)
        val words = previewImport(document, defaultTransferMapping(document), "de", "en", emptyList()).words
        assertEquals(emptyMap<String, String>(), words.single().meanings)
    }

    @Test fun ankiMeaningFieldListsPrimaryThenLabeledExtras() {
        val field = ankiNoteFields(word)[1]
        assertTrue(field.startsWith("Ascension"))
        assertTrue(field.contains("dir=\"auto\""))
        assertTrue(field.contains("صعود / ترقی"))
        assertTrue(field.contains("fa"))
        assertEquals("Ascension", ankiNoteFields(word.copy(meanings = emptyMap()))[1])
    }
}
