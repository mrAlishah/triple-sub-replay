package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiConnectionTarget
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState

/**
 * The body of Settings → AI Assistant → Connection: the saved keys for a keyed service, or the sign-in for ChatGPT Subscription.
 * Which one shows comes from the target's access method. A keyed [target] other than the assistant's own service is managed in
 * place; the assistant keeps its service.
 */
@Composable
internal fun AiConnectionBody(
    target: AiConnectionTarget,
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    chatGpt: ChatGptActions?,
) {
    when (target) {
        // The keys shown are the service's own; saving or testing one never changes the service chat uses.
        is AiConnectionTarget.Key ->
            AiKeySettings(aiState.copy(settings = aiState.settings.copy(provider = target.provider)), controller, managing = target.provider)
        AiConnectionTarget.ChatGpt ->
            if (chatGpt != null) {
                val state by chatGpt.state.collectAsState()
                AiChatGptConnection(state, chatGpt)
            }
    }
}

/** ChatGPT Subscription: not connected, connecting, connected or the real reason it is not, with the action that fits each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiChatGptConnection(
    state: ChatGptUiState,
    actions: ChatGptActions,
) {
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(status.labelRes),
            color = if (status.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("chatgpt_status"),
        )
        if (status == ChatGptStatus.CONNECTING || status == ChatGptStatus.CHECKING) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("chatgpt_progress")) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ai_connection_chatgpt_connecting))
            }
        }
        if (state.signInFailed) {
            Text(
                stringResource(R.string.chatgpt_sign_in_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status != ChatGptStatus.NOT_CONNECTED && status != ChatGptStatus.CONNECTING) {
            Text(
                state.account?.let { stringResource(R.string.chatgpt_account, it) } ?: stringResource(R.string.chatgpt_account_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chatgpt_account"),
            )
        }
        Text(
            stringResource(R.string.chatgpt_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == ChatGptStatus.NOT_CONNECTED || status == ChatGptStatus.SIGN_IN_REQUIRED || status == ChatGptStatus.NO_PLAN_USAGE) {
                Button(onClick = actions.signIn, modifier = Modifier.testTag("chatgpt_continue")) {
                    Text(stringResource(R.string.chatgpt_continue))
                }
            }
            if (status != ChatGptStatus.NOT_CONNECTED && status != ChatGptStatus.CONNECTING) {
                TextButton(onClick = actions.refresh, modifier = Modifier.testTag("chatgpt_refresh")) {
                    Text(stringResource(R.string.chatgpt_refresh))
                }
                TextButton(onClick = actions.disconnect, modifier = Modifier.testTag("chatgpt_disconnect")) {
                    Text(stringResource(R.string.chatgpt_disconnect))
                }
            }
            if (status == ChatGptStatus.READY) {
                OutlinedButton(
                    onClick = actions.test,
                    enabled = !state.testing,
                    modifier = Modifier.testTag("chatgpt_test"),
                ) { Text(stringResource(R.string.ai_settings_test)) }
            }
            if (status == ChatGptStatus.CONNECTING) {
                TextButton(onClick = actions.cancel, modifier = Modifier.testTag("chatgpt_cancel")) {
                    Text(stringResource(R.string.chatgpt_cancel))
                }
            }
        }
        if (state.testing) Text(stringResource(R.string.ai_checking), modifier = Modifier.testTag("chatgpt_testing"))
        if (state.testFailed) {
            Text(
                stringResource(R.string.ai_connection_test_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("chatgpt_test_failed"),
            )
        } else if (state.testResult != null) {
            Text(
                stringResource(R.string.ai_settings_test_passed),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("chatgpt_test_passed"),
            )
        }
    }
}
