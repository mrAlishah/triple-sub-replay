package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiConnectionTarget
import com.kienhoang.dualsubreplay.assistant.aiConnectionTargetFor
import com.kienhoang.dualsubreplay.assistant.aiTranslationModelConflict
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.assistant.AiConnectionTest
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiSavedKey
import com.kienhoang.dualsubreplay.assistant.AiThinking
import com.kienhoang.dualsubreplay.assistant.ChatHistoryRetention
import com.kienhoang.dualsubreplay.assistant.MAX_AI_KEYS_PER_SERVICE
import com.kienhoang.dualsubreplay.assistant.aiCheckedSetup
import com.kienhoang.dualsubreplay.assistant.chatCompletionsUrl
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shown in the panel until the key has answered once: choose a service and paste its key, or
 * wait for, retry or start the check. Chat opens only after the check passes.
 */
@Composable
internal fun AiConnectCard(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai_setup_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (aiState.hasKey && aiState.addressValid) {
                AiKeyCheck(aiState, controller)
            } else {
                Text(stringResource(R.string.ai_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ai_setup_intro), style = MaterialTheme.typography.bodyMedium)
                AiServicePicker(aiState.settings.provider, includeCustom = false, onSelect = controller::selectProvider)
                AiSetupSteps(aiState, controller)
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("ai_open_settings")) {
                Text(stringResource(R.string.ai_open_settings))
            }
        }
    }
}

/**
 * Shown in place of the chat while ChatGPT Subscription is the Chat AI Service: it cannot answer chat yet, so the panel says so
 * and sends nothing, instead of asking for a key or using another service.
 */
@Composable
internal fun AiChatUnavailableCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai_chat_unavailable_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_chat_chatgpt_unavailable), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("ai_open_settings")) {
                Text(stringResource(R.string.ai_open_settings))
            }
        }
    }
}

/** One row per service with what it costs; Other only in settings, for people who know their address. */
@Composable
private fun AiServicePicker(
    selected: AiProvider,
    includeCustom: Boolean,
    onSelect: (AiProvider) -> Unit,
) {
    Column(Modifier.selectableGroup().testTag("ai_service_picker")) {
        AiProvider.entries.filter { includeCustom || it != AiProvider.CUSTOM }.forEach { provider ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = provider == selected, role = Role.RadioButton, onClick = { onSelect(provider) })
                    .padding(vertical = 2.dp)
                    .testTag("ai_provider_${provider.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = provider == selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(provider.labelRes), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(provider.noteRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The chosen service's three steps and its key buttons, or where to enter Other's address. */
@Composable
private fun AiSetupSteps(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val provider = aiState.settings.provider
    if (provider == AiProvider.CUSTOM && !aiState.addressValid) {
        Text(stringResource(R.string.ai_setup_custom_address), style = MaterialTheme.typography.bodyMedium)
        return
    }
    listOf(provider.getKeyStepRes, provider.createKeyStepRes, R.string.ai_setup_step_paste)
        .distinct()
        .forEachIndexed { index, step ->
            Text("${index + 1}. " + stringResource(step), style = MaterialTheme.typography.bodyMedium)
        }
    AiKeyButtons(provider, controller)
    Text(
        stringResource(R.string.ai_settings_key_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A saved key that has not answered yet: checking, why it failed, or a button to check it. */
@Composable
private fun AiKeyCheck(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    when (val test = aiState.connectionTest) {
        AiConnectionTest.Testing ->
            Row(Modifier.testTag("ai_key_checking"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ai_checking), style = MaterialTheme.typography.titleMedium)
            }
        is AiConnectionTest.Failed -> {
            Text(stringResource(R.string.ai_check_failed_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(test.failure.kind.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ai_key_check_failed"),
            )
            test.failure.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Button(onClick = controller::testConnection, modifier = Modifier.testTag("ai_check_key")) {
                Text(stringResource(R.string.ai_retry))
            }
            AiKeyButtons(aiState.settings.provider, controller)
        }
        AiConnectionTest.Idle, AiConnectionTest.Passed -> {
            Text(stringResource(R.string.ai_check_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_check_message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = controller::testConnection, modifier = Modifier.testTag("ai_check_key")) {
                Text(stringResource(R.string.ai_check_key))
            }
        }
    }
}

/** "Get a free key" and "Paste key". Pasting recognizes the service from the key. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiKeyButtons(
    provider: AiProvider,
    controller: AiAssistantController,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Int?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        provider.keyPageUrl?.let { url ->
            Button(
                onClick = { notice = if (openExternalPage(context, url)) null else R.string.navigation_no_browser },
                modifier = Modifier.testTag("ai_get_key"),
            ) { Text(stringResource(if (provider.freeKeys) R.string.ai_get_free_key else R.string.ai_get_key)) }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val text =
                        clipboard
                            .getClipEntry()
                            ?.clipData
                            ?.firstText(context)
                            .orEmpty()
                    notice =
                        when {
                            text.isBlank() -> R.string.ai_key_clipboard_empty
                            // A Tavily key pasted here is a search key, not an AI service's.
                            controller.saveTavilyKey(text) -> R.string.ai_search_tavily_saved
                            controller.saveKey(text) == null -> R.string.ai_key_not_a_key
                            else -> null
                        }
                }
            },
            modifier = Modifier.testTag("ai_paste_key"),
        ) { Text(stringResource(R.string.ai_paste_key)) }
    }
    notice?.let {
        val color = if (it == R.string.ai_search_tavily_saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Text(stringResource(it), color = color, style = MaterialTheme.typography.bodySmall)
    }
}

private fun ClipData.firstText(context: android.content.Context): String? =
    if (itemCount >
        0
    ) {
        getItemAt(0).coerceToText(context)?.toString()
    } else {
        null
    }

/** The sections under the AI service row; one is open at a time, like More settings. */
private enum class AiSettingsSection(
    val key: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    KEYS("keys", R.string.ai_settings_keys_section, Icons.Default.Key),
    MODEL("model", R.string.ai_settings_model_section, Icons.Default.Tune),
    SEARCH("search", R.string.ai_search_section, Icons.Default.TravelExplore),
    MEMORY("memory", R.string.ai_memory_title, Icons.Default.Psychology),
    HISTORY("history", R.string.ai_history, Icons.Default.History),
    PERMISSIONS("permissions", R.string.ai_settings_permissions_section, Icons.Default.Shield),
}

/**
 * More settings → AI assistant, and the settings page inside the panel: the on/off switch, the AI service row (which service's
 * settings are shown below), the Chat AI Service row (which service chat uses), then sections that open one at a time. [nested] is
 * true inside a More settings card, so the sections take a lighter color. [connectionTarget] opens the sections on that service
 * (Captions & translation → Manage AI connection); neither row changes the subtitle translation service, which is set in
 * Captions & translation.
 */
@Composable
internal fun AiAssistantSettingsSection(
    host: AiAssistantHost,
    nested: Boolean = false,
    connectionTarget: AiConnectionTarget? = null,
    chatOnly: Boolean = false,
) {
    val controller = host.controller
    val aiState by controller.state.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(R.string.ai_settings_enabled_title),
        description = stringResource(R.string.ai_settings_enabled_description),
        checked = aiState.settings.enabled,
        onCheckedChange = controller::setEnabled,
        testTag = "ai_enabled_switch",
    )
    if (!aiState.settings.enabled) return
    SettingsSectionDivider()
    val chatTarget = if (aiState.settings.chatGptService) AiConnectionTarget.ChatGpt else AiConnectionTarget.Key(aiState.settings.provider)
    val hasChatGpt = host.chatGpt != null
    // The service whose settings are shown: the chat's until another is picked here, or opened from Captions & translation.
    var shownId by rememberSaveable(connectionTarget?.id) { mutableStateOf(connectionTarget?.id) }
    val target = aiConnectionTargetFor(shownId)?.takeIf { it != AiConnectionTarget.ChatGpt || hasChatGpt } ?: chatTarget
    if (!chatOnly) {
        SettingsSubheading(stringResource(R.string.ai_settings_service))
        AiServiceMenu(aiState, target, onSelect = { shownId = it.id }, showChatGpt = hasChatGpt)
        SettingsHint(stringResource(R.string.ai_service_viewing_hint))
        if (target == AiConnectionTarget.Key(AiProvider.CUSTOM)) AiCustomAddressField(aiState, controller)
        Spacer(Modifier.height(12.dp))
    }
    // In the chat's own settings only the services that are configured are offered, and the one chat uses now is always among them.
    val gptState by (host.chatGpt?.state ?: remember { MutableStateFlow(ChatGptUiState()) }).collectAsStateWithLifecycle()
    val offered: (AiConnectionTarget) -> Boolean = { service ->
        !chatOnly || service == chatTarget ||
            when (service) {
                AiConnectionTarget.ChatGpt -> gptState.status == ChatGptStatus.READY
                is AiConnectionTarget.Key -> aiState.keys[service.provider].orEmpty().isNotEmpty()
            }
    }
    SettingsSubheading(stringResource(R.string.ai_chat_service))
    AiServiceMenu(
        aiState,
        chatTarget,
        offered = offered,
        onSelect = {
            when (it) {
                AiConnectionTarget.ChatGpt -> controller.selectChatGptService()
                is AiConnectionTarget.Key -> controller.selectProvider(it.provider)
            }
        },
        tag = "ai_chat_service",
        showChatGpt = hasChatGpt,
        chatGptNoteRes = R.string.ai_service_chat_unsupported,
    )
    if (chatTarget == AiConnectionTarget.ChatGpt) {
        Box(Modifier.testTag("ai_chatgpt_chat_unavailable")) { SettingsHint(stringResource(R.string.ai_chat_chatgpt_unavailable)) }
    }
    if (chatOnly) SettingsHint(stringResource(R.string.ai_chat_gear_hint))
    Spacer(Modifier.height(12.dp))
    // Without a key, the connection section starts open: adding one is the next step.
    var open by rememberSaveable {
        mutableStateOf(
            if (!chatOnly && (connectionTarget != null || (target is AiConnectionTarget.Key && aiState.keys[target.provider].isNullOrEmpty()))) {
                AiSettingsSection.KEYS.key
            } else {
                null
            },
        )
    }
    val color = if (nested) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Keys, sign-in, model and thinking are managed in Settings → AI Connection only, never in the chat's own settings.
        AiSettingsSection.entries.filter { !chatOnly || (it != AiSettingsSection.KEYS && it != AiSettingsSection.MODEL) }.forEach { section ->
            SettingsExpandableCard(
                title = stringResource(if (section == AiSettingsSection.KEYS && target is AiConnectionTarget.ChatGpt) R.string.ai_connection_title else section.titleRes),
                summary =
                    when (section) {
                        AiSettingsSection.KEYS -> aiConnectionSummary(target, aiState, host.chatGpt)
                        AiSettingsSection.MODEL -> aiModelSummary(target, aiState, host.chatGpt)
                        else -> aiSectionSummary(section, aiState)
                    },
                icon = section.icon,
                expanded = open == section.key,
                onToggle = { open = if (open == section.key) null else section.key },
                testTag = "ai_section_${section.key}",
                color = color,
            ) {
                when (section) {
                    AiSettingsSection.KEYS -> AiConnectionBody(target, aiState, controller, host.chatGpt)
                    AiSettingsSection.MODEL -> AiModelSettings(host, aiState, controller, target)
                    AiSettingsSection.SEARCH -> AiSearchSettings(aiState, controller)
                    AiSettingsSection.MEMORY -> AiMemorySettings(aiState, controller)
                    AiSettingsSection.HISTORY -> AiHistorySettings(aiState, controller)
                    AiSettingsSection.PERMISSIONS -> AiPermissionSettings(aiState, controller)
                }
            }
        }
    }
}

/** The Model and Thinking card's one line for the service shown: its model, and its thinking level where it has one. */
@Composable
private fun aiModelSummary(
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
private fun aiConnectionSummary(
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
                if (count == 0) stringResource(R.string.ai_settings_keys_none) else pluralStringResource(R.plurals.ai_settings_keys_count, count, count)
            }
    }

/** One line under each section's title: how many keys, the model, memory, history and access as they are now. */
@Composable
private fun aiSectionSummary(
    section: AiSettingsSection,
    aiState: AiAssistantUiState,
): String {
    val settings = aiState.settings
    val on = stringResource(R.string.ai_action_on)
    val off = stringResource(R.string.ai_action_off)
    val onOff = { enabled: Boolean -> if (enabled) on else off }
    return when (section) {
        AiSettingsSection.KEYS ->
            aiState.currentKeys.size.let { count ->
                if (count == 0) {
                    stringResource(R.string.ai_settings_keys_none)
                } else {
                    pluralStringResource(R.plurals.ai_settings_keys_count, count, count)
                }
            }
        AiSettingsSection.MODEL ->
            settings.modelFor(settings.provider).ifEmpty { stringResource(R.string.ai_settings_model) } + " · " +
                stringResource(R.string.ai_effort_button, stringResource(settings.thinking.labelRes))
        AiSettingsSection.SEARCH -> aiSearchSummary(aiState)
        AiSettingsSection.MEMORY ->
            stringResource(R.string.ai_action_setting, stringResource(R.string.ai_memory_enabled_title), onOff(settings.memoryEnabled))
        AiSettingsSection.HISTORY ->
            stringResource(
                R.string.ai_action_setting,
                stringResource(R.string.ai_settings_history_title),
                stringResource(settings.historyRetention.labelRes),
            )
        AiSettingsSection.PERMISSIONS ->
            stringResource(R.string.ai_action_setting, stringResource(R.string.ai_settings_full_access_title), onOff(settings.fullAccess))
    }
}

/**
 * One service in one row; tapping it lists every service with what it costs and whether a key is saved. [selected] is the service
 * the row stands for, [onSelect] what choosing another does, [tag] the prefix of its test tags, and [chatGptNoteRes] what the
 * ChatGPT Subscription row says about itself in this list.
 */
@Composable
private fun AiServiceMenu(
    aiState: AiAssistantUiState,
    selected: AiConnectionTarget,
    onSelect: (AiConnectionTarget) -> Unit,
    tag: String = "ai_service",
    showChatGpt: Boolean = true,
    @StringRes chatGptNoteRes: Int = R.string.ai_service_chatgpt_note,
    offered: (AiConnectionTarget) -> Boolean = { true },
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProvider = (selected as? AiConnectionTarget.Key)?.provider
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("${tag}_menu"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(aiConnectionLabel(selected)), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(selectedProvider?.noteRes ?: chatGptNoteRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.ai_settings_service_choose))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.testTag("${tag}_list")) {
            if (showChatGpt && offered(AiConnectionTarget.ChatGpt)) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.ai_service_chatgpt), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(chatGptNoteRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(AiConnectionTarget.ChatGpt)
                    },
                    trailingIcon = {
                        if (selected == AiConnectionTarget.ChatGpt) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.testTag("${tag}_chatgpt"),
                )
            }
            AiProvider.entries.filter { offered(AiConnectionTarget.Key(it)) }.forEach { provider ->
                val keys = aiState.keys[provider].orEmpty().size
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(provider.labelRes), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (keys > 0) {
                                    stringResource(provider.noteRes) + " · " +
                                        pluralStringResource(R.plurals.ai_settings_keys_count, keys, keys)
                                } else {
                                    stringResource(provider.noteRes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(AiConnectionTarget.Key(provider))
                    },
                    trailingIcon = {
                        if (provider == selectedProvider) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.testTag("${tag}_${provider.key}"),
                )
            }
        }
    }
}

@Composable
private fun AiCustomAddressField(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val address = aiState.settings.customBaseUrl
    OutlinedTextField(
        value = address,
        onValueChange = controller::setCustomBaseUrl,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_base_url"),
        label = { Text(stringResource(R.string.ai_settings_base_url)) },
        placeholder = { Text("https://example.com/v1") },
        singleLine = true,
        isError = address.isNotBlank() && chatCompletionsUrl(address) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

/** Each saved key with its state and Remove, then how to add another and Test all keys. */
@Composable
internal fun AiKeySettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    managing: AiProvider? = null,
) {
    val provider = aiState.settings.provider
    val keys = aiState.currentKeys
    keys.forEach { key -> AiKeyRow(aiState, key, onRemove = { controller.removeKey(provider, key.id) }) }
    if (keys.isNotEmpty()) {
        OutlinedButton(
            onClick = { managing?.let(controller::testConnection) ?: controller.testConnection() },
            enabled = aiState.connectionTest != AiConnectionTest.Testing,
            modifier = Modifier.padding(top = 4.dp).testTag("ai_test_connection"),
        ) { Text(stringResource(if (keys.size > 1) R.string.ai_settings_test_all else R.string.ai_settings_test)) }
        SettingsHint(stringResource(R.string.ai_settings_keys_fallback))
        SettingsSectionDivider()
    }
    when {
        keys.size >= MAX_AI_KEYS_PER_SERVICE -> SettingsHint(stringResource(R.string.ai_settings_keys_full, MAX_AI_KEYS_PER_SERVICE))
        keys.isEmpty() -> {
            AiSetupSteps(aiState, controller)
            AiTypedKeyField(controller, managing)
        }
        else -> {
            Text(stringResource(R.string.ai_settings_add_key), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            AiKeyButtons(provider, controller)
            AiTypedKeyField(controller, managing)
        }
    }
    if (provider == AiProvider.GEMINI) SettingsHint(stringResource(R.string.ai_settings_gemini_note))
}

/** "•••• fpzA", what its last check said, and Remove. */
@Composable
private fun AiKeyRow(
    aiState: AiAssistantUiState,
    key: AiSavedKey,
    onRemove: () -> Unit,
) {
    val test = aiState.keyTests[key.id]
    val failure = (test as? AiConnectionTest.Failed)?.failure
    val checked = key.checkedSetup == aiCheckedSetup(aiState.settings, aiState.settings.provider)
    val inUse = aiState.keyInUse(aiState.settings.provider) == key.id
    val status =
        when {
            test == AiConnectionTest.Testing -> stringResource(R.string.ai_checking)
            failure != null -> stringResource(failure.kind.messageRes())
            checked && inUse && aiState.currentKeys.size > 1 -> stringResource(R.string.ai_settings_key_in_use)
            checked && aiState.currentKeys.size > 1 -> stringResource(R.string.ai_settings_key_works)
            checked -> stringResource(R.string.ai_settings_test_passed)
            else -> stringResource(R.string.ai_settings_not_checked)
        }
    val color =
        when {
            failure != null -> MaterialTheme.colorScheme.error
            test == AiConnectionTest.Testing || checked -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("ai_key_row"), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_settings_key_ending, key.hint), modifier = Modifier.testTag("ai_key_saved"))
            Text(status, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_test_result"))
            failure?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        IconButton(onClick = onRemove, modifier = Modifier.testTag("ai_remove_key")) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ai_settings_remove_key))
        }
    }
}

@Composable
private fun AiTypedKeyField(
    controller: AiAssistantController,
    managing: AiProvider? = null,
) {
    var typed by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            invalid = false
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_key_field"),
        label = { Text(stringResource(R.string.ai_settings_key_label)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.ai_key_not_a_key)) }) else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    TextButton(
        onClick = {
            if (controller.saveTavilyKey(typed) || controller.saveKey(typed, managing) != null) typed = "" else invalid = true
        },
        enabled = typed.isNotBlank(),
        modifier = Modifier.testTag("ai_save_key"),
    ) { Text(stringResource(R.string.ai_settings_save_key)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiHistorySettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    var deleted by remember { mutableStateOf(false) }
    SettingsSubheading(stringResource(R.string.ai_settings_history_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatHistoryRetention.entries.forEach { retention ->
            FilterChip(
                selected = aiState.settings.historyRetention == retention,
                onClick = { controller.setHistoryRetention(retention) },
                label = { Text(stringResource(retention.labelRes)) },
                modifier = Modifier.testTag("ai_history_${retention.key}"),
            )
        }
    }
    SettingsHint(stringResource(R.string.ai_settings_history_description))
    OutlinedButton(
        onClick = {
            controller.deleteHistory()
            deleted = true
        },
        modifier = Modifier.padding(top = 8.dp).testTag("ai_delete_history"),
    ) { Text(stringResource(R.string.ai_settings_delete_history)) }
    if (deleted) SettingsHint(stringResource(R.string.ai_settings_history_deleted))
}

/**
 * The model typed for the service shown, the thinking level of that service, and the Available models list above the box. Chat and
 * subtitle translation read these same values for their assigned service; the chat's model menu picks from a list.
 */
@Composable
private fun AiModelSettings(
    host: AiAssistantHost,
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    target: AiConnectionTarget,
) {
    when (target) {
        is AiConnectionTarget.Key -> AiKeyedModelSettings(host, aiState, controller, target.provider)
        AiConnectionTarget.ChatGpt -> host.chatGpt?.let { AiChatGptModelSettings(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiKeyedModelSettings(
    host: AiAssistantHost,
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    provider: AiProvider,
) {
    val settings = aiState.settings
    val link = host.translation
    // Translation is told once a change is final, and only when it uses this service.
    val changed = { if (link != null && link.accessId() == provider.key) link.changed() }
    AiKeyedModelPicker(aiState, controller, provider) { id ->
        controller.setModel(provider, id)
        changed()
    }
    Spacer(Modifier.height(8.dp))
    var edited by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = settings.models[provider].orEmpty(),
        onValueChange = {
            controller.setModel(provider, it)
            edited = true
        },
        modifier =
            Modifier.fillMaxWidth().testTag("ai_model").onFocusChanged {
                if (!it.isFocused && edited) {
                    edited = false
                    changed()
                }
            },
        label = { Text(stringResource(R.string.ai_settings_model)) },
        placeholder = { Text(provider.defaultModel) },
        singleLine = true,
    )
    link
        ?.let { aiTranslationModelConflict(settings.models[provider], it.legacyModel(provider), provider in settings.unifiedModels) }
        ?.let { SettingsHint(stringResource(R.string.ai_models_translation_conflict, it)) }
    if (provider == AiProvider.OPENROUTER) SettingsHint(stringResource(R.string.ai_settings_openrouter_note))
    SettingsSubheading(stringResource(R.string.ai_settings_thinking_title))
    val thinking = settings.thinkingFor(provider)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiThinking.entries.forEach { level ->
            FilterChip(
                selected = thinking == level,
                onClick = {
                    controller.setThinking(provider, level)
                    changed()
                },
                label = { Text(stringResource(level.labelRes)) },
                modifier = Modifier.testTag("ai_settings_thinking_${level.key}"),
            )
        }
    }
    SettingsHint(stringResource(thinking.noteRes))
}

/** ChatGPT Subscription: its authorized model catalog above the model box. It has no adjustable thinking level. */
@Composable
private fun AiChatGptModelSettings(chatGpt: ChatGptActions) {
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
        if (id.isEmpty()) text = state.model else if (id != state.model) chatGpt.selectModel(id)
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

/** Full access: off by default, with what it allows and what still waits for a tap. */
@Composable
private fun AiPermissionSettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    SettingsSwitchRow(
        title = stringResource(R.string.ai_settings_full_access_title),
        description = stringResource(R.string.ai_settings_full_access_description),
        checked = aiState.settings.fullAccess,
        onCheckedChange = controller::setFullAccess,
        testTag = "ai_full_access_switch",
    )
    Text(
        stringResource(R.string.ai_settings_full_access_warning),
        style = MaterialTheme.typography.bodySmall,
        color = if (aiState.settings.fullAccess) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    SettingsHint(stringResource(R.string.ai_settings_permissions_default))
}
