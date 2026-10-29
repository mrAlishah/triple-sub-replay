package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_MODEL_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_PROVIDER_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiChatException
import com.kienhoang.dualsubreplay.assistant.AiChatRequest
import com.kienhoang.dualsubreplay.assistant.AiChatTransport
import com.kienhoang.dualsubreplay.assistant.AiEffort
import com.kienhoang.dualsubreplay.assistant.AiErrorKind
import com.kienhoang.dualsubreplay.assistant.AiKeyPacer
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiModelInfo
import com.kienhoang.dualsubreplay.assistant.AiModelLister
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiReply
import com.kienhoang.dualsubreplay.assistant.AiTranslationChoices
import com.kienhoang.dualsubreplay.assistant.FakeCipher
import com.kienhoang.dualsubreplay.assistant.MapStorage
import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.aiTranslationModel
import com.kienhoang.dualsubreplay.chatgpt.CHATGPT_PLAN_SCOPE
import com.kienhoang.dualsubreplay.chatgpt.ChatGptApi
import com.kienhoang.dualsubreplay.chatgpt.ChatGptCredentialStore
import com.kienhoang.dualsubreplay.chatgpt.ChatGptCredentials
import com.kienhoang.dualsubreplay.chatgpt.ChatGptGate
import com.kienhoang.dualsubreplay.chatgpt.ChatGptModel
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSession
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSubscription
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Map-backed preferences: the holder and the ChatGPT subscription only read and write strings. */
private class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, String>()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = map[key] ?: defValue

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()

            override fun putString(
                key: String,
                value: String?,
            ) = apply { pending[key] = value }

            override fun remove(key: String) = apply { pending[key] = null }

            override fun apply() {
                pending.forEach { (key, value) -> if (value == null) map.remove(key) else map[key] = value }
            }

            override fun commit(): Boolean = true.also { apply() }

            override fun clear() = apply { map.clear() }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = this

            override fun putInt(
                key: String,
                value: Int,
            ) = this

            override fun putLong(
                key: String,
                value: Long,
            ) = this

            override fun putFloat(
                key: String,
                value: Float,
            ) = this

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = this
        }

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ) = defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ) = defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ) = defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ) = defValue

    override fun contains(key: String) = key in map

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}

private object OpenCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray) = sealed
}

private class GptApi : ChatGptApi {
    var streams = 0

    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ) = error("unused")

    override suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ) = error("unused")

    override suspend fun revoke(
        clientId: String,
        token: String,
    ) = Unit

    override suspend fun jwks() = error("unused")

    override suspend fun models(accessToken: String) = listOf(ChatGptModel("gpt-x", "GPT X"), ChatGptModel("gpt-y", "GPT Y"))

    override suspend fun stream(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ): String {
        streams++
        onText("من هر روز آلمانی یاد می‌گیرم.")
        return "من هر روز آلمانی یاد می‌گیرم."
    }
}

class AiTranslationConfigTest {
    private val prefs = FakePrefs()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val keyStore = AiKeyStore(MapStorage(), FakeCipher())
    private val gptApi = GptApi()
    private val gptStore = ChatGptCredentialStore(MapStorage(), OpenCipher)
    private var reloads = 0
    private val requests = mutableListOf<AiChatRequest>()
    private var lists = 0
    private var assistant = AiAssistantSettings(provider = AiProvider.GEMINI)
    private var transportFailure: AiChatException? = null

    private val transport =
        AiChatTransport { request ->
            requests += request
            transportFailure?.let { throw it }
            AiReply("Ich lerne → ترجمه")
        }

    private val claudeModels =
        listOf(
            AiModelInfo("claude-opus-5-5", name = "Claude Opus 5.5", efforts = setOf("low", "medium", "high", "xhigh", "max")),
            AiModelInfo("claude-haiku-5-5", name = "Claude Haiku 5.5", efforts = setOf("low", "medium", "high")),
        )
    private val geminiModels =
        listOf(AiModelInfo("gemini-flash-lite-latest"), AiModelInfo("gemini-flash-latest"), AiModelInfo("gemini-3-flash-preview"))
    private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val lister =
        AiModelLister { _, _, format ->
            lists++
            gate?.await()
            if (format == com.kienhoang.dualsubreplay.assistant.AiWireFormat.ANTHROPIC_MESSAGES) claudeModels else geminiModels
        }

    private val chatGpt =
        ChatGptSubscription(
            prefs,
            gptStore,
            gptApi,
            ChatGptSession(gptStore, gptApi),
            ChatGptGate(),
            scope,
            "TripleSub",
            { },
        ) { reloads++ }

    private val config =
        AiTranslationConfig(
            choices = AiTranslationChoices(FakePrefsView(prefs)) { chatGpt.useSubscription },
            assistant = { assistant },
            keyStore = keyStore,
            lister = lister,
            chatGpt = chatGpt,
            scope = scope,
            buildKeyed = { provider, model, effort, drop ->
                aiTranslationModel(provider, model, effort, provider.baseUrl, keyStore, transport, AiKeyPacer(intervalMs = 0), drop)
            },
        ) { reloads++ }

    private class FakePrefsView(
        private val prefs: FakePrefs,
    ) : com.kienhoang.dualsubreplay.assistant.AiTranslationPrefs {
        override fun get(key: String) = prefs.map[key]

        override fun put(
            key: String,
            value: String?,
        ) {
            if (value == null) prefs.map.remove(key) else prefs.map[key] = value
        }
    }

    private fun addKeys() {
        keyStore.add(AiProvider.GEMINI, "AIzaGeminiKey0000000000000000000000000")
        keyStore.add(AiProvider.CLAUDE, "sk-ant-claudeKey0000000000000000000000000")
    }

    private fun signInChatGpt() {
        gptStore.save(
            ChatGptCredentials("oaiapp_1", "sub-1", null, "id", "access", "refresh", Long.MAX_VALUE / 2, setOf(CHATGPT_PLAN_SCOPE), null),
        )
    }

    private suspend fun loaded() = withTimeout(5000) { config.state.first { it.modelsState == AiModelsState.LOADED } }

    @Test
    fun existingUsersStartOnTheAssistantsServiceWithNothingSaved() {
        assertEquals("gemini", config.state.value.accessId)
        assertFalse(chatGpt.useSubscription)
        assertTrue(prefs.map.isEmpty())
        assertEquals(0, reloads)
    }

    @Test
    fun anUnknownProviderCannotBeSelectedAndStartsNothing() {
        addKeys()
        config.selectAccess("claude_subscription")
        config.selectAccess("not_a_provider")
        assertEquals("gemini", config.state.value.accessId)
        assertEquals(0, reloads)
        assertEquals(0, requests.size)
        assertEquals(0, gptApi.streams)
        assertNull(prefs.map[AI_TRANSLATION_PROVIDER_PREFERENCE])
    }

    @Test
    fun switchingProvidersReturnsEachProvidersModelAndEffort() =
        runBlocking {
            addKeys()
            config.ensureModels()
            loaded()
            config.selectModel("gemini-3-flash-preview")
            config.selectEffort(AiEffort.MINIMAL)

            config.selectAccess("claude")
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("claude-opus-5-5")
            config.selectEffort(AiEffort.MAX)
            assertEquals("claude-opus-5-5", config.state.value.model)
            assertEquals(AiEffort.MAX, config.state.value.effort)

            config.selectAccess("gemini")
            withTimeout(5000) { config.state.first { it.accessId == "gemini" && it.modelsState == AiModelsState.LOADED } }
            assertEquals("gemini-3-flash-preview", config.state.value.model)
            assertEquals(AiEffort.MINIMAL, config.state.value.effort)

            config.selectAccess("claude")
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            assertEquals("claude-opus-5-5", config.state.value.model)
            assertEquals(AiEffort.MAX, config.state.value.effort)
            // The AI assistant's own service is untouched by any of this.
            assertEquals(AiProvider.GEMINI, assistant.provider)
        }

    @Test
    fun eachModelOffersOnlyItsOwnEffortLevelsAndAnUnsupportedSavedLevelWaitsInTheBackground() =
        runBlocking {
            addKeys()
            config.selectAccess("claude")
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("claude-opus-5-5")
            assertTrue(AiEffort.MAX in config.state.value.effortOptions)
            config.selectEffort(AiEffort.MAX)

            config.selectModel("claude-haiku-5-5")
            val haiku = config.state.value
            assertEquals(listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH), haiku.effortOptions)
            assertEquals(AiEffort.AUTO, haiku.effort)
            assertEquals("the saved choice is kept", AiEffort.MAX, haiku.savedEffort)
            // A level the model lacks cannot be chosen.
            config.selectEffort(AiEffort.XHIGH)
            assertEquals(AiEffort.MAX, config.state.value.savedEffort)

            config.selectModel("claude-opus-5-5")
            assertEquals(AiEffort.MAX, config.state.value.effort)
        }

    @Test
    fun theRequestCarriesTheEffortOnlyWhenOneIsInForceAndTheScopeFollowsIt() =
        runBlocking {
            addKeys()
            config.selectAccess("claude")
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("claude-haiku-5-5")
            val automatic = config.translationModel()!!
            automatic.ask("s", "u")
            assertNull(requests.last().reasoningEffort)
            assertEquals("claude/claude-haiku-5-5", automatic.scope)

            config.selectEffort(AiEffort.LOW)
            val low = config.translationModel()!!
            low.ask("s", "u")
            assertEquals("low", requests.last().reasoningEffort)
            assertEquals("claude/claude-haiku-5-5@low", low.scope)
            assertTrue(low.scope != automatic.scope)
            assertTrue(requests.all { it.apiKey.startsWith("sk-ant-") })
        }

    @Test
    fun apiKeysAndTheChatGptSessionAreNeverMixedUp() =
        runBlocking {
            // Only Gemini has a key: Claude gets no model, and nothing is asked of Gemini instead.
            keyStore.add(AiProvider.GEMINI, "AIzaGeminiKey0000000000000000000000000")
            config.selectAccess("claude")
            assertNull(config.translationModel())
            assertFalse(config.state.value.keyPresent)
            withTimeout(5000) { config.state.first { it.modelsState == AiModelsState.NEEDS_CONNECTION } }

            // ChatGPT without a session is no model either, although API keys exist.
            config.selectAccess("chatgpt")
            assertNull(config.translationModel())
            assertEquals(0, requests.size)
            assertEquals(0, gptApi.streams)
        }

    @Test
    fun chatGptSubscriptionStillTranslatesWithoutAnyApiKey() =
        runBlocking {
            signInChatGpt()
            config.selectAccess("chatgpt")
            withTimeout(5000) { config.state.first { it.isChatGpt && it.modelsState == AiModelsState.LOADED } }
            assertEquals(
                listOf("gpt-x", "gpt-y"),
                config.state.value.models
                    .map { it.id },
            )
            assertEquals("gpt-x", config.state.value.model)
            assertEquals(listOf(AiEffort.AUTO), config.state.value.effortOptions)
            val model = config.translationModel()!!
            assertTrue(model.scope.startsWith("chatgpt/gpt-x/"))
            assertEquals("من هر روز آلمانی یاد می‌گیرم.", model.ask("s", "u"))
            assertEquals(1, gptApi.streams)
            assertEquals(0, requests.size)
            assertEquals("the ChatGPT effort is never selectable", false, AiEffort.HIGH in config.state.value.effortOptions)
        }

    @Test
    fun everyChangeReloadsTheVideoOnceAndNothingIsSentToAProviderByChoosing() =
        runBlocking {
            addKeys()
            config.selectAccess("claude")
            assertEquals(1, reloads)
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("claude-opus-5-5")
            config.selectEffort(AiEffort.HIGH)
            assertEquals(3, reloads)
            // Choosing the same thing again changes nothing; choosing today's default saves it without reloading.
            config.selectModel("claude-opus-5-5")
            config.selectAccess("claude")
            config.selectEffort(AiEffort.HIGH)
            assertEquals(3, reloads)
            // Listing models is free metadata; no translation request was made.
            assertTrue(lists >= 1)
            assertEquals(0, requests.size)
        }

    @Test
    fun aSavedModelTheServiceNoLongerOffersIsReplacedWithANoticeAndStaysSaved() =
        runBlocking {
            addKeys()
            prefs.map["ai_translation_model_gemini"] = "gemini-retired"
            config.ensureModels()
            val state = loaded()
            assertEquals("gemini-retired", state.replacedModel)
            assertTrue(state.model in geminiModels.map { it.id })
            assertEquals("gemini-retired", prefs.map["ai_translation_model_gemini"])
        }

    @Test
    fun theOlderSingleModelSettingStillSelectsTheAssistantsServiceModel() {
        prefs.map[AI_TRANSLATION_MODEL_PREFERENCE] = "gemini-flash-latest"
        config.resetChoice()
        assertEquals("gemini-flash-latest", config.state.value.model)
        assertEquals(
            "claude-haiku-5-5",
            run {
                config.selectAccess("claude")
                config.state.value.model
            },
        )
    }

    @Test
    fun testTranslationUsesTheSelectedProviderAndNamesWhoAnswered() =
        runBlocking {
            addKeys()
            config.selectAccess("claude")
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("claude-haiku-5-5")
            config.selectEffort(AiEffort.LOW)
            config.test()
            val outcome = withTimeout(5000) { config.state.first { it.test != null } }.test!!
            assertEquals("claude", outcome.accessId)
            assertEquals("claude-haiku-5-5", outcome.model)
            assertEquals(AiEffort.LOW, outcome.effort)
            assertEquals("Ich lerne → ترجمه", outcome.text)
            assertEquals(TranslationProvider.AI_PROVIDER, outcome.origin?.provider)
            assertEquals("claude/claude-haiku-5-5@low", outcome.origin?.model)
            assertEquals("low", requests.single().reasoningEffort)
            assertNull(outcome.errorRes)
        }

    @Test
    fun aRefusedEffortFailsTheTestWithAClearMessageAndKeepsTheSavedChoice() =
        runBlocking {
            addKeys()
            config.selectAccess("claude")
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("claude-opus-5-5")
            config.selectEffort(AiEffort.XHIGH)
            transportFailure = AiChatException(AiErrorKind.BAD_REQUEST)
            config.test()
            val outcome = withTimeout(5000) { config.state.first { it.test != null } }.test!!
            assertEquals(R.string.ai_translation_test_effort_refused, outcome.errorRes)
            assertNull(outcome.text)
            assertEquals(AiEffort.XHIGH, config.state.value.savedEffort)
            assertEquals("claude-opus-5-5", config.state.value.model)
        }

    @Test
    fun testTranslationWithoutAKeyNeverUsesAnotherProvider() =
        runBlocking {
            keyStore.add(AiProvider.GEMINI, "AIzaGeminiKey0000000000000000000000000")
            config.selectAccess("claude")
            config.test()
            // No Claude key: the test says so, and nothing is sent to Gemini or anyone else instead.
            val outcome = withTimeout(5000) { config.state.first { it.test != null } }.test!!
            assertEquals(R.string.ai_translation_test_no_key, outcome.errorRes)
            assertEquals(0, requests.size)
        }

    @Test
    fun resettingStartsAgainFromTheAssistantsService() {
        addKeys()
        config.selectAccess("claude")
        assertEquals("claude", config.state.value.accessId)
        prefs.map.clear()
        config.resetChoice()
        assertEquals("gemini", config.state.value.accessId)
    }

    @Test
    fun anUnchosenProviderFollowsTheAssistantsServiceUntilTheUserPinsOne() {
        addKeys()
        assistant = AiAssistantSettings(provider = AiProvider.CLAUDE)
        assertEquals("claude", config.accessId)
        assertTrue(config.translationModel()!!.scope.startsWith("claude/"))
        assistant = AiAssistantSettings(provider = AiProvider.GEMINI)
        config.selectAccess("gemini")
        assertEquals("choosing the provider already in force pins it", "gemini", prefs.map[AI_TRANSLATION_PROVIDER_PREFERENCE])
        assistant = AiAssistantSettings(provider = AiProvider.CLAUDE)
        assertEquals("gemini", config.accessId)
        assertEquals(0, reloads)
    }

    @Test
    fun aSavedClaudeLevelIsUsedOnTheWireBeforeTheModelListIsRead() =
        runBlocking {
            addKeys()
            prefs.map["ai_translation_provider"] = "claude"
            prefs.map["ai_translation_model_claude"] = "claude-opus-5-5"
            prefs.map["ai_translation_effort_claude"] = "high"
            val model = config.translationModel()!!
            assertEquals("claude/claude-opus-5-5@high", model.scope)
            model.ask("s", "u")
            assertEquals("high", requests.single().reasoningEffort)
        }

    @Test
    fun theModelListCorrectsATrustedLevelAndTranslatesAgainOnlyWhenSomethingChanged() =
        runBlocking {
            addKeys()
            prefs.map["ai_translation_provider"] = "claude"
            prefs.map["ai_translation_model_claude"] = "claude-haiku-5-5"
            prefs.map["ai_translation_effort_claude"] = "max"
            config.resetChoice()
            assertEquals(AiEffort.MAX, config.state.value.effort)
            config.ensureModels()
            withTimeout(5000) { config.state.first { it.accessId == "claude" && it.modelsState == AiModelsState.LOADED } }
            // Haiku does not report max: Automatic is in force now, the saved level waits, and the video translates again once.
            assertEquals(AiEffort.AUTO, config.state.value.effort)
            assertEquals(AiEffort.MAX, config.state.value.savedEffort)
            withTimeout(5000) { while (reloads < 1) kotlinx.coroutines.delay(10) }
            assertEquals(1, reloads)
        }

    @Test
    fun aCancelledModelLoadNeverLeavesTheProviderStuckOnLoading() =
        runBlocking {
            addKeys()
            gate = kotlinx.coroutines.CompletableDeferred()
            config.ensureModels()
            assertEquals(AiModelsState.LOADING, config.state.value.modelsState)
            config.resetChoice()
            gate!!.complete(Unit)
            withTimeout(5000) { config.state.first { it.modelsState != AiModelsState.LOADING } }
            val before = lists
            config.ensureModels(force = true)
            loaded()
            assertTrue("a later refresh really loads again", lists > before)
        }

    @Test
    fun chatGptAndAnApiProviderCanBeSwitchedBackAndForthWithoutLosingEitherOne() =
        runBlocking {
            signInChatGpt()
            addKeys()
            config.selectAccess("chatgpt")
            withTimeout(5000) { config.state.first { it.isChatGpt && it.modelsState == AiModelsState.LOADED } }
            config.selectModel("gpt-y")
            assertTrue(chatGpt.useSubscription)

            config.selectAccess("claude")
            assertFalse(chatGpt.useSubscription)
            assertEquals("keys", prefs.map["ai_access_method"])
            assertEquals("claude", config.accessId)
            assertTrue(config.translationModel()!!.scope.startsWith("claude/"))

            config.selectAccess("chatgpt")
            assertTrue(chatGpt.useSubscription)
            withTimeout(5000) { config.state.first { it.isChatGpt && it.modelsState == AiModelsState.LOADED } }
            assertEquals("gpt-y", config.state.value.model)
            assertTrue(config.translationModel()!!.scope.startsWith("chatgpt/gpt-y/"))
            assertEquals("the API provider choice is remembered", "claude", prefs.map[AI_TRANSLATION_PROVIDER_PREFERENCE])
        }
}
