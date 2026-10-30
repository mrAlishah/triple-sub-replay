package com.kienhoang.dualsubreplay.data

import java.text.Normalizer
import java.util.Locale

private const val MAX_SOURCE_TITLE_LENGTH = 200

/** Key of the "Unknown Source" group: records with neither a video id nor a title. */
internal const val UNKNOWN_SOURCE_KEY = ""

/** A source title with runs of whitespace collapsed, trimmed and capped; null when blank (unknown, never invented). */
internal fun normalizeSourceTitle(title: String?): String? =
    title
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_SOURCE_TITLE_LENGTH)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun caseless(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * Which source a word belongs to: its video id (so two videos with one title stay apart and a renamed video stays
 * together), else its normalized title, else [UNKNOWN_SOURCE_KEY].
 */
internal fun sourceKey(word: SavedWord): String =
    word.videoId?.takeIf { it.isNotBlank() }?.let { "v:$it" }
        ?: normalizeSourceTitle(word.sourceTitle)?.let { "t:${caseless(it)}" }
        ?: UNKNOWN_SOURCE_KEY

/** One entry of the source selector. [sharesTitle] is true when another source has the same title, so the UI can tell them apart. */
internal data class SourceOption(
    val key: String,
    val title: String?,
    val videoId: String?,
    val count: Int,
    val sharesTitle: Boolean = false,
)

/** The sources found in [words], titled ones alphabetically, the unknown group last. */
internal fun sourceOptions(words: List<SavedWord>): List<SourceOption> {
    val options =
        words.groupBy(::sourceKey).map { (key, group) ->
            val title =
                group
                    .mapNotNull { normalizeSourceTitle(it.sourceTitle) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
            SourceOption(key, title, group.firstNotNullOfOrNull { it.videoId?.takeIf(String::isNotBlank) }, group.size)
        }
    val titleCounts = options.mapNotNull { it.title?.let(::caseless) }.groupingBy { it }.eachCount()
    return options
        .map { it.copy(sharesTitle = it.title != null && (titleCounts[caseless(it.title)] ?: 0) > 1) }
        .sortedWith(
            compareBy<SourceOption> {
                it.key == UNKNOWN_SOURCE_KEY
            }.thenBy { it.title == null }.thenBy { it.title?.let(::caseless).orEmpty() }.thenBy { it.key },
        )
}

/** The selector entries whose title contains [query] (any case, any script); a blank query keeps all. */
internal fun filterSourceOptions(
    options: List<SourceOption>,
    query: String,
): List<SourceOption> {
    val needle = caseless(query.trim())
    return if (needle.isEmpty()) options else options.filter { it.title != null && caseless(it.title).contains(needle) }
}

/** True for every word when [selected] is null (All Sources), else only for words of that source. */
internal fun matchesSource(
    word: SavedWord,
    selected: String?,
): Boolean = selected == null || sourceKey(word) == selected

/** Words matching the text [search] AND the [selected] source. */
internal fun filterSavedWords(
    words: List<SavedWord>,
    selected: String?,
    search: (SavedWord) -> Boolean,
): List<SavedWord> = words.filter { matchesSource(it, selected) && search(it) }

/** The words of the [selected] source (all when null) that are due at [now], in their given order. */
internal fun dueWordsForSource(
    words: List<SavedWord>,
    selected: String?,
    now: Long,
): List<SavedWord> = words.filter { it.dueAt <= now && matchesSource(it, selected) }

/**
 * Saves a title corrected in Practice. A word with a video goes through [editor] (the same store the player header
 * uses, which also retitles the video's other words); a word without one only changes itself.
 */
internal suspend fun VocabularyRepository.saveSourceTitle(
    word: SavedWord,
    title: String?,
    editor: ((String, String) -> Unit)?,
) {
    val videoId = word.videoId
    if (videoId == null) {
        update(word.id) { it.copy(sourceTitle = title) }
    } else if (title != null) {
        editor?.invoke(videoId, title) ?: retitleVideo(videoId, title)
    }
}
