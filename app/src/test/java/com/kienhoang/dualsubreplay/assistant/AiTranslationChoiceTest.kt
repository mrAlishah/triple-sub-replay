package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.chatgpt.chatGptResponsesBody
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class MapPrefs : AiTranslationPrefs {
    val map = mutableMapOf<String, String>()

    override fun get(key: String) = map[key]

    override fun put(
        key: String,
        value: String?,
    ) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** The AI provider dropdown, the effort rules, the saved choices and what is serialized for each provider. */
class AiTranslationChoiceTest {
    @Test
    fun theDropdownOffersChatGptSubscriptionAndTheApiProvidersOnly() {
        val options = aiAccessOptions()
        assertEquals(
            listOf("chatgpt", "gemini", "claude", "openai", "openrouter", "opencode_zen", "opencode_go", "custom"),
            options.map { it.id },
        )
        assertEquals(options.map { it.id }, options.map { it.id }.distinct())
    }

    @Test
    fun onlyChatGptHasNoApiProviderAndAnUnknownIdIsNotAnOption() {
        assertNull(aiAccessOption("chatgpt")!!.provider)
        assertEquals(AiProvider.CLAUDE, aiAccessOption("claude")!!.provider)
        assertNull(aiAccessOption("something_new"))
        assertNull(aiAccessOption("claude_subscription"))
        assertNull(aiAccessOption("gemini_subscription"))
    }

    @Test
    fun everyApiProviderIsOfferedOnce() {
        assertEquals(AiProvider.entries.map { it.key }.toSet(), aiAccessOptions().mapNotNull { it.provider?.key }.toSet())
    }

    @Test
    fun chatGptAndServicesWithoutAContractOfferOnlyAutomatic() {
        listOf("chatgpt", "opencode_zen", "opencode_go", "custom").forEach {
            assertEquals(it, listOf(AiEffort.AUTO), aiEffortOptions(it, "any-model"))
        }
    }

    @Test
    fun claudeOffersOnlyTheLevelsItsModelReports() {
        val haiku = setOf("low", "medium", "high")
        val opus = setOf("low", "medium", "high", "xhigh", "max")
        assertEquals(
            listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH),
            aiEffortOptions("claude", "claude-haiku-5-5", haiku),
        )
        assertEquals(
            listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH, AiEffort.XHIGH, AiEffort.MAX),
            aiEffortOptions("claude", "claude-opus-5-5", opus),
        )
        // The model list was not read, or the model has no effort at all: nothing to choose.
        assertEquals(listOf(AiEffort.AUTO), aiEffortOptions("claude", "claude-opus-5-5", null))
        assertEquals(listOf(AiEffort.AUTO), aiEffortOptions("claude", "claude-old", emptySet()))
    }

    @Test
    fun geminiLevelsFollowTheModelFamily() {
        assertEquals(
            listOf(AiEffort.AUTO, AiEffort.MINIMAL, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH),
            aiEffortOptions("gemini", "gemini-3-flash-preview"),
        )
        assertEquals(
            listOf(AiEffort.AUTO, AiEffort.OFF, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH),
            aiEffortOptions("gemini", "gemini-2.5-flash"),
        )
        // Pro cannot turn thinking off, and an alias that follows new releases offers only what every Gemini model accepts.
        assertEquals(listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH), aiEffortOptions("gemini", "gemini-2.5-pro"))
        assertEquals(listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH), aiEffortOptions("gemini", "gemini-flash-latest"))
    }

    @Test
    fun openAiAndOpenRouterOfferTheSharedLevels() {
        val shared = listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH)
        assertEquals(shared, aiEffortOptions("openai", "gpt-5-mini"))
        assertEquals(shared, aiEffortOptions("openrouter", "openrouter/auto"))
    }

    @Test
    fun aSavedLevelTheModelLacksFallsBackToAutomaticWithoutBeingErased() {
        val haiku = listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH)
        assertEquals(AiEffort.MAX, effectiveAiEffort(AiEffort.MAX, haiku + AiEffort.XHIGH + AiEffort.MAX))
        assertEquals(AiEffort.AUTO, effectiveAiEffort(AiEffort.MAX, haiku))
        assertEquals(AiEffort.LOW, effectiveAiEffort(AiEffort.LOW, haiku))
    }

    @Test
    fun automaticKeepsTodaysDefaultsAndNeverForcesThinkingOffWhereItCannotBe() {
        assertEquals("none", aiEffortWire(AiProvider.GEMINI, "gemini-flash-lite-latest", AiEffort.AUTO))
        assertEquals("none", aiEffortWire(AiProvider.GEMINI, "gemini-2.5-flash", AiEffort.AUTO))
        assertNull(aiEffortWire(AiProvider.GEMINI, "gemini-2.5-pro", AiEffort.AUTO))
        assertNull(aiEffortWire(AiProvider.GEMINI, "gemini-pro-latest", AiEffort.AUTO))
        assertNull(aiEffortWire(AiProvider.GEMINI, "gemini-3-flash-preview", AiEffort.AUTO))
        assertEquals("minimal", aiEffortWire(AiProvider.OPENAI, "gpt-5-mini", AiEffort.AUTO))
        assertNull(aiEffortWire(AiProvider.CLAUDE, "claude-haiku-5-5", AiEffort.AUTO))
        assertNull(aiEffortWire(AiProvider.OPENROUTER, "openrouter/free", AiEffort.AUTO))
        assertEquals("high", aiEffortWire(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.HIGH))
        assertEquals("xhigh", aiEffortWire(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.XHIGH))
        assertEquals("none", aiEffortWire(AiProvider.GEMINI, "gemini-2.5-flash", AiEffort.OFF))
    }

    @Test
    fun theCacheScopeNamesTheEffortOnlyWhenOneIsChosen() {
        assertEquals("gemini/gemini-flash-latest", aiTranslationScope(AiProvider.GEMINI, "gemini-flash-latest", AiEffort.AUTO))
        assertEquals("claude/claude-opus-5-5@high", aiTranslationScope(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.HIGH))
        assertTrue(
            aiTranslationScope(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.HIGH) !=
                aiTranslationScope(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.LOW),
        )
    }

    @Test
    fun choicesAreSavedPerProviderAndTheLegacyModelOnlyBelongsToTheAssistantsService() {
        val prefs = MapPrefs()
        var chatGpt = false
        val choices = AiTranslationChoices(prefs) { chatGpt }
        // Before any choice: the assistant's service, and the older single model belongs to that service only.
        assertEquals("gemini", choices.accessId(AiProvider.GEMINI))
        prefs.map[AI_TRANSLATION_MODEL_PREFERENCE] = "gemini-flash-latest"
        assertEquals("gemini-flash-latest", choices.model("gemini", AiProvider.GEMINI))
        assertEquals("", choices.model("claude", AiProvider.GEMINI))
        // A model the service does not suggest (from an older build or another service) is not inherited.
        prefs.map[AI_TRANSLATION_MODEL_PREFERENCE] = "gpt-5"
        assertEquals("", choices.model("gemini", AiProvider.GEMINI))
        prefs.map[AI_TRANSLATION_MODEL_PREFERENCE] = "gemini-flash-latest"
        // Each provider keeps its own model and effort.
        choices.saveProvider("claude")
        choices.saveModel("claude", "claude-opus-5-5")
        choices.saveEffort("claude", AiEffort.HIGH)
        choices.saveModel("gemini", "gemini-pro-latest")
        choices.saveEffort("gemini", AiEffort.LOW)
        assertEquals("claude", choices.accessId(AiProvider.GEMINI))
        assertEquals("claude-opus-5-5", choices.model("claude", AiProvider.GEMINI))
        assertEquals("gemini-pro-latest", choices.model("gemini", AiProvider.GEMINI))
        assertEquals(AiEffort.HIGH, choices.effort("claude"))
        assertEquals(AiEffort.LOW, choices.effort("gemini"))
        assertEquals(AiEffort.AUTO, choices.effort("openai"))
        // ChatGPT Subscription has its own switch and does not touch the API provider choice.
        chatGpt = true
        assertEquals("chatgpt", choices.accessId(AiProvider.GEMINI))
        chatGpt = false
        assertEquals("claude", choices.accessId(AiProvider.GEMINI))
        // An id from a newer or older build falls back to the assistant's service.
        prefs.map[AI_TRANSLATION_PROVIDER_PREFERENCE] = "unknown_service"
        assertEquals("gemini", choices.accessId(AiProvider.GEMINI))
    }

    @Test
    fun resetClearsEveryKeyTheChoicesUse() {
        val prefs = MapPrefs()
        val choices = AiTranslationChoices(prefs) { false }
        listOf("chatgpt").plus(AiProvider.entries.map { it.key }).forEach {
            choices.saveModel(it, "m")
            choices.saveEffort(it, AiEffort.LOW)
        }
        choices.saveProvider("claude")
        val keys = aiTranslationChoiceKeys()
        assertTrue(prefs.map.keys.all { it in keys })
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun claudeModelListsCarryTheirNamesAndEffortLevels() {
        val body =
            """{"data":[
              {"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","capabilities":{"effort":{"supported":true,
                "low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"xhigh":{"supported":true},"max":{"supported":true}}}},
              {"id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5","capabilities":{"effort":{"supported":true,
                "low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"max":{"supported":false}}}},
              {"id":"claude-old","capabilities":{"effort":{"supported":false}}},
              {"id":"gpt-5-mini"}
            ]}"""
        val models = parseAiModelList(body).associateBy { it.id }
        assertEquals("Claude Opus 5.5", models.getValue("claude-opus-5-5").name)
        assertEquals(setOf("low", "medium", "high", "xhigh", "max"), models.getValue("claude-opus-5-5").efforts)
        assertEquals(setOf("low", "medium", "high"), models.getValue("claude-haiku-5-5").efforts)
        assertEquals(emptySet<String>(), models.getValue("claude-old").efforts)
        assertNull(models.getValue("gpt-5-mini").efforts)
        assertNull(models.getValue("gpt-5-mini").name)
    }

    @Test
    fun theSerializedRequestsCarryEffortOnlyWhereTheProviderDocumentsIt() {
        val messages = listOf(AiWireMessage(AiRole.SYSTEM, "s"), AiWireMessage(AiRole.USER, "u"))
        val claudeHigh = JSONObject(anthropicRequestBody("claude-opus-5-5", messages, reasoningEffort = "high"))
        assertEquals("high", claudeHigh.getJSONObject("output_config").getString("effort"))
        assertFalse(claudeHigh.has("reasoning_effort"))
        assertFalse(claudeHigh.has("thinking"))
        assertFalse(JSONObject(anthropicRequestBody("claude-opus-5-5", messages)).has("output_config"))

        val gemini = JSONObject(chatRequestBody("gemini-3-flash-preview", messages, reasoningEffort = "low"))
        assertEquals("low", gemini.getString("reasoning_effort"))
        // Never both knobs of Gemini: the compatible endpoint takes reasoning_effort alone.
        assertFalse(gemini.has("thinking_config") || gemini.has("thinkingConfig") || gemini.has("extra_body"))
        assertFalse(JSONObject(chatRequestBody("gemini-3-flash-preview", messages)).has("reasoning_effort"))

        // ChatGPT Subscription: the preview documents no reasoning field, so none is sent.
        val chatGpt = JSONObject(chatGptResponsesBody("gpt-x", "instructions", "user"))
        assertFalse(chatGpt.has("reasoning") || chatGpt.has("reasoning_effort"))
    }

    @Test
    fun aKeyedModelSendsTheChosenEffortUsesOnlyItsOwnProvidersKeysAndScopesTheCache() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.CLAUDE, "sk-ant-claudeKey0000000000000000000000000")
            store.add(AiProvider.GEMINI, "AIzaGeminiKey0000000000000000000000000")
            val seen = mutableListOf<AiChatRequest>()
            val transport =
                AiChatTransport { request ->
                    seen += request
                    AiReply("ok")
                }
            val claude =
                aiTranslationModel(
                    AiProvider.CLAUDE,
                    "claude-opus-5-5",
                    AiEffort.HIGH,
                    AiProvider.CLAUDE.baseUrl,
                    store,
                    transport,
                    AiKeyPacer(intervalMs = 0),
                )!!
            assertEquals("claude/claude-opus-5-5@high", claude.scope)
            claude.ask("s", "u")
            assertEquals("high", seen.single().reasoningEffort)
            assertTrue(seen.single().apiKey.startsWith("sk-ant-"))
            assertEquals(AiWireFormat.ANTHROPIC_MESSAGES, seen.single().format)

            seen.clear()
            val automatic =
                aiTranslationModel(
                    AiProvider.CLAUDE,
                    "claude-opus-5-5",
                    AiEffort.AUTO,
                    AiProvider.CLAUDE.baseUrl,
                    store,
                    transport,
                    AiKeyPacer(intervalMs = 0),
                )!!
            assertEquals("claude/claude-opus-5-5", automatic.scope)
            automatic.ask("s", "u")
            assertNull(seen.single().reasoningEffort)

            // A provider without its own key gets no model at all, whatever other keys exist.
            assertNull(aiTranslationModel(AiProvider.OPENAI, "gpt-5-mini", AiEffort.AUTO, AiProvider.OPENAI.baseUrl, store, transport))
            assertNotNull(
                aiTranslationModel(AiProvider.GEMINI, "gemini-flash-latest", AiEffort.AUTO, AiProvider.GEMINI.baseUrl, store, transport),
            )
        }

    @Test
    fun cachedTranslationsNeverCrossProviderModelOrEffort() =
        runBlocking {
            val asked = mutableListOf<String>()

            fun model(scope: String) =
                object : com.kienhoang.dualsubreplay.translation.AiTranslationModel {
                    override val scope = scope

                    override suspend fun ask(
                        system: String,
                        user: String,
                        onText: (String) -> Unit,
                    ): String {
                        asked += scope
                        return """{"sentences":[{"id":1,"rows":["from $scope"]}]}"""
                    }
                }

            val translator =
                com.kienhoang.dualsubreplay.translation
                    .AiSubtitleTranslator(cacheDirectory = null)
            val scopes =
                listOf(
                    aiTranslationScope(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.AUTO),
                    aiTranslationScope(AiProvider.CLAUDE, "claude-opus-5-5", AiEffort.HIGH),
                    aiTranslationScope(AiProvider.CLAUDE, "claude-haiku-5-5", AiEffort.AUTO),
                    aiTranslationScope(AiProvider.GEMINI, "claude-opus-5-5", AiEffort.AUTO),
                )
            scopes.forEach { translator.translateAll(model(it), "de", "en", listOf("Hallo Welt.")) }
            assertEquals("each scope asks the model itself", scopes, asked)
            scopes.forEach { assertEquals("from $it", translator.cachedTranslation(model(it), "de", "en", "Hallo Welt.")) }
            // The same scope again is a cache hit and asks nothing.
            translator.translateAll(model(scopes[1]), "de", "en", listOf("Hallo Welt."))
            assertEquals(scopes.size, asked.size)
        }

    @Test
    fun aRefusedExplicitEffortIsReportedByTheTestAndDroppedForSubtitles() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.GEMINI, "AIzaOnlyKey0000000000000000000000000000")
            val efforts = mutableListOf<String?>()
            val transport =
                AiChatTransport { request ->
                    efforts += request.reasoningEffort
                    if (request.reasoningEffort != null) throw AiChatException(AiErrorKind.UNSUPPORTED_THINKING)
                    AiReply("ok")
                }
            val strict =
                aiTranslationModel(
                    AiProvider.GEMINI,
                    "gemini-3-flash-preview",
                    AiEffort.HIGH,
                    AiProvider.GEMINI.baseUrl,
                    store,
                    transport,
                    AiKeyPacer(intervalMs = 0),
                    dropRefusedEffort = false,
                )!!
            try {
                strict.ask("s", "u")
                fail("the refusal should reach the caller")
            } catch (error: AiChatException) {
                assertEquals(AiErrorKind.UNSUPPORTED_THINKING, error.kind)
            }
            assertEquals(listOf<String?>("high"), efforts)

            efforts.clear()
            val forSubtitles =
                aiTranslationModel(
                    AiProvider.GEMINI,
                    "gemini-3-flash-preview",
                    AiEffort.HIGH,
                    AiProvider.GEMINI.baseUrl,
                    store,
                    transport,
                    AiKeyPacer(intervalMs = 0),
                )!!
            assertEquals("ok", forSubtitles.ask("s", "u"))
            assertEquals(listOf("high", null), efforts)
        }
}
