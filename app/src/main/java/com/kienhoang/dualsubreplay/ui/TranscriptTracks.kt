package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Fork-owned (spec docs/specs/2026-11-19-srt-export-subtitle-2.md): one translated line of the Whole transcript,
 * Subtitle 1 or Subtitle 2. Each track has its own [translate] and [prefetch] (the playing video's engine for that
 * line, always asked with the Original's sentence) and its own bounded cache, so the two lines can never share or
 * replace each other's translations, even for the same sentence. Nothing here reads [DualSubUiState.thirdLines], the
 * playback window's map. [usesAi] says the engine may ask the selected AI service for lines it does not know yet.
 */
internal class TranscriptTrack(
    val language: String,
    private val translate: suspend (String) -> String,
    private val prefetch: (suspend (List<String>) -> Unit)?,
    val usesAi: Boolean = false,
) {
    private val translations =
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > WHOLE_TRANSCRIPT_TRANSLATIONS_KEPT
        }

    /** The translation already known for [text], without asking for one. */
    fun known(text: String): String? = synchronized(translations) { translations[text] }

    /** Asks for the sentences of [texts] not known yet, [EXPORT_BATCH_SENTENCES] at a time, each sentence once. */
    suspend fun fillInBatches(texts: List<String>) {
        texts.filter { known(it) == null }.distinct().chunked(EXPORT_BATCH_SENTENCES).forEach { batch ->
            currentCoroutineContext().ensureActive()
            fill(batch)
        }
    }

    /** Asks the engine for [texts]: in one request first where it can, then each from its cache. */
    suspend fun fill(texts: List<String>) {
        if (texts.isEmpty()) return
        try {
            prefetch?.invoke(texts)
        } catch (cancel: CancellationException) {
            throw endedOr(cancel)
        } catch (_: Exception) {
            // Each sentence is still asked on its own below.
        }
        texts.forEach { text ->
            val translated =
                try {
                    translate(text).trim()
                } catch (cancel: CancellationException) {
                    throw endedOr(cancel)
                } catch (_: Exception) {
                    ""
                }
            if (translated.isNotEmpty()) synchronized(translations) { translations[text] = translated }
        }
    }

    /**
     * A cancellation of the caller stays one. A cancellation that reaches an export which is still running came from the
     * engine's own load ending (the video or a language or translation setting changed): the transcript is stale.
     */
    private suspend fun endedOr(cancel: CancellationException): Throwable =
        if (currentCoroutineContext().isActive) StaleTranscriptException() else cancel
}

/** The transcript's video or subtitle setup was replaced while an export was running. */
internal class StaleTranscriptException : Exception("The video or its subtitle languages changed.")

/** What an export writes after the Original: Subtitle 1 and Subtitle 2, each on its own (Original and Subtitle 1 by default). */
internal data class TranscriptExportOptions(
    val format: TranscriptExportFormat,
    val subtitle1: Boolean = true,
    val subtitle2: Boolean = false,
)

/**
 * How an export ended: [completed] false when it stopped before the end, [changed] when that was because the video or
 * its subtitle setup was replaced (else the file could not be written); [missingSubtitle1] and [missingSubtitle2]
 * count the sentences a requested track could not translate, so a file with gaps is never reported as complete.
 */
internal data class TranscriptExportOutcome(
    val completed: Boolean,
    val missingSubtitle1: Int = 0,
    val missingSubtitle2: Int = 0,
    val changed: Boolean = false,
) {
    val result: TranscriptExportState.Result
        get() =
            when {
                !completed && changed -> TranscriptExportState.Result.CHANGED
                !completed -> TranscriptExportState.Result.FAILED
                missingSubtitle1 > 0 || missingSubtitle2 > 0 -> TranscriptExportState.Result.PARTIAL
                else -> TranscriptExportState.Result.SAVED
            }
}

/**
 * Writes [transcript] to the stream [open] gives, always closing it. A file that cannot be opened or written ends as a
 * failed outcome (not a cancellation, not a missing translation); a cancellation of the caller passes through.
 */
internal suspend fun writeTranscript(
    transcript: WholeTranscript,
    open: () -> OutputStream?,
    options: TranscriptExportOptions,
    title: String?,
    onProgress: (done: Int, total: Int) -> Unit,
): TranscriptExportOutcome =
    try {
        withContext(Dispatchers.IO) {
            val stream = open() ?: throw IOException("No file to write to.")
            stream.bufferedWriter(Charsets.UTF_8).use { out -> transcript.export(out, options, title, onProgress) }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // IOException, SecurityException, or whatever else a document provider throws: no file, not a translation gap.
        TranscriptExportOutcome(completed = false)
    }

/** [text] without blank lines, which would end an SRT cue early and break its numbering. */
internal fun srtSafe(text: String): String = text.lines().filter { it.isNotBlank() }.joinToString("\n")
