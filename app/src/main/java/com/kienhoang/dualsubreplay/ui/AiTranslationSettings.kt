package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiEffort
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.aiAccessOption
import com.kienhoang.dualsubreplay.assistant.aiAccessOptions
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import kotlinx.coroutines.flow.StateFlow

/** What Settings → Captions & translation → AI can do with the provider, model and effort. */
internal class AiTranslationActions(
    val state: StateFlow<AiTranslationUiState>,
    val selectAccess: (String) -> Unit,
    val selectModel: (String) -> Unit,
    val selectEffort: (AiEffort) -> Unit,
    val ensureModels: () -> Unit,
    val refresh: () -> Unit,
    val test: () -> Unit,
    /** Sign-in, refresh and disconnect for ChatGPT Subscription. */
    val chatGpt: ChatGptActions,
)

internal fun AiTranslationConfig.actions(chatGpt: ChatGptActions) =
    AiTranslationActions(state, ::selectAccess, ::selectModel, ::selectEffort, { ensureModels() }, ::refresh, ::test, chatGpt)

/**
 * The AI configuration of the AI translation modes: one dropdown each for the provider, the model and the reasoning effort, the
 * provider's connection card and one Test translation action. [onOpenKeys] opens the AI assistant's key screen, or is null where
 * the dialog has none.
 */
@Composable
internal fun AiConfigurationSection(
    actions: AiTranslationActions,
    onOpenKeys: (() -> Unit)?,
) {
    val state by actions.state.collectAsState()
    val gpt by actions.chatGpt.state.collectAsState()
    LaunchedEffect(state.accessId) { actions.ensureModels() }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("ai_config"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderDropdown(state, actions.selectAccess)
        ModelDropdown(state, actions)
        EffortDropdown(state, actions.selectEffort)
        ConnectionCard(state, gpt, actions, onOpenKeys)
        TestTranslation(state, gpt, actions)
    }
}

/** A read-only field that opens a menu: the shared look of the three dropdowns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    supporting: String?,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            supportingText = supporting?.let { { Text(it) } },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                    .fillMaxWidth()
                    .semantics { role = Role.DropdownList }
                    .testTag(tag),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}

@Composable
private fun ProviderDropdown(
    state: AiTranslationUiState,
    onSelect: (String) -> Unit,
) {
    val current = aiAccessOption(state.accessId)
    DropdownField(
        label = stringResource(R.string.ai_config_provider),
        value = current?.let { stringResource(it.labelRes) }.orEmpty(),
        enabled = true,
        tag = "ai_provider_field",
        supporting = null,
    ) { close ->
        aiAccessOptions().forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(option.labelRes)) },
                onClick = {
                    close()
                    onSelect(option.id)
                },
                modifier = Modifier.semantics { selected = option.id == state.accessId }.testTag("ai_provider_${option.id}"),
            )
        }
    }
}

@Composable
private fun ModelDropdown(
    state: AiTranslationUiState,
    actions: AiTranslationActions,
) {
    val name = state.models.firstOrNull { it.id == state.model }?.name ?: state.model
    val canPick = state.models.isNotEmpty()
    val supporting =
        when {
            state.replacedModel != null -> stringResource(R.string.ai_config_model_replaced, state.replacedModel, name)
            state.modelsState == AiModelsState.LOADING -> stringResource(R.string.ai_config_models_loading)
            state.modelsState == AiModelsState.FAILED -> stringResource(R.string.ai_config_models_failed)
            state.modelsState == AiModelsState.EMPTY -> stringResource(R.string.ai_config_models_empty)
            state.modelsState == AiModelsState.NEEDS_CONNECTION ->
                stringResource(if (state.isChatGpt) R.string.ai_config_models_connect_chatgpt else R.string.ai_config_models_connect_key)
            else -> null
        }
    DropdownField(
        label = stringResource(R.string.ai_config_model),
        value = name,
        enabled = canPick,
        tag = "ai_model_field",
        supporting = supporting,
    ) { close ->
        state.models.forEach { choice ->
            DropdownMenuItem(
                text = { Text(choice.name) },
                onClick = {
                    close()
                    actions.selectModel(choice.id)
                },
                leadingIcon = { SelectedMark(choice.id == state.model) },
                modifier = Modifier.semantics { selected = choice.id == state.model }.testTag("ai_model_${choice.id}"),
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ai_config_models_refresh)) },
            onClick = {
                close()
                actions.refresh()
            },
            modifier = Modifier.testTag("ai_models_refresh"),
        )
    }
}

@Composable
private fun EffortDropdown(
    state: AiTranslationUiState,
    onSelect: (AiEffort) -> Unit,
) {
    val adjustable = state.effortOptions.size > 1
    val supporting =
        when {
            state.savedEffort != state.effort ->
                stringResource(
                    R.string.ai_config_effort_replaced,
                    stringResource(state.savedEffort.labelRes),
                )
            state.isChatGpt -> stringResource(R.string.ai_config_effort_fixed_chatgpt)
            !adjustable && state.accessId == AiProvider.CLAUDE.key -> stringResource(R.string.ai_config_effort_unknown_claude)
            !adjustable -> stringResource(R.string.ai_config_effort_fixed)
            state.effort == AiEffort.AUTO && state.automaticShortensThinking -> stringResource(R.string.ai_config_effort_auto_note)
            else -> null
        }
    DropdownField(
        label = stringResource(R.string.ai_config_effort),
        value = stringResource(state.effort.labelRes),
        enabled = adjustable,
        tag = "ai_effort_field",
        supporting = supporting,
    ) { close ->
        state.effortOptions.forEach { effort ->
            DropdownMenuItem(
                text = { Text(stringResource(effort.labelRes)) },
                onClick = {
                    close()
                    onSelect(effort)
                },
                leadingIcon = { SelectedMark(effort == state.effort) },
                modifier = Modifier.semantics { selected = effort == state.effort }.testTag("ai_effort_${effort.key}"),
            )
        }
    }
}

/** The provider's account or key status, its billing line, and the actions that belong to it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(
    state: AiTranslationUiState,
    gpt: ChatGptUiState,
    actions: AiTranslationActions,
    onOpenKeys: (() -> Unit)?,
) {
    val option = aiAccessOption(state.accessId) ?: return
    OutlinedCard(Modifier.fillMaxWidth().testTag("ai_connection_card")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.isChatGpt) {
                ChatGptConnection(gpt, actions.chatGpt)
            } else {
                Text(
                    stringResource(if (state.keyPresent) R.string.ai_config_key_saved else R.string.ai_config_key_missing),
                    color = if (state.keyPresent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ai_key_status"),
                )
                Text(
                    stringResource(option.noteRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenKeys != null) {
                    FlowRow {
                        TextButton(onClick = onOpenKeys, modifier = Modifier.testTag("ai_manage_keys")) {
                            Text(stringResource(R.string.ai_config_manage_keys))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatGptConnection(
    state: ChatGptUiState,
    actions: ChatGptActions,
) {
    val status = state.status
    Text(
        stringResource(status.labelRes),
        color = if (status.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("chatgpt_status"),
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
    Text(
        stringResource(R.string.chatgpt_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == ChatGptStatus.NOT_CONNECTED || status == ChatGptStatus.SIGN_IN_REQUIRED || status == ChatGptStatus.NO_PLAN_USAGE) {
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
            TextButton(onClick = actions.disconnect, modifier = Modifier.testTag("chatgpt_disconnect")) {
                Text(stringResource(R.string.chatgpt_disconnect))
            }
        }
        if (status == ChatGptStatus.CONNECTING) {
            TextButton(
                onClick = actions.cancel,
                modifier = Modifier.testTag("chatgpt_cancel"),
            ) { Text(stringResource(R.string.chatgpt_cancel)) }
        }
    }
}

/** One Test translation for whichever provider is chosen, and what it proved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestTranslation(
    state: AiTranslationUiState,
    gpt: ChatGptUiState,
    actions: AiTranslationActions,
) {
    val ready =
        if (state.isChatGpt) {
            gpt.status == ChatGptStatus.READY && state.model.isNotEmpty()
        } else {
            state.keyPresent &&
                state.model.isNotEmpty()
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = actions.test, enabled = ready && !state.testing, modifier = Modifier.testTag("ai_test")) {
            Text(stringResource(if (state.testing) R.string.ai_translation_testing else R.string.ai_translation_test))
        }
        state.test?.let { outcome ->
            val provider = aiAccessOption(outcome.accessId)?.let { stringResource(it.labelRes) }.orEmpty()
            Text(
                stringResource(R.string.ai_translation_test_setup, provider, outcome.model, stringResource(outcome.effort.labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ai_test_setup"),
            )
            Text(
                stringResource(R.string.ai_translation_test_pair),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outcome.errorRes != null) {
                Text(
                    stringResource(outcome.errorRes),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ai_test_error"),
                )
            }
            if (outcome.text != null) {
                Text(stringResource(R.string.ai_translation_test_result, outcome.text), modifier = Modifier.testTag("ai_test_result"))
                Text(
                    stringResource(R.string.ai_translation_test_origin, stringResource(translationSourceRes(outcome.origin))),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ai_test_origin"),
                )
            }
        }
    }
}
