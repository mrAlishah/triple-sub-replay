package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import kotlin.math.abs

// Per-tab playback speed (docs/specs/2026-11-02-per-tab-settings-profiles.md). YouTube keeps one speed for the whole page, so a
// tab's speed is read back from the page video and, when the tab's video opens, set again. The page video is still the only
// player; nothing here plays or seeks.

private const val SAME_SPEED_EPSILON = 0.01f
private const val STABLE_SAMPLES = 3
private const val MAX_RESTORE_TRIES = 3

internal sealed interface TabSpeedAction {
    data object None : TabSpeedAction

    data class Apply(
        val rate: Float,
    ) : TabSpeedAction

    data class Record(
        val rate: Float,
    ) : TabSpeedAction
}

/**
 * Decides, per sample of the page video, whether to set the tab's saved speed or to remember a new one. A speed counts only
 * when the sample belongs to the open tab's own video, lies in the person's range (ads play at 4x and skip silence goes
 * faster), and stayed the same for [STABLE_SAMPLES] samples. While a saved speed is being restored nothing is recorded, so
 * the speed YouTube carried over from another tab never replaces it.
 */
internal class TabSpeedTracker {
    private var key: Pair<Long?, String>? = null
    private var restoring: Float? = null
    private var restoreTries = 0
    private var candidate: Float? = null
    private var candidateCount = 0

    fun onSample(
        tabId: Long?,
        openVideoId: String?,
        sampleVideoId: String,
        rate: Float,
        saved: Float?,
        recordingAllowed: Boolean,
    ): TabSpeedAction {
        if (tabId == null || openVideoId == null || sampleVideoId != openVideoId) return TabSpeedAction.None
        val current = tabId to sampleVideoId
        if (current != key) {
            key = current
            restoring = saved?.takeIf { it in MIN_WEB_PLAYBACK_SPEED..MAX_WEB_PLAYBACK_SPEED }
            restoreTries = 0
            candidate = null
            candidateCount = 0
        }
        if (!rate.isFinite() || rate !in MIN_WEB_PLAYBACK_SPEED..MAX_WEB_PLAYBACK_SPEED) return TabSpeedAction.None
        restoring?.let { target ->
            if (abs(rate - target) < SAME_SPEED_EPSILON) {
                restoring = null
                return TabSpeedAction.None
            }
            // A page that will not take the speed is left alone; its speed is not recorded over the saved one.
            return if (restoreTries++ < MAX_RESTORE_TRIES) TabSpeedAction.Apply(target) else TabSpeedAction.None
        }
        if (!recordingAllowed || (saved != null && abs(rate - saved) < SAME_SPEED_EPSILON)) {
            candidate = null
            candidateCount = 0
            return TabSpeedAction.None
        }
        if (candidate?.let { abs(it - rate) < SAME_SPEED_EPSILON } == true) candidateCount++ else {
            candidate = rate
            candidateCount = 1
        }
        return if (candidateCount >= STABLE_SAMPLES) TabSpeedAction.Record(rate) else TabSpeedAction.None
    }
}

/** What the speed binder needs from the app: the open tab and its video, the saved speed, and where to save a new one. */
internal class TabSpeedAccess(
    val openVideoId: String?,
    val saved: () -> Float?,
    val record: (Float) -> Unit,
)

/** Keeps the page video's speed in step with the open tab's profile. */
@Composable
internal fun BindTabPlaybackSpeed(webController: YouTubeWebController) {
    val tabSettings = LocalTabSettings.current ?: return
    val access = tabSettings.speed
    val tracker = remember { TabSpeedTracker() }
    val skipSilence = rememberStudySettings().value.skipSilence
    val playback by WebVideoBridge.playback.collectAsState()
    LaunchedEffect(playback, tabSettings.openTabId, access.openVideoId, skipSilence) {
        val sample = playback ?: return@LaunchedEffect
        when (
            val action =
                tracker.onSample(
                    tabSettings.openTabId,
                    access.openVideoId,
                    sample.videoId,
                    sample.playbackRate,
                    access.saved(),
                    recordingAllowed = skipSilence == SkipSilence.OFF,
                )
        ) {
            is TabSpeedAction.Apply -> webController.setPlaybackSpeed(action.rate)
            is TabSpeedAction.Record -> access.record(action.rate)
            TabSpeedAction.None -> Unit
        }
    }
}
