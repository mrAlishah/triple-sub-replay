package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiChatException
import com.kienhoang.dualsubreplay.assistant.AiEffort
import com.kienhoang.dualsubreplay.assistant.AiErrorKind
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiModelInfo
import com.kienhoang.dualsubreplay.assistant.AiModelLister
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiTranslationChoices
import com.kienhoang.dualsubreplay.assistant.AiTranslationPrefs
import com.kienhoang.dualsubreplay.assistant.CHATGPT_ACCESS_ID
import com.kienhoang.dualsubreplay.assistant.StoredAiKey
import com.kienhoang.dualsubreplay.assistant.aiAccessOption
import com.kienhoang.dualsubreplay.assistant.aiEffortOptions
import com.kienhoang.dualsubreplay.assistant.aiEffortWire
import com.kienhoang.dualsubreplay.assistant.aiModelCanChat
import com.kienhoang.dualsubreplay.assistant.aiTranslationModelChoice
import com.kienhoang.dualsubreplay.assistant.aiTranslationSavedEffort
import com.kienhoang.dualsubreplay.assistant.effectiveAiEffort
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSubscription
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import com.kienhoang.dualsubreplay.chatgpt.TEST_INSTRUCTIONS
import com.kienhoang.dualsubreplay.chatgpt.TEST_SENTENCE
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.translation.aiTranslationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the AI model dropdown can show. */
internal enum class AiModelsState {
    /** Nothing asked yet. */
    IDLE,
    LOADING,
    LOADED,

    /** The service answered with no model this app can use. */
    EMPTY,
    FAILED,

    /** No key (API providers) or no signed-in account (ChatGPT): the list is the suggestions or empty. */
    NEEDS_CONNECTION,
}

internal data class AiModelChoice(
    val id: String,
    val name: String,
)

/** The last Test translation: who answered (as verified from the model that answered), or why it did not. */
internal data class AiTestOutcome(
    val accessId: String,
    val model: String,
    val effort: AiEffort,
    val text: String? = null,
    val origin: TranslationOrigin? = null,
    @StringRes val errorRes: Int? = null,
)

internal data class AiTranslationUiState(
    val accessId: String,
    /** The model that translates now: the saved one, or a safe current one when that disappeared. */
    val model: String,
    val models: List<AiModelChoice>,
    val modelsState: AiModelsState,
    /** The level the user picked, and the one in force (Automatic when the model does not take the picked one). */
    val savedEffort: AiEffort = AiEffort.AUTO,
    val effort: AiEffort = AiEffort.AUTO,
    val effortOptions: List<AiEffort> = listOf(AiEffort.AUTO),
    /** An API provider has a stored key. */
    val keyPresent: Boolean = false,
    /** Automatic sends a setting that keeps thinking short (Gemini, OpenAI); elsewhere it is the provider's default. */
    val automaticShortensThinking: Boolean = false,
    val testing: Boolean = false,
    val test: AiTestOutcome? = null,
    /** The services that can answer right now: an API service with a saved key, ChatGPT Subscription with plan usage allowed. */
    val connected: Set<String> = emptySet(),
) {
    val isChatGpt: Boolean get() = accessId == CHATGPT_ACCESS_ID
}

private class ModelsEntry(
    val state: AiModelsState,
    val infos: List<AiModelInfo> = emptyList(),
)

private data class Own(
    val models: Map<String, ModelsEntry> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val testing: Boolean = false,
    val test: AiTestOutcome? = null,
    /** Raised when a saved choice changed, so the state is read again. */
    val revision: Int = 0,
)

/**
 * The AI provider, model and effort for subtitle translation, as Settings shows them and as the translator uses them.
 * The provider is its own choice (it starts as the AI assistant's service, so nothing changes for existing users); the model and
 * effort are saved per provider. Credentials stay where they were: API keys in [AiKeyStore], the ChatGPT session in
 * [ChatGptSubscription]; nothing here ever holds a secret in state.
 */
internal class AiTranslationConfig(
    private val choices: AiTranslationChoices,
    private val assistant: () -> AiAssistantSettings,
    private val keyStore: AiKeyStore,
    private val lister: AiModelLister,
    private val chatGpt: ChatGptSubscription,
    private val scope: CoroutineScope,
    /** Builds the keyed model, or null without a key; the last flag keeps a refused effort setting from being dropped. */
    private val buildKeyed: (AiProvider, String, AiEffort, Boolean) -> AiTranslationModel?,
    /** The provider, model or effort changed: the playing video translates again. */
    private val onChanged: () -> Unit,
) {
    private val own = MutableStateFlow(Own())
    private val loadJobs = HashMap<String, Job>()
    private var testJob: Job? = null

    val state: StateFlow<AiTranslationUiState> =
        combine(own, chatGpt.state) { mine, gpt -> compose(mine, gpt) }
            .stateIn(scope, SharingStarted.Eagerly, compose(own.value, chatGpt.state.value))

    /** The state as it is right now (the flow above can lag one emission): what translates and what Test sends is read from this. */
    internal fun current(): AiTranslationUiState = compose(own.value, chatGpt.state.value)

    /** The provider id now in force: the saved choice, else the AI assistant's service, which it follows until the user chooses. */
    val accessId: String get() = current().accessId

    /** [accessId] for callers that need a function reference. */
    fun accessIdNow(): String = accessId

    /** The model saved for translation before the Model box was shared, or empty. */
    fun legacyModel(provider: AiProvider): String = choices.model(provider.key, assistant().provider)

    /** Picks a provider; an id this build does not know changes nothing. */
    fun selectAccess(id: String) {
        if (aiAccessOption(id) == null) return
        val before = current()
        if (id == before.accessId) {
            // Choosing the provider already in force still pins it, so it no longer follows the AI assistant's service.
            if (id != CHATGPT_ACCESS_ID) {
                choices.saveProvider(id)
                own.update { it.copy(revision = it.revision + 1) }
            }
            return
        }
        testJob?.cancel()
        if (id == CHATGPT_ACCESS_ID) {
            chatGpt.select(true)
        } else {
            choices.saveProvider(id)
            // Turning ChatGPT off reloads the video itself; an API provider change reloads it here.
            if (chatGpt.useSubscription) chatGpt.select(false) else onChanged()
        }
        own.update { it.copy(test = null, testing = false, revision = it.revision + 1) }
        ensureModels()
    }

    fun selectModel(id: String) {
        if (id.isBlank()) return
        val before = current()
        if (before.isChatGpt) {
            if (id != before.model) chatGpt.selectModel(id)
            return
        }
        // Saved even when it equals today's default, so a later change of the default does not move the user's choice.
        choices.saveModel(before.accessId, id)
        testJob?.cancel()
        own.update { it.copy(revision = it.revision + 1, test = null, testing = false) }
        if (id != before.model) onChanged()
    }

    fun selectEffort(effort: AiEffort) {
        val before = current()
        if (before.isChatGpt || effort !in before.effortOptions) return
        choices.saveEffort(before.accessId, effort)
        testJob?.cancel()
        own.update { it.copy(revision = it.revision + 1, test = null, testing = false) }
        if (current().effort != before.effort) onChanged()
    }

    /** Reads the model list of the chosen API provider once per run, or again when [force]d (the refresh row). */
    fun ensureModels(force: Boolean = false) {
        val provider = keyedProvider() ?: return
        val key = provider.key
        val known = own.value.models[key]
        if (!force && known != null && known.state != AiModelsState.FAILED && known.state != AiModelsState.NEEDS_CONNECTION) return
        if (key in own.value.loading) return
        val before = current()
        own.update { it.copy(loading = it.loading + key) }
        loadJobs[key] =
            scope.launch {
                var entry: ModelsEntry? = null
                try {
                    entry = loadModels(provider)
                } finally {
                    // Always cleared, also when this load is cancelled, so a later load or Refresh is never blocked.
                    own.update {
                        it.copy(
                            models = entry?.let { loaded -> it.models + (key to loaded) } ?: it.models,
                            loading = it.loading - key,
                            revision = it.revision + 1,
                        )
                    }
                }
                // The list can change what is in force (a vanished model, the levels a saved effort needs): translate again then.
                val after = current()
                if (after.accessId == before.accessId && (after.model != before.model || after.effort != before.effort)) onChanged()
            }
    }

    /**
     * The model or thinking level of the translation service was changed in Settings → AI Assistant: read the state again and
     * translate the playing video with what is in force now.
     */
    fun settingsChanged() {
        testJob?.cancel()
        own.update { it.copy(revision = it.revision + 1, test = null, testing = false) }
        onChanged()
    }

    /** Sign in with ChatGPT or read the model list again, whichever the provider has. */
    fun refresh() {
        if (accessId == CHATGPT_ACCESS_ID) chatGpt.refresh() else ensureModels(force = true)
    }

    /** One real request with the chosen provider, model and effort; never another provider. */
    fun test() {
        val current = current()
        if (current.testing) return
        if (current.isChatGpt) {
            chatGpt.test()
            return
        }
        val provider = keyedProvider() ?: return
        testJob?.cancel()
        own.update { it.copy(testing = true, test = null) }
        testJob =
            scope.launch {
                val outcome = runTest(provider, current)
                own.update { it.copy(testing = false, test = outcome) }
            }
    }

    /** The model subtitles are translated with, or null so Google or the device does (no key or session, no model). */
    fun translationModel(): AiTranslationModel? {
        val current = current()
        if (current.isChatGpt) return chatGpt.translationModel()
        val provider = keyedProvider() ?: return null
        return buildKeyed(provider, current.model, current.effort, true)
    }

    /** After "Reset all settings": the choices are gone, so the state starts again from the assistant's service. */
    fun resetChoice() {
        testJob?.cancel()
        loadJobs.values.forEach(Job::cancel)
        loadJobs.clear()
        own.update { Own(revision = it.revision + 1) }
    }

    private fun keyedProvider(): AiProvider? = aiAccessOption(current().accessId)?.provider

    private suspend fun runTest(
        provider: AiProvider,
        current: AiTranslationUiState,
    ): AiTestOutcome {
        val explicit = current.effort != AiEffort.AUTO

        fun failure(res: Int) = AiTestOutcome(current.accessId, current.model, current.effort, errorRes = res)
        val model =
            withContext(Dispatchers.IO) { buildKeyed(provider, current.model, current.effort, !explicit) }
                ?: return failure(R.string.ai_translation_test_no_key)
        return try {
            val text = model.ask(TEST_INSTRUCTIONS, TEST_SENTENCE).trim()
            AiTestOutcome(current.accessId, current.model, current.effort, text, aiTranslationOrigin(model.scope, cached = false))
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiChatException) {
            // A refused level is reported, not dropped: the saved choice stays and the user can pick another.
            val refused = explicit && error.kind in setOf(AiErrorKind.UNSUPPORTED_THINKING, AiErrorKind.BAD_REQUEST)
            failure(if (refused) R.string.ai_translation_test_effort_refused else error.kind.messageRes())
        } catch (_: IOException) {
            failure(R.string.ai_error_network)
        }
    }

    private suspend fun loadModels(provider: AiProvider): ModelsEntry {
        val key =
            withContext(Dispatchers.IO) {
                keyStore.keys(provider).firstNotNullOfOrNull { (keyStore.load(provider, it.id) as? StoredAiKey.Found)?.key }
            } ?: return ModelsEntry(AiModelsState.NEEDS_CONNECTION)
        return try {
            val infos = lister.listModels(assistant().baseUrlFor(provider), key, provider.format).filter { aiModelCanChat(provider, it.id) }
            ModelsEntry(if (infos.isEmpty()) AiModelsState.EMPTY else AiModelsState.LOADED, infos)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiChatException) {
            ModelsEntry(AiModelsState.FAILED)
        } catch (_: IOException) {
            ModelsEntry(AiModelsState.FAILED)
        }
    }

    private fun compose(
        mine: Own,
        gpt: ChatGptUiState,
    ): AiTranslationUiState {
        val settings = assistant()
        val id = choices.accessId(settings.provider)
        if (id == CHATGPT_ACCESS_ID) return composeChatGpt(gpt)
        val provider = aiAccessOption(id)?.provider ?: AiProvider.GEMINI
        val entry = mine.models[provider.key]
        val legacy = choices.model(provider.key, settings.provider)
        val default = settings.modelFor(provider)
        val wanted = aiTranslationModelChoice(settings.models[provider], legacy, provider in settings.unifiedModels, provider.defaultModel)
        val explicit = legacy.isNotEmpty() || !settings.models[provider].isNullOrBlank()
        val listed = entry?.takeIf { it.state == AiModelsState.LOADED }?.infos.orEmpty()
        val listedIds = listed.map { it.id }
        // The model the user set is sent as it is, never swapped; only a default the service no longer offers gives way to a listed one.
        val model = if (explicit || listedIds.isEmpty() || wanted in listedIds) wanted else default.takeIf { it in listedIds } ?: listedIds.first()
        val choicesList =
            if (listed.isNotEmpty()) {
                listed.map { AiModelChoice(it.id, it.name ?: it.id) }
            } else {
                (listOf(model) + provider.suggestedModels).filter(String::isNotBlank).distinct().map { AiModelChoice(it, it) }
            }
        val savedEffort = aiTranslationSavedEffort(settings.thinkings[provider], choices.effort(id))
        // Until the model list is read, a saved Claude level is trusted, so after a restart the video keeps translating with the
        // level the user chose; the list corrects it once it is known.
        val levels =
            listed.firstOrNull { it.id == model }?.efforts ?: savedEffort.wire?.takeIf { id == AiProvider.CLAUDE.key }?.let { setOf(it) }
        val options = aiEffortOptions(id, model, levels)
        val keyPresent = keyStore.keys(provider).isNotEmpty()
        val modelsState =
            when {
                provider.key in mine.loading -> AiModelsState.LOADING
                entry != null -> entry.state
                !keyPresent -> AiModelsState.NEEDS_CONNECTION
                else -> AiModelsState.IDLE
            }
        return AiTranslationUiState(
            accessId = id,
            model = model,
            models = choicesList,
            modelsState = modelsState,
            savedEffort = savedEffort,
            effort = effectiveAiEffort(savedEffort, options),
            effortOptions = options,
            keyPresent = keyPresent,
            automaticShortensThinking = aiEffortWire(provider, model, AiEffort.AUTO) != null,
            testing = mine.testing,
            test = mine.test,
            connected = connectedServices(gpt),
        )
    }

    private fun connectedServices(gpt: ChatGptUiState): Set<String> =
        buildSet {
            AiProvider.entries.filter { keyStore.keys(it).isNotEmpty() }.forEach { add(it.key) }
            if (gpt.status == ChatGptStatus.READY) add(CHATGPT_ACCESS_ID)
        }

    private fun composeChatGpt(gpt: ChatGptUiState): AiTranslationUiState {
        val modelsState =
            when (gpt.status) {
                ChatGptStatus.READY -> if (gpt.models.isEmpty()) AiModelsState.EMPTY else AiModelsState.LOADED
                ChatGptStatus.CHECKING, ChatGptStatus.CONNECTING -> AiModelsState.LOADING
                ChatGptStatus.TEMPORARY_FAILURE -> AiModelsState.FAILED
                else -> AiModelsState.NEEDS_CONNECTION
            }
        val outcome =
            when {
                gpt.testResult != null ->
                    AiTestOutcome(
                        CHATGPT_ACCESS_ID,
                        gpt.model,
                        AiEffort.AUTO,
                        gpt.testResult,
                        TranslationOrigin(TranslationProvider.CHATGPT, cached = false, model = gpt.model),
                    )
                gpt.testFailed -> AiTestOutcome(CHATGPT_ACCESS_ID, gpt.model, AiEffort.AUTO, errorRes = R.string.chatgpt_test_failed)
                else -> null
            }
        return AiTranslationUiState(
            accessId = CHATGPT_ACCESS_ID,
            model = gpt.model,
            models = gpt.models.map { AiModelChoice(it.slug, it.displayName) },
            modelsState = modelsState,
            keyPresent = false,
            testing = gpt.testing,
            test = outcome,
            connected = connectedServices(gpt),
        )
    }
}

/** The app preferences as [AiTranslationPrefs]. */
internal fun android.content.SharedPreferences.aiTranslationPrefs(): AiTranslationPrefs =
    object : AiTranslationPrefs {
        override fun get(key: String): String? = getString(key, null)

        override fun put(
            key: String,
            value: String?,
        ) = edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
