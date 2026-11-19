package com.kienhoang.dualsubreplay.ui

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where an export stands: rows done out of all rows, then how it ended. PARTIAL is a saved file where a requested
 * subtitle line could not be made; [missingLanguages] names those lines' languages, kept here so the message stays right
 * after the video changes. CHANGED is an export that stopped because the video or its subtitle setup was replaced; FAILED
 * is a file that could not be written. After CHANGED, FAILED and CANCELLED the document may exist with partial content.
 */
internal data class TranscriptExportState(
    val done: Int,
    val total: Int,
    val result: Result = Result.RUNNING,
    val missingLanguages: List<String> = emptyList(),
) {
    enum class Result { RUNNING, SAVED, PARTIAL, FAILED, CHANGED, CANCELLED }
}

/**
 * Writes a [WholeTranscript] to a file the learner picked with Android's own save screen (no storage
 * permission). It streams from disk through a buffered writer, so a 6-hour transcript never sits in
 * memory, and runs in the view model's scope, so closing the sheet doesn't stop it.
 *
 * The save screen has already created the document when this starts and the app is not given a way to tell whether it
 * held something before, so a stopped or failed export never deletes it: it can stay behind incomplete, and the result
 * message says so.
 */
internal class TranscriptExporter(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
) {
    private val _state = MutableStateFlow<TranscriptExportState?>(null)
    val state: StateFlow<TranscriptExportState?> = _state.asStateFlow()
    private var job: Job? = null

    /** Which export owns [state]: an export that was replaced or stopped never writes over a newer one's. */
    @Volatile private var run = 0

    fun start(
        transcript: WholeTranscript,
        uri: Uri,
        options: TranscriptExportOptions,
        title: String?,
    ) {
        job?.cancel()
        val mine = ++run
        _state.value = TranscriptExportState(0, transcript.size)
        job =
            scope.launch {
                val outcome =
                    try {
                        writeTranscript(transcript, { resolver.openOutputStream(uri, "wt") }, options, title) { done, total ->
                            if (mine == run) _state.value = TranscriptExportState(done, total)
                        }
                    } catch (cancel: CancellationException) {
                        if (mine == run) _state.value = _state.value?.copy(result = TranscriptExportState.Result.CANCELLED)
                        throw cancel
                    }
                if (mine == run) {
                    _state.value =
                        (_state.value ?: TranscriptExportState(0, transcript.size)).copy(
                            result = outcome.result,
                            missingLanguages =
                                listOfNotNull(
                                    transcript.subtitle1Language.takeIf { outcome.missingSubtitle1 > 0 },
                                    transcript.subtitle2Language.takeIf { outcome.missingSubtitle2 > 0 },
                                ),
                        )
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Forgets a finished export's message. */
    fun dismiss() {
        if (_state.value?.result != TranscriptExportState.Result.RUNNING) _state.value = null
    }
}
