package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiApiFamily
import com.kienhoang.dualsubreplay.assistant.AiAuthMethod
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiResponseSource
import com.kienhoang.dualsubreplay.assistant.AiResponseStatus
import com.kienhoang.dualsubreplay.assistant.CHATGPT_ACCESS_ID

@StringRes
private fun providerNameRes(id: String): Int =
    when {
        id == CHATGPT_ACCESS_ID -> R.string.ai_service_chatgpt
        else -> AiProvider.entries.firstOrNull { it.key == id }?.labelRes ?: R.string.ai_source_none
    }

@StringRes
private fun AiAuthMethod.labelRes() = if (this == AiAuthMethod.OAUTH) R.string.ai_source_auth_oauth else R.string.ai_source_auth_key

@StringRes
private fun AiApiFamily.labelRes() =
    when (this) {
        AiApiFamily.CHATGPT_RESPONSES -> R.string.ai_source_api_responses
        AiApiFamily.OPENAI_CHAT -> R.string.ai_source_api_chat
        AiApiFamily.ANTHROPIC_MESSAGES -> R.string.ai_source_api_messages
    }

@StringRes
private fun AiResponseStatus.labelRes() =
    when (this) {
        AiResponseStatus.COMPLETED -> R.string.ai_source_status_completed
        AiResponseStatus.FAILED -> R.string.ai_source_status_failed
        AiResponseStatus.CANCELLED -> R.string.ai_source_status_cancelled
        AiResponseStatus.INCOMPLETE -> R.string.ai_source_status_incomplete
    }

/**
 * Under an answer, when Settings → Show AI response source (debug) is on: who really produced it, in one line; tap for the details.
 * A failed, stopped or incomplete question says so in the line and is never styled as a success. [source] null is a message saved
 * before this was recorded. [key] keeps each message's open state apart.
 */
@Composable
internal fun AiResponseSourceLine(
    source: AiResponseSource?,
    key: String,
) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val problem = source != null && !source.successful
    val color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag("ai_source")) {
        Text(
            sourceHeadline(source),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier
                    .heightIn(min = 32.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .padding(vertical = 6.dp)
                    .testTag("ai_source_line"),
        )
        if (open) {
            Column(
                Modifier.padding(start = 8.dp, bottom = 4.dp).testTag("ai_source_details"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.ai_source_title), style = MaterialTheme.typography.labelLarge)
                if (source == null) {
                    Fact(R.string.ai_source_not_recorded_detail)
                } else {
                    SourceFacts(source)
                }
            }
        }
    }
}

@Composable
private fun sourceHeadline(source: AiResponseSource?): String {
    if (source == null) return stringResource(R.string.ai_source_not_recorded)
    val parts = mutableListOf<String>()
    parts += stringResource(providerNameRes(source.providerId))
    source.auth?.let { parts += stringResource(it.labelRes()) }
    if (!source.network) parts += stringResource(R.string.ai_source_no_request)
    if (source.status != AiResponseStatus.COMPLETED) parts += stringResource(source.status.labelRes())
    return parts.joinToString(" · ")
}

@Composable
private fun SourceFacts(source: AiResponseSource) {
    Fact(R.string.ai_source_actual_provider, stringResource(providerNameRes(source.providerId)))
    Fact(R.string.ai_source_selected, stringResource(providerNameRes(source.selectedProviderId)))
    source.auth?.let { Fact(R.string.ai_source_auth, stringResource(it.labelRes())) }
    source.api?.let { Fact(R.string.ai_source_api, stringResource(it.labelRes())) }
    Fact(R.string.ai_source_requested_model, source.requestedModel.ifEmpty { stringResource(R.string.ai_source_not_reported) })
    Fact(R.string.ai_source_reported_model, source.reportedModel ?: stringResource(R.string.ai_source_not_reported))
    Fact(R.string.ai_source_status, stringResource(source.status.labelRes()))
    Fact(R.string.ai_source_fallback, stringResource(if (source.fallback) R.string.ai_source_yes else R.string.ai_source_no))
    Fact(R.string.ai_source_requests, source.requests.toString())
    if (source.providerId == CHATGPT_ACCESS_ID && source.network) {
        Fact(
            R.string.ai_source_plan,
            stringResource(
                if (source.planUsageVerified ==
                    true
                ) {
                    R.string.ai_source_plan_checked
                } else {
                    R.string.ai_source_plan_unchecked
                },
            ),
        )
    }
}

@Composable
private fun Fact(
    @StringRes label: Int,
    value: String? = null,
) {
    Text(
        if (value == null) stringResource(label) else stringResource(R.string.ai_source_fact, stringResource(label), value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
