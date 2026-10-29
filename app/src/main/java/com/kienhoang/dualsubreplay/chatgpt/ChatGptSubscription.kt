package com.kienhoang.dualsubreplay.chatgpt

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.kienhoang.dualsubreplay.assistant.CHATGPT_ACCESS_ID
import com.kienhoang.dualsubreplay.assistant.KeystoreSecretCipher
import com.kienhoang.dualsubreplay.assistant.SharedPreferencesSecretStorage
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Translation → AI access: `keys` (the AI assistant's service and keys, the default) or `chatgpt`. */
internal const val AI_ACCESS_METHOD_PREFERENCE = "ai_access_method"
internal const val CHATGPT_MODEL_PREFERENCE = "chatgpt_translation_model"
internal const val AI_ACCESS_CHATGPT = CHATGPT_ACCESS_ID

internal const val TEST_INSTRUCTIONS = "Translate the German sentence the user sends into Persian. Reply with the translation only."
internal const val TEST_SENTENCE = "Ich lerne jeden Tag Deutsch."

internal enum class ChatGptStatus {
    NOT_CONNECTED,
    CONNECTING,
    CHECKING,
    READY,
    NO_PLAN_USAGE,
    NOT_ELIGIBLE,
    SIGN_IN_REQUIRED,
    USAGE_LIMIT,
    TEMPORARY_FAILURE,
}

internal data class ChatGptUiState(
    val useSubscription: Boolean = false,
    val status: ChatGptStatus = ChatGptStatus.NOT_CONNECTED,
    val account: String? = null,
    val models: List<ChatGptModel> = emptyList(),
    val model: String = "",
    val testing: Boolean = false,
    /** The test translation, or null; never kept beyond the screen. */
    val testResult: String? = null,
    val testFailed: Boolean = false,
    /** The last browser sign-in ended without an account (cancelled, timed out, refused or no browser). */
    val signInFailed: Boolean = false,
)

/**
 * The ChatGPT subscription as the Settings screen and the translator see it: the choice (off by default), the
 * signed-in account, the account's models and a one-sentence test. Credentials live only in [ChatGptCredentialStore].
 */
internal class ChatGptSubscription(
    private val preferences: SharedPreferences,
    private val store: ChatGptCredentialStore,
    private val api: ChatGptApi,
    private val session: ChatGptSession,
    private val gate: ChatGptGate,
    private val scope: CoroutineScope,
    private val agentName: String,
    private val openBrowser: (String) -> Unit,
    /** Called when the signed-in account or the model changed, so the playing video translates again. */
    private val onChanged: () -> Unit,
) {
    private val mutableState =
        MutableStateFlow(
            ChatGptUiState(
                useSubscription = preferences.getString(AI_ACCESS_METHOD_PREFERENCE, null) == AI_ACCESS_CHATGPT,
                model = preferences.getString(CHATGPT_MODEL_PREFERENCE, "").orEmpty(),
            ),
        )
    val state: StateFlow<ChatGptUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    val useSubscription: Boolean get() = mutableState.value.useSubscription

    init {
        // A failure the translation hits (ineligible, limit, ended sign-in) reaches the panel, not just the log-less fallback.
        gate.onBlocked = { error ->
            accountStatusFor(error.kind)?.let { status -> mutableState.update { it.copy(status = status) } }
        }
        // After a restart the panel must show the real account, not "Not connected".
        if (useSubscription) refresh()
    }

    fun select(useSubscription: Boolean) {
        preferences.edit { putString(AI_ACCESS_METHOD_PREFERENCE, if (useSubscription) AI_ACCESS_CHATGPT else "keys") }
        mutableState.update { it.copy(useSubscription = useSubscription) }
        if (useSubscription) {
            // The video translates again once the account and its model are known, not before.
            launch {
                gate.reset()
                loadAccount()
                onChanged()
            }
        } else {
            onChanged()
        }
    }

    fun selectModel(slug: String) {
        preferences.edit { putString(CHATGPT_MODEL_PREFERENCE, slug) }
        gate.reset()
        mutableState.update { it.copy(model = slug, testResult = null) }
        onChanged()
    }

    /** The model subtitles are translated with, or null so the caller uses Google or the device: no session, no plan usage, no model. */
    fun translationModel(): AiTranslationModel? {
        val credentials = store.load()?.takeIf { it.signedIn && it.grantsPlanUsage } ?: return null
        val model = mutableState.value.model.ifEmpty { return null }
        return ChatGptTranslationModel(model, credentials.cacheScope(model), session, api, gate)
    }

    /** Resets the choice only; the signed-in account stays until the user disconnects. */
    fun resetChoice() {
        preferences.edit { remove(AI_ACCESS_METHOD_PREFERENCE).remove(CHATGPT_MODEL_PREFERENCE) }
        mutableState.update { it.copy(useSubscription = false, model = "") }
    }

    fun signIn() {
        launch {
            mutableState.update { it.copy(status = ChatGptStatus.CONNECTING, testResult = null, signInFailed = false) }
            try {
                ChatGptSignIn(store, api, agentName, save = session::replace).signIn(openBrowser)
                gate.reset()
                loadAccount()
                onChanged()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ChatGptException) {
                loadAccount()
                mutableState.update { it.copy(signInFailed = error.kind == ChatGptErrorKind.SIGN_IN_FAILED) }
            } catch (_: Exception) {
                // No browser, a refused intent or a failing Keystore: the sign-in did not finish, the app keeps running.
                loadAccount()
                mutableState.update { it.copy(signInFailed = true) }
            }
        }
    }

    fun refresh() {
        launch {
            gate.reset()
            loadAccount()
        }
    }

    fun disconnect() {
        job?.cancel()
        scope.launch {
            session.disconnect()
            gate.reset()
            preferences.edit { remove(CHATGPT_MODEL_PREFERENCE) }
            mutableState.value =
                mutableState.value.copy(
                    status = ChatGptStatus.NOT_CONNECTED,
                    account = null,
                    models = emptyList(),
                    model = "",
                    testResult = null,
                    testFailed = false,
                    signInFailed = false,
                )
            onChanged()
        }
    }

    fun test() {
        launch {
            val model = withContext(Dispatchers.IO) { translationModel() }
            if (model == null) {
                mutableState.update { it.copy(testing = false, testFailed = true) }
                return@launch
            }
            mutableState.update { it.copy(testing = true, testResult = null, testFailed = false) }
            try {
                val text = model.ask(TEST_INSTRUCTIONS, TEST_SENTENCE)
                mutableState.update { it.copy(testing = false, testResult = text.trim()) }
            } catch (error: ChatGptException) {
                // Only a failure that changes what the account may do changes the status; a passing one just fails the test.
                mutableState.update { it.copy(testing = false, testFailed = true, status = accountStatusFor(error.kind) ?: it.status) }
            }
        }
    }

    /** Stops a sign-in that is waiting for the browser and shows the account as it was. */
    fun cancel() {
        launch { loadAccount() }
    }

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private suspend fun loadAccount() {
        val credentials = withContext(Dispatchers.IO) { store.load() }
        when {
            credentials == null ->
                mutableState.update {
                    it.copy(
                        status = ChatGptStatus.NOT_CONNECTED,
                        account = null,
                        models = emptyList(),
                    )
                }
            !credentials.signedIn -> mutableState.update { it.copy(status = ChatGptStatus.SIGN_IN_REQUIRED, account = credentials.email) }
            !credentials.grantsPlanUsage ->
                mutableState.update {
                    it.copy(status = ChatGptStatus.NO_PLAN_USAGE, account = credentials.email, models = emptyList())
                }
            else -> {
                mutableState.update { it.copy(status = ChatGptStatus.CHECKING, account = credentials.email) }
                try {
                    val models = api.models(session.accessToken())
                    // A saved model the account no longer lists is dropped rather than failing later.
                    val saved =
                        mutableState.value.model.takeIf { slug -> models.any { it.slug == slug } } ?: models.firstOrNull()?.slug.orEmpty()
                    preferences.edit { putString(CHATGPT_MODEL_PREFERENCE, saved) }
                    mutableState.update { it.copy(status = ChatGptStatus.READY, models = models, model = saved) }
                } catch (error: ChatGptException) {
                    gate.record(error)
                    mutableState.update { it.copy(status = accountStatusFor(error.kind) ?: ChatGptStatus.TEMPORARY_FAILURE) }
                }
            }
        }
    }

    private fun accountStatusFor(kind: ChatGptErrorKind): ChatGptStatus? =
        when (kind) {
            ChatGptErrorKind.NOT_ELIGIBLE -> ChatGptStatus.NOT_ELIGIBLE
            ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED -> ChatGptStatus.NO_PLAN_USAGE
            ChatGptErrorKind.SIGN_IN_REQUIRED -> ChatGptStatus.SIGN_IN_REQUIRED
            ChatGptErrorKind.USAGE_LIMIT -> ChatGptStatus.USAGE_LIMIT
            else -> null
        }

    companion object {
        fun create(
            context: Context,
            preferences: SharedPreferences,
            scope: CoroutineScope,
            agentName: String,
            onChanged: () -> Unit,
        ): ChatGptSubscription {
            val app = context.applicationContext
            val store =
                ChatGptCredentialStore(
                    SharedPreferencesSecretStorage(app.getSharedPreferences(CHATGPT_PREFERENCES, Context.MODE_PRIVATE)),
                    KeystoreSecretCipher("dualsub_chatgpt_subscription"),
                )
            val api = ChatGptHttpApi()
            val openBrowser = { url: String ->
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return ChatGptSubscription(
                preferences,
                store,
                api,
                ChatGptSession(store, api),
                ChatGptGate(),
                scope,
                agentName,
                openBrowser,
                onChanged,
            )
        }
    }
}
