package com.kienhoang.dualsubreplay.assistant

import androidx.annotation.StringRes
import com.kienhoang.dualsubreplay.R

/** Settings → Captions & translation → AI provider; empty follows the AI assistant's service (the behavior before this choice existed). */
internal const val AI_TRANSLATION_PROVIDER_PREFERENCE = "ai_translation_provider"
private const val MODEL_PREFIX = "ai_translation_model_"
private const val EFFORT_PREFIX = "ai_translation_effort_"

/** The id of ChatGPT Subscription; the API providers use [AiProvider.key]. */
internal const val CHATGPT_ACCESS_ID = "chatgpt"

/** One row of the AI provider dropdown. */
internal data class AiAccessOption(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int,
) {
    /** The API provider behind this option, or null for ChatGPT Subscription. */
    val provider: AiProvider? get() = AiProvider.entries.firstOrNull { it.key == id }
}

/**
 * Everything the AI provider dropdown offers, in order: ChatGPT Subscription (the only subscription sign-in), then the API providers.
 */
internal fun aiAccessOptions(): List<AiAccessOption> =
    listOf(
        AiAccessOption(CHATGPT_ACCESS_ID, R.string.ai_access_chatgpt_subscription, R.string.ai_access_chatgpt_note),
        AiAccessOption(AiProvider.GEMINI.key, R.string.ai_access_gemini_api, R.string.ai_access_gemini_note),
        AiAccessOption(AiProvider.CLAUDE.key, R.string.ai_access_claude_api, R.string.ai_access_claude_note),
        AiAccessOption(AiProvider.OPENAI.key, AiProvider.OPENAI.labelRes, AiProvider.OPENAI.noteRes),
        AiAccessOption(AiProvider.OPENROUTER.key, AiProvider.OPENROUTER.labelRes, AiProvider.OPENROUTER.noteRes),
        AiAccessOption(AiProvider.OPENCODE_ZEN.key, AiProvider.OPENCODE_ZEN.labelRes, AiProvider.OPENCODE_ZEN.noteRes),
        AiAccessOption(AiProvider.OPENCODE_GO.key, AiProvider.OPENCODE_GO.labelRes, AiProvider.OPENCODE_GO.noteRes),
        AiAccessOption(AiProvider.CUSTOM.key, AiProvider.CUSTOM.labelRes, AiProvider.CUSTOM.noteRes),
    )

/** The option for [id], or null for an id this build does not know (a saved value from a newer or older build). */
internal fun aiAccessOption(id: String): AiAccessOption? = aiAccessOptions().firstOrNull { it.id == id }

/**
 * How long the model thinks, as the user picks it. [AUTO] lets the app choose what suits subtitles ([aiTranslationEffort]);
 * the other levels are sent as [wire] and only where the model supports them ([aiEffortOptions]).
 */
internal enum class AiEffort(
    val key: String,
    @StringRes val labelRes: Int,
    val wire: String?,
) {
    AUTO("auto", R.string.ai_translation_effort_auto, null),
    OFF("none", R.string.ai_translation_effort_off, "none"),
    MINIMAL("minimal", R.string.ai_translation_effort_minimal, "minimal"),
    LOW("low", R.string.ai_translation_effort_low, "low"),
    MEDIUM("medium", R.string.ai_translation_effort_medium, "medium"),
    HIGH("high", R.string.ai_translation_effort_high, "high"),
    XHIGH("xhigh", R.string.ai_translation_effort_xhigh, "xhigh"),
    MAX("max", R.string.ai_translation_effort_max, "max"),
}

internal fun storedAiEffort(raw: String?): AiEffort = AiEffort.entries.firstOrNull { it.key == raw } ?: AiEffort.AUTO

private val SHARED_LEVELS = listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH)

/**
 * The effort levels worth offering for [accessId] and [modelId], so an unsupported value is never even selectable.
 * [claudeLevels] are the levels Claude's model list reported for the model; null (not listed) leaves only Automatic.
 */
internal fun aiEffortOptions(
    accessId: String,
    modelId: String,
    claudeLevels: Set<String>? = null,
): List<AiEffort> {
    val id = modelId.lowercase()
    return when (accessId) {
        AiProvider.CLAUDE.key ->
            listOf(AiEffort.AUTO) +
                listOf(AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH, AiEffort.XHIGH, AiEffort.MAX).filter {
                    it.wire in
                        claudeLevels.orEmpty()
                }
        AiProvider.GEMINI.key ->
            when {
                // Gemini 3 names its levels; its lowest is "minimal".
                "gemini-3" in id -> listOf(AiEffort.AUTO, AiEffort.MINIMAL, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH)
                // Gemini 2.5 can switch thinking off, except Pro.
                "gemini-2.5" in id && "-pro" !in id -> listOf(AiEffort.AUTO, AiEffort.OFF, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH)
                else -> SHARED_LEVELS
            }
        AiProvider.OPENAI.key, AiProvider.OPENROUTER.key -> SHARED_LEVELS
        // ChatGPT Subscription: OpenAI's plan-usage preview does not document `reasoning`, so nothing is sent.
        // OpenCode and custom services have no documented contract.
        else -> listOf(AiEffort.AUTO)
    }
}

/** The saved level when [options] still has it, else Automatic; the saved choice itself is never erased by this. */
internal fun effectiveAiEffort(
    saved: AiEffort,
    options: List<AiEffort>,
): AiEffort = saved.takeIf { it in options } ?: AiEffort.AUTO

/** What Automatic sends for [provider] and [modelId]: thinking off where the model can (fast, and free limits count thinking tokens). */
internal fun aiTranslationEffort(
    provider: AiProvider,
    modelId: String = "",
): String? {
    val id = modelId.lowercase()
    return when (provider) {
        // Pro models and Gemini 3 cannot turn thinking off (also behind an alias such as gemini-pro-latest), so nothing is forced on them.
        AiProvider.GEMINI -> if ("-pro" in id || "gemini-3" in id) null else "none"
        AiProvider.OPENAI -> "minimal"
        else -> null
    }
}

/** The `reasoning_effort` / `output_config.effort` value to send, or null to send nothing. */
internal fun aiEffortWire(
    provider: AiProvider,
    modelId: String,
    effort: AiEffort,
): String? = if (effort == AiEffort.AUTO) aiTranslationEffort(provider, modelId) else effort.wire

/** The cache scope of a keyed model: service and model, plus the effort when one is chosen (Automatic keeps the old scope). */
internal fun aiTranslationScope(
    provider: AiProvider,
    modelId: String,
    effort: AiEffort,
): String = "${provider.key}/$modelId" + if (effort == AiEffort.AUTO) "" else "@${effort.key}"

/** Every preference key the choices use, so "Reset all settings" clears them. */
internal fun aiTranslationChoiceKeys(): List<String> =
    listOf(AI_TRANSLATION_PROVIDER_PREFERENCE) +
        (listOf(CHATGPT_ACCESS_ID) + AiProvider.entries.map { it.key }).flatMap { listOf(MODEL_PREFIX + it, EFFORT_PREFIX + it) }

/** A tiny key/value view of the app preferences, so the choices can be tested without Android. */
internal interface AiTranslationPrefs {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String?,
    )
}

/**
 * The saved provider, model and effort, kept per provider so switching away and back returns each one's choices.
 * The older `ai_translation_model` is the model of the AI assistant's service until a per-provider one is saved.
 */
internal class AiTranslationChoices(
    private val prefs: AiTranslationPrefs,
    private val chatGptSelected: () -> Boolean,
) {
    /** ChatGPT Subscription when chosen (its own switch), else the saved API provider, else the assistant's service. */
    fun accessId(assistant: AiProvider): String {
        if (chatGptSelected()) return CHATGPT_ACCESS_ID
        val saved = prefs.get(AI_TRANSLATION_PROVIDER_PREFERENCE)
        return AiProvider.entries.firstOrNull { it.key == saved }?.key ?: assistant.key
    }

    fun saveProvider(id: String) = prefs.put(AI_TRANSLATION_PROVIDER_PREFERENCE, id)

    /** The saved model id for [id], or empty when none is saved (the caller uses its default). */
    fun model(
        id: String,
        assistant: AiProvider,
    ): String =
        prefs.get(MODEL_PREFIX + id)?.trim().orEmpty().ifEmpty {
            // The older setting was picked from the assistant's service's suggestions; another service does not inherit it.
            prefs
                .get(LEGACY_MODEL_PREFERENCE)
                ?.trim()
                .orEmpty()
                .takeIf { id == assistant.key && it in assistant.suggestedModels }
                .orEmpty()
        }

    fun saveModel(
        id: String,
        model: String,
    ) = prefs.put(MODEL_PREFIX + id, model)

    fun effort(id: String): AiEffort = storedAiEffort(prefs.get(EFFORT_PREFIX + id))

    fun saveEffort(
        id: String,
        effort: AiEffort,
    ) = prefs.put(EFFORT_PREFIX + id, effort.key)

    private companion object {
        const val LEGACY_MODEL_PREFERENCE = AI_TRANSLATION_MODEL_PREFERENCE
    }
}
