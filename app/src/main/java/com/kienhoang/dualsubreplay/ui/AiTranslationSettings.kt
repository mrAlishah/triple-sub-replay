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
import com.kienhoang.dualsubreplay.assistant.AiConnectionTarget
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
    val ensureModels: () -> Unit,
    val refresh: () -> Unit,
    val test: () -> Unit,
    /** Sign-in, refresh and disconnect for ChatGPT Subscription. */
    val chatGpt: ChatGptActions,
)

internal fun AiTranslationConfig.actions(chatGpt: ChatGptActions) =
    AiTranslationActions(state, ::selectAccess, { ensureModels() }, ::refresh, ::test, chatGpt)

/**
 * The AI configuration of the AI translation modes: one dropdown each for the provider, the model and the reasoning effort, the
 * provider's connection summary and one Test translation action. [onOpenConnection] opens Settings → AI Assistant → Connection
 * for a provider, or is null where the dialog has none.
 */
@Composable
internal fun AiConfigurationSection(
    actions: AiTranslationActions,
    onOpenConnection: ((AiConnectionTarget) -> Unit)?,
) {
    val state by actions.state.collectAsState()
    val gpt by actions.chatGpt.state.collectAsState()
    LaunchedEffect(state.accessId) { actions.ensureModels() }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("ai_config"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderDropdown(state, actions.selectAccess)
        AiConnectionSummary(state, gpt, onOpenConnection)
        TestTranslation(state, gpt, actions)
    }
}

/** A read-only field that opens a menu: the shared look of the three dropdowns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DropdownField(
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
        label = stringResource(R.string.ai_translation_service),
        value = current?.let { stringResource(it.labelRes) }.orEmpty(),
        enabled = true,
        tag = "ai_provider_field",
        supporting = null,
    ) { close ->
        aiAccessOptions().forEach { option ->
            val connected = option.id in state.connected
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(option.labelRes))
                        Text(
                            stringResource(if (connected) R.string.ai_service_state_connected else R.string.ai_service_state_not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    close()
                    onSelect(option.id)
                },
                modifier = Modifier.semantics { selected = option.id == state.accessId }.testTag("ai_provider_${option.id}"),
            )
        }
    }
}

/**
 * The provider's status in one line and a link to manage it. Signing in, keys, refresh and disconnect live only in
 * Settings → AI Assistant → Connection; [onManage] opens it on this provider.
 */
@Composable
private fun AiConnectionSummary(
    state: AiTranslationUiState,
    gpt: ChatGptUiState,
    onManage: ((AiConnectionTarget) -> Unit)?,
) {
    val option = aiAccessOption(state.accessId) ?: return
    val target = option.provider?.let { AiConnectionTarget.Key(it) } ?: AiConnectionTarget.ChatGpt
    val status =
        when {
            state.isChatGpt -> stringResource(gpt.status.labelRes)
            state.keyPresent -> stringResource(R.string.ai_config_key_saved)
            else -> stringResource(R.string.ai_config_key_missing)
        }
    val problem = if (state.isChatGpt) gpt.status.isProblem else !state.keyPresent
    OutlinedCard(Modifier.fillMaxWidth().testTag("ai_connection_card")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.ai_connection_summary, stringResource(option.labelRes), status),
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("ai_connection_summary"),
            )
            Text(
                stringResource(option.noteRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onManage != null) {
                TextButton(onClick = { onManage(target) }, modifier = Modifier.testTag("ai_manage_connection")) {
                    Text(stringResource(R.string.ai_connection_manage))
                }
            }
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
