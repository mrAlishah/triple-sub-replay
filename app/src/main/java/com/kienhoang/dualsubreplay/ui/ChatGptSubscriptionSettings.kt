package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSubscription
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import kotlinx.coroutines.flow.StateFlow

/** What Settings → Translation → AI access can do with the ChatGPT Subscription. */
internal class ChatGptActions(
    val state: StateFlow<ChatGptUiState>,
    val select: (Boolean) -> Unit,
    val signIn: () -> Unit,
    val refresh: () -> Unit,
    val disconnect: () -> Unit,
    val cancel: () -> Unit,
    val selectModel: (String) -> Unit,
    val test: () -> Unit,
)

internal fun ChatGptSubscription.actions() =
    ChatGptActions(state, ::select, ::signIn, ::refresh, ::disconnect, ::cancel, ::selectModel, ::test)

@get:StringRes
private val ChatGptStatus.labelRes: Int
    get() =
        when (this) {
            ChatGptStatus.NOT_CONNECTED -> R.string.chatgpt_status_not_connected
            ChatGptStatus.CONNECTING -> R.string.chatgpt_status_connecting
            ChatGptStatus.CHECKING -> R.string.chatgpt_status_checking
            ChatGptStatus.READY -> R.string.chatgpt_status_ready
            ChatGptStatus.NO_PLAN_USAGE -> R.string.chatgpt_status_no_plan
            ChatGptStatus.NOT_ELIGIBLE -> R.string.chatgpt_status_not_eligible
            ChatGptStatus.SIGN_IN_REQUIRED -> R.string.chatgpt_status_sign_in
            ChatGptStatus.USAGE_LIMIT -> R.string.chatgpt_status_limit
            ChatGptStatus.TEMPORARY_FAILURE -> R.string.chatgpt_status_temporary
        }

private val ChatGptStatus.isProblem: Boolean
    get() =
        this == ChatGptStatus.NOT_ELIGIBLE || this == ChatGptStatus.USAGE_LIMIT || this == ChatGptStatus.TEMPORARY_FAILURE ||
            this == ChatGptStatus.SIGN_IN_REQUIRED

/** Settings → Translation → AI access, with the ChatGPT panel below it while ChatGPT Subscription is chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiAccessSettings(
    chatGpt: ChatGptActions,
    whenKeys: @Composable () -> Unit,
) {
    val state = chatGpt.state.collectAsState().value
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_ai_access_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !state.useSubscription,
                onClick = { chatGpt.select(false) },
                label = { Text(stringResource(R.string.settings_ai_access_keys)) },
                modifier = Modifier.testTag("ai_access_keys"),
            )
            FilterChip(
                selected = state.useSubscription,
                onClick = { chatGpt.select(true) },
                label = { Text(stringResource(R.string.settings_ai_access_chatgpt)) },
                modifier = Modifier.testTag("ai_access_chatgpt"),
            )
        }
    }
    if (state.useSubscription) ChatGptSubscriptionPanel(state, chatGpt) else whenKeys()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatGptSubscriptionPanel(
    state: ChatGptUiState,
    actions: ChatGptActions,
) {
    val status = state.status
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("chatgpt_panel")) {
        Text(
            stringResource(R.string.chatgpt_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(status.labelRes),
            modifier = Modifier.padding(top = 8.dp).testTag("chatgpt_status"),
            color = if (status.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
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
        if (status == ChatGptStatus.READY) {
            Text(stringResource(R.string.chatgpt_model_title), modifier = Modifier.padding(top = 8.dp))
            if (state.models.isEmpty()) {
                Text(
                    stringResource(R.string.chatgpt_model_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.models.forEach { model ->
                    FilterChip(
                        selected = state.model == model.slug,
                        onClick = { actions.selectModel(model.slug) },
                        label = { Text(model.displayName) },
                        modifier = Modifier.testTag("chatgpt_model_${model.slug}"),
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == ChatGptStatus.NOT_CONNECTED || status == ChatGptStatus.SIGN_IN_REQUIRED ||
                status == ChatGptStatus.NO_PLAN_USAGE
            ) {
                Button(
                    onClick = actions.signIn,
                    modifier = Modifier.testTag("chatgpt_continue"),
                ) { Text(stringResource(R.string.chatgpt_continue)) }
            }
            if (status != ChatGptStatus.NOT_CONNECTED && status != ChatGptStatus.CONNECTING) {
                TextButton(
                    onClick = actions.refresh,
                    modifier = Modifier.testTag("chatgpt_refresh"),
                ) { Text(stringResource(R.string.chatgpt_refresh)) }
            }
            if (status == ChatGptStatus.READY && state.model.isNotEmpty()) {
                TextButton(onClick = actions.test, enabled = !state.testing, modifier = Modifier.testTag("chatgpt_test")) {
                    Text(stringResource(if (state.testing) R.string.chatgpt_testing else R.string.chatgpt_test))
                }
            }
            if (status == ChatGptStatus.CONNECTING) {
                TextButton(
                    onClick = actions.cancel,
                    modifier = Modifier.testTag("chatgpt_cancel"),
                ) { Text(stringResource(R.string.chatgpt_cancel)) }
            }
            if (status != ChatGptStatus.NOT_CONNECTED && status != ChatGptStatus.CONNECTING) {
                TextButton(onClick = actions.disconnect, modifier = Modifier.testTag("chatgpt_disconnect")) {
                    Text(stringResource(R.string.chatgpt_disconnect))
                }
            }
        }
        state.testResult?.let { Text(stringResource(R.string.chatgpt_test_result, it), modifier = Modifier.testTag("chatgpt_test_result")) }
        if (state.testFailed) {
            Text(
                stringResource(R.string.chatgpt_test_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
