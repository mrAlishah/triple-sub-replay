package com.kienhoang.dualsubreplay.data

import java.security.MessageDigest
import java.util.Locale

/** A tapped word or phrase. [parts] lists every word of a phrase; a single word is its own only part. */
data class WordTap(
    val token: AnalyzedToken,
    val segment: SubtitleSegment?,
    val translated: Boolean,
    val parts: List<AnalyzedToken> = listOf(token),
    /** The language of the tapped line when it is neither the original nor the second line (the third subtitle). */
    val language: String? = null,
)

data class LearningWordSelection(
    val token: AnalyzedToken,
    val wordLanguage: String,
    val meaningLanguage: String,
    val videoId: String?,
    val segment: SubtitleSegment?,
    val translated: Boolean,
    val parts: List<AnalyzedToken> = listOf(token),
    /** Further subtitle languages to give a meaning in, beside [meaningLanguage], in subtitle-slot order. */
    val extraMeaningLanguages: List<String> = emptyList(),
    /** The video's title when it was verified to belong to [videoId]; null when not known yet. */
    val sourceTitle: String? = null,
) {
    val isPhrase: Boolean get() = parts.size > 1
}

internal fun learningSelection(
    tap: WordTap,
    source: String,
    target: String,
    videoId: String?,
): LearningWordSelection {
    val wordLanguage = tap.language ?: if (tap.translated) target else source
    return LearningWordSelection(
        token = tap.token,
        wordLanguage = wordLanguage,
        meaningLanguage = if (wordLanguage == source) target else source,
        videoId = videoId,
        segment = tap.segment,
        translated = tap.translated,
        parts = tap.parts,
    )
}

/** The language of original subtitle lines, falling back to English until one is known. */
internal fun learningSourceLanguage(
    resolved: String?,
    preference: String,
): String = resolved ?: preference.takeUnless { it == "auto" } ?: "en"

data class SavedWord(
    val id: String,
    val word: String,
    val reading: String?,
    val wordLanguage: String,
    val meaningLanguage: String,
    val meaning: String,
    val sentence: String,
    val translatedSentence: String?,
    val videoId: String?,
    val startMs: Long,
    val endMs: Long,
    val translated: Boolean,
    val online: Boolean = true,
    val dueAt: Long = 0,
    val intervalMs: Long = 0,
    /** Further translations of [sentence] by language code (the third subtitle line). Only real translations, never placeholders. */
    val sentenceTranslations: Map<String, String> = emptyMap(),
    /** The word's meaning in further languages by language code, beside [meaning] in [meaningLanguage]. Only real texts, never placeholders. */
    val meanings: Map<String, String> = emptyMap(),
    /** The user's own multiline notes ("Text" in Anki). Never filled in automatically; empty for older words. */
    val personalNotes: String = "",
    /** The human-readable title of the video the word was saved from. Display and filtering only: never part of the id. */
    val sourceTitle: String? = null,
)

enum class ReviewRating { AGAIN, HARD, GOOD, EASY }

internal const val DAY_MS = 86_400_000L

internal fun reviewInterval(previous: Long, rating: ReviewRating): Long = when (rating) {
    ReviewRating.AGAIN -> 600_000L
    ReviewRating.HARD -> if (previous < DAY_MS) DAY_MS else (previous * 1.2).toLong()
    ReviewRating.GOOD -> if (previous < DAY_MS) 3 * DAY_MS else previous * 2
    ReviewRating.EASY -> if (previous < DAY_MS) 7 * DAY_MS else previous * 3
}.coerceIn(600_000L, 3650 * DAY_MS)

internal fun reviewWord(word: SavedWord, rating: ReviewRating, now: Long): SavedWord {
    val interval = reviewInterval(word.intervalMs, rating)
    return word.copy(dueAt = now + interval, intervalMs = interval)
}

internal fun validClipRange(videoId: String?, start: Long, end: Long): Boolean =
    videoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true && start >= 0 && end > start

/** The same word from the same line gets the same id, so saving it again updates it instead of adding a copy. */
internal fun savedWordId(
    word: String,
    wordLanguage: String,
    meaningLanguage: String,
    videoId: String?,
    segment: SubtitleSegment?,
): String {
    val spelling = if (wordLanguage == "fa") normalizePersian(word) else word.lowercase(Locale.ROOT)
    val key = listOf(spelling, wordLanguage, meaningLanguage,
        videoId.orEmpty(), segment?.startMs.toString(), segment?.endMs.toString()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** An imported word with no notes keeps the notes already saved for it; a file never clears them. */
internal fun keepSavedNotes(
    imported: SavedWord,
    existing: SavedWord,
): SavedWord =
    imported.copy(
        personalNotes = imported.personalNotes.ifBlank { existing.personalNotes },
        sourceTitle = imported.sourceTitle ?: existing.sourceTitle,
    )

/** Saving a word again keeps its review schedule and every translation already stored (new languages are added). */
internal fun mergeResaved(old: SavedWord, new: SavedWord): SavedWord =
    new.copy(
        dueAt = old.dueAt,
        intervalMs = old.intervalMs,
        sentenceTranslations = new.sentenceTranslations + old.sentenceTranslations,
        meanings = (old.meanings + new.meanings).filterValues { it.isNotBlank() },
        personalNotes = new.personalNotes.ifBlank { old.personalNotes },
        sourceTitle = new.sourceTitle ?: old.sourceTitle,
    )

internal fun savedWordFrom(
    selection: LearningWordSelection,
    meaning: String,
    online: Boolean,
    sentenceTranslations: Map<String, String> = emptyMap(),
    meanings: Map<String, String> = emptyMap(),
): SavedWord {
    val segment = selection.segment
    val word = selection.token.text.trim()
    val id = savedWordId(word, selection.wordLanguage, selection.meaningLanguage, selection.videoId, segment)
    val hasClip = validClipRange(selection.videoId, segment?.startMs ?: -1, segment?.endMs ?: -1)
    return SavedWord(id, word, selection.token.reading, selection.wordLanguage, selection.meaningLanguage,
        meaning.trim(), segment?.originalText.orEmpty(), segment?.translatedText, selection.videoId,
        segment?.startMs ?: 0, segment?.endMs ?: 0, selection.translated,
        online = online && hasClip,
        sentenceTranslations = sentenceTranslations.filterValues { it.isNotBlank() },
        meanings = cleanMeanings(meanings, selection.meaningLanguage),
        sourceTitle = normalizeSourceTitle(selection.sourceTitle))
}

/**
 * Trimmed texts without a second copy of the [primary] language. A blank text stays as "" so a re-save
 * ([mergeResaved]) can clear that language; [VocabularyRepository.save] never stores a blank.
 */
private fun cleanMeanings(meanings: Map<String, String>, primary: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for ((language, text) in meanings) if (language != primary) result[language] = text.trim()
    return result
}
