package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Writer
import java.util.Locale

/** Rows read from disk at a time, and how many such pages stay in memory. */
internal const val WHOLE_TRANSCRIPT_PAGE_ROWS = 50
internal const val WHOLE_TRANSCRIPT_PAGES_KEPT = 8

/** Translations of sentences the sheet showed, kept so scrolling back costs nothing. */
internal const val WHOLE_TRANSCRIPT_TRANSLATIONS_KEPT = 600

/** Search stops after this many matching rows. */
internal const val WHOLE_TRANSCRIPT_MAX_MATCHES = 500

/** Sentences translated in one go while exporting (one online request with [TranscriptTrack]'s prefetch). */
internal const val EXPORT_BATCH_SENTENCES = 40

/** Rows search and export read from disk at a time; they are dropped before the next batch. */
private const val SCAN_ROWS = 500

/**
 * The whole transcript of the playing video, for the Whole transcript sheet (25kiuo, Discussion #81,
 * 2026-11-17; spec docs/specs/2026-11-17-tapped-word-first-and-custom-repeat.md). The playing panel
 * keeps its bounded window on purpose: a 6-hour video once used up the app's memory. This reads the
 * same on-disk [store] a page at a time, so at most [WHOLE_TRANSCRIPT_PAGES_KEPT] pages of rows are
 * in memory however long the video is; search and export stream through it.
 *
 * [translate] and [prefetch] are the playing video's own engine for Subtitle 1 (Google with its pacing, AI, or
 * the device): a sentence is translated only when its row is on screen or when exporting asks for it. [subtitle2]
 * is the same for Subtitle 2, built by the fork's ThirdLineTranslation (always from the Original); it is only
 * used by exports. The store closes when the video changes; reads then return nothing.
 */
internal class WholeTranscript(
    private val store: SubtitleStore,
    translate: suspend (String) -> String,
    prefetch: (suspend (List<String>) -> Unit)?,
    subtitle1Language: String? = null,
    private val subtitle2: TranscriptTrack? = null,
    /** Whether Subtitle 1's engine may ask the selected AI service (Subtitle 2's is [TranscriptTrack.usesAi]). */
    val subtitle1UsesAi: Boolean = false,
) {
    val size: Int get() = store.size

    /** The languages of the two translation lines an export can include; Subtitle 2's is null when it is off or repeats another line. */
    val subtitle1Language: String? = subtitle1Language
    val subtitle2Language: String? get() = subtitle2?.language
    val subtitle2UsesAi: Boolean get() = subtitle2?.usesAi == true

    private val subtitle1 = TranscriptTrack(subtitle1Language.orEmpty(), translate, prefetch)
    private val lock = Mutex()
    private val pages =
        object : LinkedHashMap<Int, List<SubtitleSegment>>(WHOLE_TRANSCRIPT_PAGES_KEPT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<SubtitleSegment>>?) =
                size > WHOLE_TRANSCRIPT_PAGES_KEPT
        }

    /** Row [index], or null once the video changed. */
    suspend fun row(index: Int): SubtitleSegment? {
        if (index !in 0 until size) return null
        val page = index / WHOLE_TRANSCRIPT_PAGE_ROWS
        val rows = lock.withLock { pages[page] } ?: read(pageRange(page))?.also { lock.withLock { pages[page] = it } }
        return rows?.getOrNull(index - page * WHOLE_TRANSCRIPT_PAGE_ROWS)
    }

    /** A translation already known for [row]'s sentence, without asking for one. */
    fun knownTranslation(row: SubtitleSegment): String? = subtitle1.known(sentenceText(row))

    /**
     * Translations for the sentences that start in [indices] (the rows on screen), in one online
     * request where the engine can. A sentence's translation shows under its first row only.
     */
    suspend fun translateStarts(indices: IntRange): Map<Int, String> {
        val starts = indices.mapNotNull { index -> row(index)?.takeIf(::startsSentence)?.let { index to it } }
        val missing = starts.map { sentenceText(it.second) }.filter { text -> subtitle1.known(text) == null }.distinct()
        try {
            subtitle1.fill(missing)
        } catch (_: StaleTranscriptException) {
            // The video changed while the rows were asked for; the sheet closes itself, so they simply stay untranslated.
            return emptyMap()
        }
        return starts.mapNotNull { (index, row) -> knownTranslation(row)?.let { index to it } }.toMap()
    }

    /** Rows whose text contains [query] (ignoring case), at most [WHOLE_TRANSCRIPT_MAX_MATCHES]. */
    suspend fun search(query: String): List<Int> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()
        val matches = mutableListOf<Int>()
        var first = 0
        while (first < size && matches.size < WHOLE_TRANSCRIPT_MAX_MATCHES) {
            val range = first..minOf(size - 1, first + SCAN_ROWS - 1)
            val rows = read(range) ?: break
            rows.forEachIndexed { offset, row ->
                if (matches.size < WHOLE_TRANSCRIPT_MAX_MATCHES && needle in row.originalText.lowercase(Locale.ROOT)) {
                    matches += range.first + offset
                }
            }
            first = range.last + 1
        }
        return matches
    }

    /**
     * Writes the transcript to [out] one sentence per entry: [TranscriptExportOptions.format] text ("[m:ss]
     * sentence" lines) or SRT. After the Original come Subtitle 1 and Subtitle 2, each only when asked for, in that
     * order; each comes from its own engine and cache, always translated from the Original. A sentence a requested
     * track can't translate is left without that line and counted in the outcome. [onProgress] gets rows done out
     * of [size]. The outcome is not completed, and [TranscriptExportOutcome.changed], when the video or its subtitle
     * setup was replaced before the end: nothing of another video or setup is ever written.
     */
    suspend fun export(
        out: Writer,
        options: TranscriptExportOptions,
        title: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TranscriptExportOutcome =
        try {
            writeAll(out, options, title, onProgress)
        } catch (_: StaleTranscriptException) {
            TranscriptExportOutcome(completed = false, changed = true)
        }

    private suspend fun writeAll(
        out: Writer,
        options: TranscriptExportOptions,
        title: String?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): TranscriptExportOutcome {
        var first = 0
        var entry = 0
        var open: SentenceEntry? = null
        val missing = IntArray(2)
        if (options.format == TranscriptExportFormat.TEXT && !title.isNullOrBlank()) out.write("$title\n\n")
        while (first < size) {
            val range = first..minOf(size - 1, first + SCAN_ROWS - 1)
            val rows = read(range) ?: return TranscriptExportOutcome(completed = false, changed = true)
            val sentences = mutableListOf<SentenceEntry>()
            rows.forEach { row ->
                val current = open
                if (current != null && !startsSentence(row) && current.text == sentenceText(row)) {
                    current.endMs = row.endMs
                } else {
                    current?.let(sentences::add)
                    open = SentenceEntry(row.startMs, row.endMs, sentenceText(row))
                }
            }
            entry = writeSentences(out, options, sentences, entry, missing)
            first = range.last + 1
            onProgress(first, size)
        }
        open?.let { last -> writeSentences(out, options, listOf(last), entry, missing) }
        out.flush()
        return TranscriptExportOutcome(completed = true, missingSubtitle1 = missing[0], missingSubtitle2 = missing[1])
    }

    /** Translates the requested tracks for [sentences], writes them numbered after [done], and returns the new count. */
    private suspend fun writeSentences(
        out: Writer,
        options: TranscriptExportOptions,
        sentences: List<SentenceEntry>,
        done: Int,
        missing: IntArray,
    ): Int {
        val tracks = listOf(subtitle1.takeIf { options.subtitle1 }, subtitle2.takeIf { options.subtitle2 })
        tracks.forEach { it?.fillInBatches(sentences.map { sentence -> sentence.text }) }
        sentences.forEachIndexed { offset, sentence ->
            val lines = tracks.map { track -> track?.known(sentence.text) }
            // A requested track with no translation (or no engine at all) is counted, never filled from the other track.
            if (options.subtitle1 && lines[0].isNullOrBlank()) missing[0]++
            if (options.subtitle2 && lines[1].isNullOrBlank()) missing[1]++
            writeEntry(out, options.format, done + offset + 1, sentence, lines.mapNotNull { it?.takeIf(String::isNotBlank) })
        }
        return done + sentences.size
    }

    private fun pageRange(page: Int): IntRange {
        val first = page * WHOLE_TRANSCRIPT_PAGE_ROWS
        return first..minOf(size - 1, first + WHOLE_TRANSCRIPT_PAGE_ROWS - 1)
    }

    /** The rows in [range] from disk; null once the store was closed (the video changed). */
    private suspend fun read(range: IntRange): List<SubtitleSegment>? =
        try {
            withContext(Dispatchers.IO) { store.read(range) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private class SentenceEntry(
        val startMs: Long,
        var endMs: Long,
        val text: String,
    )

    private fun writeEntry(
        out: Writer,
        format: TranscriptExportFormat,
        number: Int,
        sentence: SentenceEntry,
        translations: List<String>,
    ) {
        when (format) {
            TranscriptExportFormat.TEXT -> {
                out.write("[${clockTime(sentence.startMs)}] ${sentence.text}\n")
                translations.forEach { out.write("    $it\n") }
            }
            TranscriptExportFormat.SRT -> {
                out.write("$number\n${srtTime(sentence.startMs)} --> ${srtTime(maxOf(sentence.endMs, sentence.startMs))}\n")
                out.write("${srtSafe(sentence.text)}\n")
                translations.forEach { out.write("${srtSafe(it)}\n") }
                out.write("\n")
            }
        }
    }
}

internal enum class TranscriptExportFormat(
    val extension: String,
) {
    TEXT("txt"),
    SRT("srt"),
}

/** Whether [row] is the first row of its sentence (or a row without one), where its translation shows. */
internal fun startsSentence(row: SubtitleSegment): Boolean = (row.sentence?.index ?: 0) == 0

/** The sentence [row] belongs to, or its own text. */
internal fun sentenceText(row: SubtitleSegment): String = row.sentence?.text ?: row.originalText

/** "m:ss", or "h:mm:ss" from an hour on. */
internal fun clockTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1_000)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}

/** SRT's "hh:mm:ss,mmm". */
internal fun srtTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val hours = clamped / 3_600_000
    val minutes = (clamped % 3_600_000) / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val millis = clamped % 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
}
