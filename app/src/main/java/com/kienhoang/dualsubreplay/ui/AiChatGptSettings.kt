package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiConnectionTarget
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import kotlinx.coroutines.flow.MutableStateFlow

// Fork-owned (docs/development/fork-customizations.md, FC-02): the ChatGPT Subscription parts of the AI settings and chat.

/**
 * Shown in place of the chat while ChatGPT Subscription is the Chat AI Service but its account cannot answer: what to do, and the
 * account's real status. It never asks for a key and never uses another service.
 */
@Composable
internal fun AiChatGptStateCard(
    status: ChatGptStatus,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai_chat_chatgpt_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_chat_chatgpt_connect), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(status.labelRes),
                color = if (status.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ai_chat_chatgpt_status"),
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("ai_open_settings")) {
                Text(stringResource(R.string.ai_open_settings))
            }
        }
    }
}

/** The Model and Thinking card's one line for the service shown: its model, and its thinking level where it has one. */
@Composable
internal fun aiModelSummary(
    target: AiConnectionTarget,
    aiState: AiAssistantUiState,
    chatGpt: ChatGptActions?,
): String =
    when (target) {
        AiConnectionTarget.ChatGpt -> {
            val state by (chatGpt?.state ?: remember { MutableStateFlow(ChatGptUiState()) }).collectAsStateWithLifecycle()
            state.model.ifEmpty { stringResource(R.string.ai_settings_model) }
        }
        is AiConnectionTarget.Key ->
            aiState.settings.modelFor(target.provider).ifEmpty { stringResource(R.string.ai_settings_model) } + " · " +
                stringResource(R.string.ai_effort_button, stringResource(aiState.settings.thinkingFor(target.provider).labelRes))
    }

@StringRes
internal fun aiConnectionLabel(target: AiConnectionTarget): Int =
    when (target) {
        AiConnectionTarget.ChatGpt -> R.string.ai_service_chatgpt
        is AiConnectionTarget.Key -> target.provider.labelRes
    }

/** The Connection card's one line: the saved keys of a keyed service, or the ChatGPT sign-in state. */
@Composable
internal fun aiConnectionSummary(
    target: AiConnectionTarget,
    aiState: AiAssistantUiState,
    chatGpt: ChatGptActions?,
): String =
    when (target) {
        AiConnectionTarget.ChatGpt -> {
            val state by (chatGpt?.state ?: remember { MutableStateFlow(ChatGptUiState()) }).collectAsStateWithLifecycle()
            stringResource(state.status.labelRes)
        }
        is AiConnectionTarget.Key ->
            aiState.keys[target.provider].orEmpty().size.let { count ->
                if (count ==
                    0
                ) {
                    stringResource(R.string.ai_settings_keys_none)
                } else {
                    pluralStringResource(R.plurals.ai_settings_keys_count, count, count)
                }
            }
    }

/** ChatGPT Subscription: its authorized model catalog above the model box. It has no adjustable thinking level. */
@Composable
internal fun AiChatGptModelSettings(chatGpt: ChatGptActions) {
    val state by chatGpt.state.collectAsStateWithLifecycle()
    val pickerState =
        when (state.status) {
            ChatGptStatus.READY -> AiPickerState.READY
            ChatGptStatus.CHECKING, ChatGptStatus.CONNECTING -> AiPickerState.LOADING
            ChatGptStatus.TEMPORARY_FAILURE -> AiPickerState.FAILED
            else -> AiPickerState.NEEDS_CONNECTION
        }
    AiModelPicker(
        typed = state.model,
        models = state.models.map { AiModelChoice(it.slug, it.displayName) },
        state = pickerState,
        connectMessage = R.string.ai_config_models_connect_chatgpt,
        onPick = chatGpt.selectModel,
        onRefresh = chatGpt.refresh,
    )
    Spacer(Modifier.height(8.dp))
    // Saved when the user is done typing, not on every key, so a half-typed id never reloads the video.
    var text by remember(state.model) { mutableStateOf(state.model) }
    val commit = {
        val id = text.trim()
        if (id.isEmpty()) {
            text = state.model
        } else if (id != state.model) {
            chatGpt.selectModel(id)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().testTag("ai_model").onFocusChanged { if (!it.isFocused) commit() },
        label = { Text(stringResource(R.string.ai_settings_model)) },
        singleLine = true,
        isError = text.isBlank(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
    SettingsHint(stringResource(R.string.ai_config_effort_fixed_chatgpt))
}

/** The chat's own settings: which service chat uses (only services that are set up), and the response-source switch. */
@Composable
internal fun AiChatServiceSettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    chatGpt: ChatGptActions?,
    chatTarget: AiConnectionTarget,
) {
    val hasChatGpt = chatGpt != null
    val gptState by (chatGpt?.state ?: remember { MutableStateFlow(ChatGptUiState()) }).collectAsStateWithLifecycle()
    SettingsSubheading(stringResource(R.string.ai_chat_service))
    AiServiceMenu(
        aiState,
        chatTarget,
        offered = { service ->
            service == chatTarget ||
                when (service) {
                    AiConnectionTarget.ChatGpt -> gptState.status == ChatGptStatus.READY
                    is AiConnectionTarget.Key -> aiState.keys[service.provider].orEmpty().isNotEmpty()
                }
        },
        onSelect = {
            when (it) {
                AiConnectionTarget.ChatGpt -> controller.selectChatGptService()
                is AiConnectionTarget.Key -> controller.selectProvider(it.provider)
            }
        },
        tag = "ai_chat_service",
        showChatGpt = hasChatGpt,
    )
    SettingsHint(stringResource(R.string.ai_chat_gear_hint))
    SettingsSwitchRow(
        title = stringResource(R.string.ai_show_response_source_title),
        description = stringResource(R.string.ai_show_response_source_description),
        checked = aiState.settings.showResponseSource,
        onCheckedChange = controller::setShowResponseSource,
        testTag = "ai_show_response_source",
    )
}
