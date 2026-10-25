package com.kienhoang.dualsubreplay.data

import org.junit.Assert.*
import org.junit.Test

class SavedWordTest {
    private val selection = LearningWordSelection(AnalyzedToken("Learn", 0, 5, PartOfSpeech.VERB), "en", "vi",
        "dQw4w9WgXcQ", SubtitleSegment(1, 1250, 4250, "Learn a word", "Học một từ"), false)
    private val card = savedWordFrom(selection, "học", true)

    @Test fun selectionUsesTappedLineLanguageAndKeepsItsSentenceSnapshot() {
        val original = learningSelection(WordTap(selection.token, selection.segment, false), "en", "vi", selection.videoId)
        val translated = learningSelection(WordTap(selection.token.copy(text = "học"), selection.segment, true), "en", "vi", selection.videoId)
        assertEquals("en", original.wordLanguage)
        assertEquals("vi", original.meaningLanguage)
        assertEquals("vi", translated.wordLanguage)
        assertEquals("en", translated.meaningLanguage)
        assertEquals(selection.segment, translated.segment)
        assertEquals(selection.videoId, translated.videoId)
    }

    @Test fun identityIncludesLanguageAndExampleButNotMeaningOrClipChoices() {
        assertEquals(card.id, savedWordFrom(selection.copy(token = selection.token.copy(text = "learn")), "new meaning", false).id)
        assertNotEquals(card.id, savedWordFrom(selection.copy(wordLanguage = "de"), "học", true).id)
        assertNotEquals(card.id, savedWordFrom(selection.copy(segment = selection.segment!!.copy(startMs = 1500)), "học", true).id)
        assertTrue(card.id.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun onlineClipChoiceIsIndependentAndInvalidContextCannotCreateClip() {
        for (online in listOf(true, false)) {
            val saved = savedWordFrom(selection, "học", online)
            assertEquals(online, saved.online)
        }
        val invalid = savedWordFrom(selection.copy(videoId = "../invalid"), "học", true)
        assertFalse(invalid.online)
        assertFalse(validClipRange(selection.videoId, 100, 100))
        assertFalse(validClipRange(selection.videoId, -1, 100))
    }
    @Test fun reviewRatingsScheduleFromInjectedTimeAndAgainRestartsProgression() {
        val now = 1_000_000L
        assertEquals(now + 600_000, reviewWord(card, ReviewRating.AGAIN, now).dueAt)
        assertEquals(now + DAY_MS, reviewWord(card, ReviewRating.HARD, now).dueAt)
        assertEquals(now + 3 * DAY_MS, reviewWord(card, ReviewRating.GOOD, now).dueAt)
        assertEquals(now + 7 * DAY_MS, reviewWord(card, ReviewRating.EASY, now).dueAt)
        val reviewed = reviewWord(card, ReviewRating.GOOD, now)
        assertEquals(6 * DAY_MS, reviewWord(reviewed, ReviewRating.GOOD, now).intervalMs)
        val lapsed = reviewWord(reviewed, ReviewRating.AGAIN, now)
        assertEquals(3 * DAY_MS, reviewWord(lapsed, ReviewRating.GOOD, now).intervalMs)
    }
    @Test fun savedDataRoundTripsWithReviewState() {
        val saved = card.copy(intervalMs = 99, dueAt = 123)
        assertEquals(saved, decodeWord(encodeWord(saved)))
        assertEquals(card.copy(videoId = null, reading = null, translatedSentence = null),
            decodeWord(encodeWord(card.copy(videoId = null, reading = null, translatedSentence = null))))
    }

    @Test fun sentenceTranslationsRoundTripAndOldPayloadsDecodeToNone() {
        val withFa = card.copy(sentenceTranslations = mapOf("fa" to "یک کلمه یاد بگیر"))
        assertEquals(withFa, decodeWord(encodeWord(withFa)))
        assertFalse(encodeWord(card).has("sentenceTranslations"))
        assertEquals(emptyMap<String, String>(), decodeWord(encodeWord(card)).sentenceTranslations)
    }

    @Test fun savingAgainKeepsKnownTranslationsAddsNewOnesAndKeepsReviewState() {
        val old = card.copy(dueAt = 5, intervalMs = 9, sentenceTranslations = mapOf("fa" to "قدیمی"))
        val merged = mergeResaved(old, card.copy(meaning = "neu", sentenceTranslations = mapOf("fa" to "جدید", "de" to "Satz")))
        assertEquals("neu", merged.meaning)
        assertEquals(5, merged.dueAt)
        assertEquals(9, merged.intervalMs)
        assertEquals(mapOf("fa" to "قدیمی", "de" to "Satz"), merged.sentenceTranslations)
        assertEquals(old.sentenceTranslations, mergeResaved(old, card).sentenceTranslations)
    }

    @Test fun savedWordFromStoresOnlyNonBlankSentenceTranslations() {
        val saved = savedWordFrom(selection, "học", true, mapOf("fa" to "متن", "ar" to " "))
        assertEquals(mapOf("fa" to "متن"), saved.sentenceTranslations)
        assertEquals(card.id, saved.id)
    }
}
