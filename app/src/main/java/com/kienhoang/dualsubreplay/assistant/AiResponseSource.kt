package com.kienhoang.dualsubreplay.assistant

import org.json.JSONObject

/** How a request was authorized. */
internal enum class AiAuthMethod(
    val key: String,
) {
    OAUTH("oauth"),
    API_KEY("api_key"),
}

/** The API a request was written for. */
internal enum class AiApiFamily(
    val key: String,
) {
    OPENAI_CHAT("openai_chat"),
    ANTHROPIC_MESSAGES("anthropic_messages"),
    CHATGPT_RESPONSES("chatgpt_responses"),
}

/** How a question ended. Only [COMPLETED] is a finished answer. */
internal enum class AiResponseStatus(
    val key: String,
) {
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),

    /** The reply was cut short or could not be read. */
    INCOMPLETE("incomplete"),
}

/**
 * What the transport that ran a request says about it: which provider's credentials and route it used and the model it sent.
 * It carries no key, token, header or body. [planUsageVerified] is only ever set for ChatGPT Subscription, and only when the
 * plan-usage permission was checked before the request was sent.
 */
internal data class AiExecution(
    val providerId: String,
    val auth: AiAuthMethod,
    val api: AiApiFamily,
    val requestedModel: String,
    val planUsageVerified: Boolean? = null,
)

/** The API family a wire format is. */
internal fun aiApiFamily(format: AiWireFormat): AiApiFamily =
    when (format) {
        AiWireFormat.OPENAI_CHAT -> AiApiFamily.OPENAI_CHAT
        AiWireFormat.ANTHROPIC_MESSAGES -> AiApiFamily.ANTHROPIC_MESSAGES
        AiWireFormat.CHATGPT_RESPONSES -> AiApiFamily.CHATGPT_RESPONSES
    }

/**
 * Where an assistant message really came from, kept with the message. The provider, authentication, API and requested model come
 * from the transport that ran the request ([AiExecution]); [selectedProviderId] is only the Chat AI Service at the time, kept to
 * compare. A source without [auth] means no request left the phone.
 */
internal data class AiResponseSource(
    /** The provider that ran the request: an [AiProvider] key or [CHATGPT_ACCESS_ID]; empty when none did. */
    val providerId: String,
    val auth: AiAuthMethod?,
    val api: AiApiFamily?,
    val requestedModel: String,
    /** The model the server named in its reply; null when it named none. Never the requested model. */
    val reportedModel: String?,
    val status: AiResponseStatus,
    val selectedProviderId: String,
    /** How many requests to the provider produced this message (more than one when it used actions). */
    val requests: Int,
    val planUsageVerified: Boolean?,
) {
    /** A provider other than the selected one answered. Nothing falls back by itself; this reports it if it ever happens. */
    val fallback: Boolean get() = providerId.isNotEmpty() && providerId != selectedProviderId

    /** A request actually left the phone. */
    val network: Boolean get() = auth != null

    /** A finished answer from a real request: the only case shown without a warning. */
    val successful: Boolean get() = status == AiResponseStatus.COMPLETED && network
}

/** The stored form: ids, enum keys, model names and counts only. */
internal fun encodeAiResponseSource(source: AiResponseSource): JSONObject =
    JSONObject()
        .put("provider", source.providerId)
        .put("requested", source.requestedModel)
        .put("status", source.status.key)
        .put("selected", source.selectedProviderId)
        .put("requests", source.requests)
        .apply {
            source.auth?.let { put("auth", it.key) }
            source.api?.let { put("api", it.key) }
            source.reportedModel?.let { put("reported", it) }
            source.planUsageVerified?.let { put("plan", it) }
        }

/** Reads [encodeAiResponseSource]; anything unreadable is null, shown as "not recorded", never guessed. */
internal fun decodeAiResponseSource(json: JSONObject?): AiResponseSource? {
    if (json == null || !json.has("provider")) return null
    val status = AiResponseStatus.entries.firstOrNull { it.key == json.optString("status") } ?: return null
    return AiResponseSource(
        providerId = json.optString("provider"),
        auth = AiAuthMethod.entries.firstOrNull { it.key == json.optString("auth") },
        api = AiApiFamily.entries.firstOrNull { it.key == json.optString("api") },
        requestedModel = json.optString("requested"),
        reportedModel = json.optString("reported").takeIf { json.has("reported") },
        status = status,
        selectedProviderId = json.optString("selected"),
        requests = json.optInt("requests", 1),
        planUsageVerified = if (json.has("plan")) json.optBoolean("plan") else null,
    )
}
