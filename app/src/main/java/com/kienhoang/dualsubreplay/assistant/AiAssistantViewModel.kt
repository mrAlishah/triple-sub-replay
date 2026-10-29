package com.kienhoang.dualsubreplay.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSubscription
import java.io.File

/** Directory under the app's files for saved chats; backups leave it out. */
internal const val AI_CHATS_DIRECTORY = "ai-chats"

/** Connects [AiAssistantController] to the Keystore, preferences, files and network. */
class AiAssistantViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsPreferences = application.getSharedPreferences(AI_SETTINGS_PREFERENCES, 0)
    private val chatHistory = AiChatHistoryStore(File(application.filesDir, AI_CHATS_DIRECTORY))
    private val memory = AiMemoryStore(File(application.filesDir, AI_MEMORY_DIRECTORY))

    /** Read once, the first time a question is asked. */
    internal val guide: String by lazy {
        application.assets
            .open(AI_ASSISTANT_GUIDE_ASSET)
            .bufferedReader()
            .use { it.readText() }
    }

    private val transport = AiHttpTransport()

    /** Keyed services go to [transport]; ChatGPT Subscription goes only to the transport attached by [attachChatGpt]. */
    private val router = AiTransportRouter(transport)
    private val secrets = SharedPreferencesSecretStorage(application.getSharedPreferences(AI_KEYS_PREFERENCES, 0))

    internal val controller =
        AiAssistantController(
            scope = viewModelScope,
            transport = router,
            keyStore = AiKeyStore(secrets, KeystoreSecretCipher()),
            settingsStorage =
                object : AiSettingsStorage {
                    override fun load() = readAiAssistantSettings(settingsPreferences)

                    override fun save(settings: AiAssistantSettings) = writeAiAssistantSettings(settingsPreferences, settings)
                },
            historyStorage =
                object : AiHistoryStorage {
                    override fun load() = chatHistory.load()

                    override fun save(chats: List<AiChat>) = chatHistory.save(chats)

                    override fun clear() = chatHistory.clear()
                },
            modelLister = transport,
            memoryStorage =
                object : AiMemoryStorage {
                    override fun load() = memory.load()

                    override fun save(data: AiMemoryData) = memory.save(data)
                },
            searchKeys = AiSearchKeyStore(secrets, KeystoreSecretCipher()),
            poster = transport,
        )

    /**
     * Lets the chat use the ChatGPT Subscription account that Settings → AI Connection manages. It is the same object the subtitle
     * translation uses: one session, one refresh lock, one usage-limit gate.
     */
    internal fun attachChatGpt(subscription: ChatGptSubscription) {
        router.chatGpt = ChatGptChatTransport(subscription.chatBackend())
        controller.attachChatGpt(subscription.state, subscription::refresh)
    }
}
