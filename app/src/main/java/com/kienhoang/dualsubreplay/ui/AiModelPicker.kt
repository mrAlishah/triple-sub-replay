package com.kienhoang.dualsubreplay.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiModelList
import com.kienhoang.dualsubreplay.assistant.AiProvider

/** What the Available models field shows for the text box's value; it never decides the model. */
internal sealed interface AiModelPickerValue {
    data object Empty : AiModelPickerValue

    data class Listed(
        val model: AiModelChoice,
    ) : AiModelPickerValue

    /** Typed, and not (or not yet) in the list. */
    data object Custom : AiModelPickerValue
}

/** The picker's item is derived from the typed [model]; the text box stays the only saved value. */
internal fun aiModelPickerValue(
    model: String,
    models: List<AiModelChoice>,
): AiModelPickerValue =
    when {
        model.isBlank() -> AiModelPickerValue.Empty
        else -> models.firstOrNull { it.id == model.trim() }?.let(AiModelPickerValue::Listed) ?: AiModelPickerValue.Custom
    }

/** What the service said about its model list, in the words the picker needs. */
internal enum class AiPickerState { NEEDS_CONNECTION, LOADING, FAILED, READY }

/**
 * Available models: a dropdown above the Model text box. Choosing one hands its exact id to [onPick], which writes it into the
 * text box's own setting. Loading, refreshing or failing never writes the text box, and a typed id that is not listed stays as typed.
 */
@Composable
internal fun AiModelPicker(
    typed: String,
    models: List<AiModelChoice>,
    state: AiPickerState,
    connectMessage: Int,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val value = aiModelPickerValue(typed, models)
    val shown =
        when (value) {
            AiModelPickerValue.Empty -> ""
            is AiModelPickerValue.Listed -> value.model.name
            AiModelPickerValue.Custom -> stringResource(R.string.ai_models_custom)
        }
    val supporting =
        when {
            state == AiPickerState.NEEDS_CONNECTION -> stringResource(connectMessage)
            state == AiPickerState.LOADING -> stringResource(R.string.ai_models_loading)
            state == AiPickerState.FAILED -> stringResource(R.string.ai_models_picker_failed)
            models.isEmpty() -> stringResource(R.string.ai_models_picker_empty)
            value == AiModelPickerValue.Custom -> stringResource(R.string.ai_models_not_in_list)
            else -> null
        }
    DropdownField(
        label = stringResource(R.string.ai_models_available),
        value = shown,
        enabled = state != AiPickerState.NEEDS_CONNECTION,
        tag = "ai_models_picker",
        supporting = supporting,
    ) { close ->
        models.forEach { model ->
            val chosen = model.id == typed.trim()
            DropdownMenuItem(
                text = { Text(model.name) },
                onClick = {
                    close()
                    onPick(model.id)
                },
                leadingIcon = { SelectedMark(chosen) },
                modifier = Modifier.semantics { selected = chosen }.testTag("ai_models_picker_${model.id}"),
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ai_models_refresh)) },
            onClick = {
                close()
                onRefresh()
            },
            modifier = Modifier.testTag("ai_models_picker_refresh"),
        )
    }
}

/** Available models for an API service, read through the assistant's own model lister. */
@Composable
internal fun AiKeyedModelPicker(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    provider: AiProvider,
    onPick: (String) -> Unit,
) {
    val list = aiState.modelList
    val loaded = (list as? AiModelList.Loaded)?.takeIf { it.provider == provider }
    val hasKey = aiState.keys[provider].orEmpty().isNotEmpty()
    // Without a key the service refuses the list, so nothing is asked; the text box stays usable.
    LaunchedEffect(provider, hasKey) { if (hasKey && loaded == null && list != AiModelList.Loading) controller.loadModels(provider) }
    val state =
        when {
            !hasKey -> AiPickerState.NEEDS_CONNECTION
            list == AiModelList.Loading -> AiPickerState.LOADING
            list is AiModelList.Failed -> AiPickerState.FAILED
            else -> AiPickerState.READY
        }
    AiModelPicker(
        typed = aiState.settings.models[provider].orEmpty(),
        models = loaded?.models.orEmpty().map { AiModelChoice(it.id, it.name ?: it.id) },
        state = state,
        connectMessage = R.string.ai_models_connect,
        onPick = onPick,
        onRefresh = { controller.loadModels(provider) },
    )
}
