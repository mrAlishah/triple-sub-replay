package com.kienhoang.dualsubreplay.assistant

import android.content.Context
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** A subtitle request waits this long for the model; a window of sentences takes a few seconds. */
internal const val AI_TRANSLATION_TIMEOUT_MS = 45_000L

/** Settings → Translation → AI model for translation; empty uses [aiTranslationDefaultModel]. */
internal const val AI_TRANSLATION_MODEL_PREFERENCE = "ai_translation_model"

/**
 * Free keys allow about 15 requests a minute each (Gemini's free tier), so one key is asked at most
 * this often (10 a minute, well under the limit). With several keys the requests take turns, so each key is asked less.
 */
internal const val AI_TRANSLATION_KEY_INTERVAL_MS = 6_000L

/** A key that answered "too many requests" or "quota" rests this long, doubling while it keeps refusing. */
internal const val AI_TRANSLATION_KEY_REST_MS = 60_000L
internal const val AI_TRANSLATION_KEY_MAX_REST_MS = 30 * 60_000L

/** A key the service rejects is not asked again for this long (or until the app restarts). */
internal const val AI_TRANSLATION_BAD_KEY_REST_MS = 6 * 60 * 60_000L

private val REST_PROBLEMS = setOf(AiErrorKind.RATE_LIMITED, AiErrorKind.NO_CREDIT)
private val BAD_KEY_PROBLEMS = setOf(AiErrorKind.NO_KEY, AiErrorKind.KEY_UNREADABLE, AiErrorKind.INVALID_KEY)

/**
 * The model subtitles are translated with when none is chosen: the AI assistant's own model (owner
 * request 2026-10-28; Gemini's default is already the fast Flash-Lite). Thinking is turned off for
 * translation, so a thinking model still answers quickly.
 */
internal fun aiTranslationDefaultModel(settings: AiAssistantSettings): String = settings.modelFor(settings.provider)

/** The models offered besides "Same as AI assistant": the service's suggestions. */
internal fun aiTranslationModelChoices(settings: AiAssistantSettings): List<String> =
    settings.provider.suggestedModels
        .filter(String::isNotBlank)
        .distinct()

/** The chosen model when it is still offered by the current service, else the assistant's. */
internal fun aiTranslationModelName(
    settings: AiAssistantSettings,
    chosen: String?,
): String = chosen?.takeIf { it in aiTranslationModelChoices(settings) } ?: aiTranslationDefaultModel(settings)

/**
 * The model subtitles are translated with: the service and keys chosen in More settings → AI
 * assistant, and the model chosen in Settings → Translation (owner request 2026-10-28). Null when
 * that service has no key, so Google translates.
 */
internal fun aiTranslationModel(
    settings: AiAssistantSettings,
    chosenModel: String?,
    keyStore: AiKeyStore,
    transport: AiChatTransport,
    pacer: AiKeyPacer = AiKeyPacer.Shared,
): AiTranslationModel? {
    val provider = settings.provider
    return aiTranslationModel(
        provider,
        aiTranslationModelName(settings, chosenModel),
        AiEffort.AUTO,
        settings.baseUrlFor(provider),
        keyStore,
        transport,
        pacer,
    )
}

/**
 * [provider]'s model [model] with the user's keys for that provider, sending [effort] only as [aiEffortWire] allows. Null when
 * the provider has no key or no model, so Google translates; another provider's keys are never used.
 */
internal fun aiTranslationModel(
    provider: AiProvider,
    model: String,
    effort: AiEffort,
    baseUrl: String,
    keyStore: AiKeyStore,
    transport: AiChatTransport,
    pacer: AiKeyPacer = AiKeyPacer.Shared,
    dropRefusedEffort: Boolean = true,
): AiTranslationModel? {
    val ids = keyStore.keys(provider).map { it.id }
    if (ids.isEmpty() || model.isBlank()) return null
    return KeyedAiTranslationModel(provider, model, baseUrl, ids, keyStore, transport, pacer, effort, dropRefusedEffort)
}

/** The user's own [provider] keys and settings from the app's storage, for the chosen [model] and [effort]. */
internal fun Context.aiTranslationModelFor(
    provider: AiProvider,
    model: String,
    effort: AiEffort,
    transport: AiChatTransport,
    dropRefusedEffort: Boolean = true,
): AiTranslationModel? =
    aiTranslationModel(
        provider,
        model,
        effort,
        readAiAssistantSettings(getSharedPreferences(AI_SETTINGS_PREFERENCES, 0)).baseUrlFor(provider),
        AiKeyStore(SharedPreferencesSecretStorage(getSharedPreferences(AI_KEYS_PREFERENCES, 0)), KeystoreSecretCipher()),
        transport,
        dropRefusedEffort = dropRefusedEffort,
    )

/**
 * When each key was last asked and how long it rests, kept for the whole app run so a new video does
 * not start by asking a key that just ran out. Requests take turns over the keys: the key asked
 * longest ago goes first.
 */
internal class AiKeyPacer(
    private val now: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = AI_TRANSLATION_KEY_INTERVAL_MS,
) {
    private class KeyState {
        var lastAskedAt = Long.MIN_VALUE / 2
        var turn = 0L
        var restUntil = Long.MIN_VALUE / 2
        var nextRestMs = AI_TRANSLATION_KEY_REST_MS
    }

    private val keys = HashMap<String, KeyState>()
    private var turns = 0L
    private val lock = Mutex()

    /** Picks the key to ask now among [ids], waiting for its turn; null when every key rests. */
    suspend fun take(ids: List<String>): String? {
        val (id, waitMs) =
            lock.withLock {
                val time = now()
                val ready = ids.filter { state(it).restUntil <= time }
                val id = ready.minByOrNull { state(it).turn } ?: return null
                val state = state(id)
                val waitMs = (state.lastAskedAt + intervalMs - time).coerceAtLeast(0)
                state.lastAskedAt = time + waitMs
                state.turn = ++turns
                id to waitMs
            }
        if (waitMs > 0) delay(waitMs)
        return id
    }

    /** The key answered: it may rest only briefly next time. */
    suspend fun answered(id: String) = lock.withLock { state(id).nextRestMs = AI_TRANSLATION_KEY_REST_MS }

    /** The key ran into its limit: it rests, longer each time in a row. */
    suspend fun limited(id: String) =
        lock.withLock {
            val state = state(id)
            state.restUntil = now() + state.nextRestMs
            state.nextRestMs = (state.nextRestMs * 2).coerceAtMost(AI_TRANSLATION_KEY_MAX_REST_MS)
        }

    /** The service rejected the key. */
    suspend fun rejected(id: String) = lock.withLock { state(id).restUntil = now() + AI_TRANSLATION_BAD_KEY_REST_MS }

    private fun state(id: String) = keys.getOrPut(id) { KeyState() }

    companion object {
        val Shared = AiKeyPacer()
    }
}

private class KeyedAiTranslationModel(
    private val provider: AiProvider,
    private val model: String,
    private val baseUrl: String,
    private val keyIds: List<String>,
    private val keyStore: AiKeyStore,
    private val transport: AiChatTransport,
    private val pacer: AiKeyPacer,
    chosenEffort: AiEffort = AiEffort.AUTO,
    /** Subtitles go on without a refused setting; Test translation reports the refusal instead. */
    private val dropRefusedEffort: Boolean = true,
) : AiTranslationModel {
    override val scope: String = aiTranslationScope(provider, model, chosenEffort)

    private val pacedIds = keyIds.map { "${provider.key}/$it" }

    /** Cleared once the model refuses the thinking setting, so later requests do not fail first. */
    @Volatile private var effort = aiEffortWire(provider, model, chosenEffort)

    override suspend fun ask(
        system: String,
        user: String,
        onText: (String) -> Unit,
    ): String {
        val messages = listOf(AiWireMessage(AiRole.SYSTEM, system), AiWireMessage(AiRole.USER, user))
        val text = StreamedText(onText)
        var failure = AiChatException(AiErrorKind.RATE_LIMITED, "Every key is resting after its limit.")
        repeat(keyIds.size) {
            val paced = pacer.take(pacedIds) ?: throw failure
            val id = keyIds[pacedIds.indexOf(paced)]
            try {
                val key = withContext(Dispatchers.IO) { keyStore.load(provider, id) }
                if (key !is StoredAiKey.Found) throw AiChatException(AiErrorKind.KEY_UNREADABLE)
                val reply = completeWhenBusy(key.key, messages, text)
                pacer.answered(paced)
                return reply.text
            } catch (error: AiChatException) {
                // Part of the answer already arrived: asking again would hand it over twice.
                if (text.started) throw error
                when (error.kind) {
                    in REST_PROBLEMS -> pacer.limited(paced)
                    in BAD_KEY_PROBLEMS -> pacer.rejected(paced)
                    else -> throw error
                }
                failure = error
            }
        }
        throw failure
    }

    /** A busy model is asked again after the assistant's waits; a refused thinking setting is dropped. */
    private suspend fun completeWhenBusy(
        apiKey: String,
        messages: List<AiWireMessage>,
        text: StreamedText,
    ): AiReply {
        val waits = AI_BUSY_RETRY_DELAYS_MS.iterator()
        while (true) {
            val request = AiChatRequest(baseUrl, model, apiKey, messages, AI_TRANSLATION_TIMEOUT_MS, effort, format = provider.format)
            try {
                return withContext(Dispatchers.IO) { send(request, text) }
            } catch (error: AiChatException) {
                when {
                    text.started -> throw error
                    dropRefusedEffort && effort != null && error.kind in setOf(AiErrorKind.UNSUPPORTED_THINKING, AiErrorKind.BAD_REQUEST) ->
                        effort =
                            null
                    error.kind == AiErrorKind.SERVER && waits.hasNext() -> delay(waits.next())
                    else -> throw error
                }
            }
        }
    }

    private suspend fun send(
        request: AiChatRequest,
        text: StreamedText,
    ): AiReply =
        try {
            transport.stream(request, text)
        } catch (error: AiChatException) {
            throw error
        } catch (error: IOException) {
            throw aiConnectionFailure(error)
        }
}

/** Hands the reply's pieces on and remembers that some arrived, after which a request is not sent again. */
private class StreamedText(
    private val onText: (String) -> Unit,
) : (String) -> Unit {
    @Volatile var started = false
        private set

    override fun invoke(piece: String) {
        if (piece.isEmpty()) return
        started = true
        onText(piece)
    }
}
