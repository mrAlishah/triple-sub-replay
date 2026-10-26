package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.ANKIDROID_PERMISSION
import com.kienhoang.dualsubreplay.data.AnkiProblem
import com.kienhoang.dualsubreplay.data.AnkiSendResult
import com.kienhoang.dualsubreplay.data.SavedWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sends the saved word shown in Practice: [prepare] first saves any edit still pending and returns the stored
 * word (null when it no longer exists, so nothing is sent), then [send] sends exactly that word.
 */
internal suspend fun sendEditedWord(
    prepare: suspend () -> SavedWord?,
    send: suspend (SavedWord) -> AnkiSendResult,
): AnkiSendResult? = prepare()?.let { send(it) }

/**
 * The "Send to AnkiDroid" button of the Practice word details. It sends through the same [StudyTools.send]
 * as the word card's Send button, shows progress and refuses a second tap while sending, then shows
 * "Sent to AnkiDroid" or the failed step in red. [alreadySent] is the stored sent state of the word.
 */
@Composable
internal fun PracticeSendToAnki(
    wordId: String,
    canSend: Boolean,
    alreadySent: Boolean,
    needsPermission: () -> Boolean,
    prepare: suspend () -> SavedWord?,
    send: suspend (SavedWord) -> AnkiSendResult,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    // Not keyed by the word: a review can move to the next word while a send runs, which must not allow a second one.
    var sending by remember { mutableStateOf(false) }
    var result by remember(wordId) { mutableStateOf<AnkiSendResult?>(null) }
    // A card added but left in another deck (WRONG_DECK) still counts as sent; a skipped word was sent before.
    val sent = alreadySent || result?.let { it.sent > 0 || (it.problem == null && it.skipped > 0) } == true

    fun run() {
        if (sending) return
        sending = true
        result = null
        scope.launch {
            result =
                try {
                    sendEditedWord(prepare, send)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    AnkiSendResult(problem = AnkiProblem.FAILED)
                } finally {
                    sending = false
                }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) run() else result = AnkiSendResult(problem = AnkiProblem.NO_PERMISSION)
        }
    val name = stringResource(FlashcardApp.ANKIDROID.nameRes)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { if (needsPermission()) permission.launch(ANKIDROID_PERMISSION) else run() },
            enabled = canSend && !sending && !sent,
            modifier = Modifier.testTag("practice_send_to_anki"),
        ) {
            if (sending) {
                CircularProgressIndicator(Modifier.size(18.dp).testTag("practice_send_progress"), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when {
                    sent -> stringResource(R.string.study_sent_to_anki, name)
                    sending -> stringResource(R.string.study_sending_to_anki)
                    else -> stringResource(R.string.study_send_to_anki, name)
                },
            )
        }
        result?.takeIf { it.problem != null }?.let {
            Text(
                sendProblemText(resources, FlashcardApp.ANKIDROID, it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("practice_send_problem"),
            )
        }
    }
}
