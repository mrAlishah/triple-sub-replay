package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSettingsTest {
    private class FakeStorage : TabProfileStorage {
        val data = mutableMapOf<String, String>()

        override fun get(key: String) = data[key]

        override fun keys(): Set<String> = data.keys

        override fun write(changes: Map<String, String?>) = changes.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
    }

    private class FakePrefs : SharedPreferences {
        val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()

        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues

        override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue

        override fun contains(key: String?) = data.containsKey(key)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                val puts = mutableMapOf<String, Any>()
                val removes = mutableSetOf<String>()

                override fun putString(key: String, value: String?) = apply { puts[key] = value!! }

                override fun putStringSet(key: String, values: MutableSet<String>?) = this

                override fun putInt(key: String, value: Int) = apply { puts[key] = value }

                override fun putLong(key: String, value: Long) = apply { puts[key] = value }

                override fun putFloat(key: String, value: Float) = apply { puts[key] = value }

                override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }

                override fun remove(key: String) = apply { removes += key }

                override fun clear() = this

                override fun commit(): Boolean {
                    apply()
                    return true
                }

                override fun apply() {
                    removes.forEach(data::remove)
                    data.putAll(puts)
                }
            }
    }

    private class Setup(
        tabs: BrowserTabs,
    ) {
        val prefs = FakePrefs()
        val storage = FakeStorage()
        var tabs = tabs
        val store = TabSettingsStore(storage)
        val controller = TabSettingsController(prefs, store) { this.tabs.activeId }

        /** The same sequence AppViewModel runs for a tab switch. */
        fun switchTo(id: Long) {
            val outgoing = tabs.activeId
            tabs = tabs.switchedTo(id)
            controller.activate(outgoing, tabs)
        }

        fun open(): Long {
            val outgoing = tabs.activeId
            val before = tabs.tabs.map { it.id }.toSet()
            tabs = tabs.opened("https://m.youtube.com/")!!
            controller.tabsChanged(before, tabs)
            controller.activate(outgoing, tabs)
            return tabs.activeId!!
        }

        fun close(id: Long) {
            val outgoing = tabs.activeId
            val before = tabs.tabs.map { it.id }.toSet()
            tabs = tabs.closed(id)
            controller.tabsChanged(before, tabs)
            if (id == outgoing) controller.activate(outgoing, tabs)
        }

        /** What the preference listener does after the user changes a setting. */
        fun edit(block: SharedPreferences.Editor.() -> Unit) {
            prefs.edit().apply(block).apply()
            controller.writeThrough()
        }
    }

    private fun twoTabs() = BrowserTabs().opened("https://m.youtube.com/")!!.opened("https://m.youtube.com/", activate = false)!!.switchedTo(1)

    private fun started(tabs: BrowserTabs = twoTabs()) = Setup(tabs).also { it.controller.bootstrap(tabs) }

    @Test
    fun profilesSurviveEncodingAndCorruptTextIsRefused() {
        val profile = TabSettingsProfile().with("font_scale", 1.5f).with("target_language", "fa").with(TRIPLE_SUBTITLES_PREFERENCE, true)
        assertEquals(profile, decodeTabSettings(encodeTabSettings(profile)))
        assertNull(decodeTabSettings("{not json"))
        assertNull(decodeTabSettings(null))
    }

    @Test
    fun aProfileRefusesUnknownKeysAndWrongTypes() {
        val empty = TabSettingsProfile()
        assertEquals(empty, empty.with("app_language", "fa"))
        assertEquals(empty, empty.with("font_scale", "big"))
        assertEquals(empty, TabSettingsProfile().with("font_scale", 1.2f).with("font_scale", null))
    }

    @Test
    fun accountsKeysAndGlobalChoicesAreNotPartOfAProfile() {
        listOf(
            "app_language",
            "app_color_theme",
            "app_theme_accent",
            "ai_models_unified",
            "known_words_mode",
            "send_to_app",
            "ai_provider",
            "player_experience_mode",
            "pronunciation_volume",
            "browser_tabs",
            "different_tab_accounts",
        ).forEach { assertFalse(it, it in TAB_SETTING_KEYS) }
        // The selections are per tab; the connections behind them are not stored here.
        listOf("ai_translation_provider", "ai_access_method", "chatgpt_translation_model", "ai_translation_model_chatgpt").forEach {
            assertTrue(it, it in TAB_SETTING_KEYS)
        }
    }

    @Test
    fun firstStartGivesTheDefaultAndEveryTabTheLegacySettingsAndRunsOnce() {
        val tabs = twoTabs()
        val setup = Setup(tabs)
        setup.prefs.data["target_language"] = "de"
        setup.prefs.data["font_scale"] = 1.3f
        setup.prefs.data["browser_tabs"] = "kept"
        setup.controller.bootstrap(tabs)
        val legacy = TabSettingsProfile().with("target_language", "de").with("font_scale", 1.3f)
        assertEquals(legacy, setup.store.defaultProfile())
        assertEquals(legacy, setup.store.profile(1))
        assertEquals(legacy, setup.store.profile(2))
        assertEquals("kept", setup.prefs.data["browser_tabs"])

        // A later start must not read the (now different) preferences again.
        setup.store.saveTab(1, legacy.with("target_language", "fa"))
        setup.prefs.data["target_language"] = "ja"
        setup.controller.bootstrap(tabs)
        assertEquals("fa", setup.prefs.data["target_language"])
        assertEquals(legacy, setup.store.defaultProfile())
    }

    @Test
    fun anUnreadableProfileIsReseededAndAnOrphanIsDropped() {
        val setup = started()
        setup.storage.data["tab_2"] = "garbage"
        setup.storage.data["tab_9"] = encodeTabSettings(TabSettingsProfile())
        setup.controller.bootstrap(setup.tabs)
        assertEquals(TabSettingsProfile(), setup.store.profile(2))
        assertNull(setup.storage.data["tab_9"])
    }

    @Test
    fun theLeavingTabKeepsItsOwnSettingsAcrossTab1Tab2Tab1() {
        val setup = started()
        setup.controller.updateDefault { it.with("target_language", "en") }
        setup.edit {
            putString("target_language", "fa")
            putBoolean(TRIPLE_SUBTITLES_PREFERENCE, false)
            putFloat("font_scale", 1.5f)
            putString("translation_engine", "google_web")
        }
        setup.switchTo(2)
        // Tab 2 was created before the edit and never changed: it has the legacy (empty) settings, not tab 1's.
        assertNull(setup.prefs.data["target_language"])
        setup.edit {
            putString("target_language", "en")
            putBoolean(TRIPLE_SUBTITLES_PREFERENCE, true)
            putString("third_language", "fa")
            putBoolean("ai_subtitle_translation", true)
            putBoolean("ai_subtitle_translation_only", true)
            putString("ai_access_method", "chatgpt")
        }
        setup.switchTo(1)
        assertEquals("fa", setup.prefs.data["target_language"])
        assertEquals(1.5f, setup.prefs.data["font_scale"])
        assertNull(setup.prefs.data["ai_access_method"])
        assertNull(setup.prefs.data["third_language"])
        setup.switchTo(2)
        assertEquals("chatgpt", setup.prefs.data["ai_access_method"])
        assertEquals(true, setup.prefs.data["ai_subtitle_translation_only"])
        assertNull(setup.prefs.data["font_scale"])
    }

    @Test
    fun aDelayedPreferenceCallbackNeverWritesOneTabsValuesIntoAnother() {
        val setup = started()
        setup.edit { putFloat("font_scale", 1.5f) }
        val tab1 = setup.store.profile(1)
        setup.switchTo(2)
        setup.controller.writeThrough() // the callback of the edit above arriving late
        assertEquals(tab1, setup.store.profile(1))
        assertNull(setup.store.profile(2)!!.values["font_scale"])
    }

    @Test
    fun editingTheDefaultNeverTouchesTabsAndANewTabCopiesTheDefaultAtCreation() {
        val setup = started()
        setup.edit { putString("target_language", "fa") }
        setup.controller.updateDefault { it.with("target_language", "es").with("font_scale", 1.2f) }
        assertEquals("fa", setup.prefs.data["target_language"]) // the open tab and its video are untouched
        assertNull(setup.store.profile(2)!!.values["target_language"])
        val third = setup.open()
        assertEquals("es", setup.prefs.data["target_language"])
        assertEquals(1.2f, setup.prefs.data["font_scale"])
        setup.controller.updateDefault { it.with("target_language", "ja") }
        assertEquals("es", setup.store.profile(third)!!.values["target_language"]) // snapshot, not live inheritance
        setup.switchTo(1)
        assertEquals("fa", setup.prefs.data["target_language"])
    }

    @Test
    fun aTabOpenedInTheBackgroundCopiesTheDefaultAtThatMoment() {
        val setup = started()
        setup.controller.updateDefault { it.with("target_language", "es") }
        val before = setup.tabs.tabs.map { it.id }.toSet()
        setup.tabs = setup.tabs.opened("https://m.youtube.com/", activate = false)!!
        setup.controller.tabsChanged(before, setup.tabs)
        setup.controller.updateDefault { it.with("target_language", "ja") }
        assertEquals("es", setup.store.profile(3)!!.values["target_language"])
    }

    @Test
    fun resetCurrentTabReplacesOnlyThatTabWithTheDefault() {
        val setup = started()
        setup.controller.updateDefault { it.with("font_scale", 1.1f) }
        setup.edit { putFloat("font_scale", 1.6f) }
        setup.switchTo(2)
        setup.edit { putFloat("font_scale", 0.8f) }
        setup.switchTo(1)
        assertTrue(setup.controller.resetOpenTab())
        assertEquals(1.1f, setup.prefs.data["font_scale"])
        assertEquals(1.1f, setup.store.profile(1)!!.values["font_scale"])
        assertEquals(0.8f, setup.store.profile(2)!!.values["font_scale"])
        assertEquals(1.1f, setup.store.defaultProfile().values["font_scale"])
    }

    @Test
    fun closingATabDeletesItsProfileAndANewTabNeverReusesItsId() {
        val setup = started()
        setup.switchTo(2)
        setup.edit { putFloat("font_scale", 0.9f) }
        setup.close(2)
        assertNull(setup.store.profile(2))
        assertEquals(1L, setup.tabs.activeId)
        val next = setup.open()
        assertNotEquals(2L, next)
        assertNull(setup.prefs.data["font_scale"]) // the new tab starts from the Default, not from the closed tab
    }

    @Test
    fun closingTheLeavingTabDoesNotBringItsProfileBack() {
        val setup = started()
        setup.close(1)
        assertNull(setup.store.profile(1))
        assertNotNull2(setup.store.profile(2))
    }

    private fun assertNotNull2(value: Any?) = assertTrue(value != null)

    @Test
    fun theTabViewOfAProfileMatchesTheExampleOfTheRequest() {
        val tab1 =
            TabSettingsProfile()
                .with("target_language", "fa")
                .with(TRIPLE_SUBTITLES_PREFERENCE, false)
                .with("font_scale", 1.5f)
        val tab2 =
            TabSettingsProfile()
                .with("target_language", "en")
                .with(TRIPLE_SUBTITLES_PREFERENCE, true)
                .with(THIRD_LANGUAGE_PREFERENCE, "fa")
                .with("ai_subtitle_translation", true)
                .with("ai_subtitle_translation_only", true)
                .with(SubtitleSlot.SECOND.alignmentPreference, "center")
        val state1 = DualSubUiState().withTabSettings(ProfilePreferences(tab1.values))
        val state2 = DualSubUiState().withTabSettings(ProfilePreferences(tab2.values))
        assertEquals("fa", state1.targetLanguage)
        assertFalse(state1.tripleSubtitlesEnabled)
        assertEquals(1.5f, state1.fontScale)
        assertEquals("en", state2.targetLanguage)
        assertEquals("fa", state2.thirdLanguage)
        assertTrue(state2.aiTranslation && state2.aiTranslationOnly)
        assertEquals(SubtitleAlignment.CENTER, state2.slotAlignment[SubtitleSlot.SECOND])
    }

    /** The request's end-to-end example: a Default, Tab 1 and Tab 2, a restart, a third tab and a changed Default. */
    @Test
    fun theRequestedScenarioHoldsAcrossSwitchesRestartAndNewTabs() {
        val start = BrowserTabs().opened("https://m.youtube.com/")!!
        val setup = Setup(start).also { it.controller.bootstrap(start) }
        // Default: Google, English Subtitle 1, Subtitle 2 off, 100 %.
        setup.controller.updateDefault {
            it.with("target_language", "en").with(TRIPLE_SUBTITLES_PREFERENCE, false).with("font_scale", 1f)
        }
        // Tab 1 (the first tab): Persian Subtitle 1, 150 %, right alignment, Google.
        setup.edit {
            putString("target_language", "fa")
            putFloat("font_scale", 1.5f)
            putString(SubtitleSlot.SECOND.alignmentPreference, "right")
            putString("ai_translation_provider", "gemini")
            putString("ai_translation_model_gemini", "model-a")
            putString("ai_translation_effort_gemini", "low")
            putFloat("tab_playback_speed", 1.25f)
            putFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, 0.3f)
            putString(SUBTITLE_ORIGINAL_COLOR_PREFERENCE, "yellow")
        }
        // Tab 2: a new tab copies the Default, then ChatGPT Subscription, Persian Subtitle 2, 120 %, centered.
        val second = setup.open()
        assertEquals("en", setup.prefs.data["target_language"])
        assertEquals(1f, setup.prefs.data["font_scale"])
        assertNull(setup.prefs.data["ai_translation_model_gemini"])
        assertNull(setup.prefs.data["tab_playback_speed"])
        setup.edit {
            putString("ai_access_method", "chatgpt")
            putString("chatgpt_translation_model", "gpt-x")
            putBoolean("ai_subtitle_translation", true)
            putBoolean("ai_subtitle_translation_only", true)
            putBoolean(TRIPLE_SUBTITLES_PREFERENCE, true)
            putString(THIRD_LANGUAGE_PREFERENCE, "fa")
            putFloat("font_scale", 1.2f)
            putString(SubtitleSlot.SECOND.alignmentPreference, "center")
            putFloat("tab_playback_speed", 0.75f)
            putFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, 0.6f)
        }
        // Tab 1 → Tab 2 → Tab 1 restores everything, and the global ChatGPT/keys/Chat service are never in a profile.
        setup.switchTo(1)
        assertEquals("fa", setup.prefs.data["target_language"])
        assertEquals(1.5f, setup.prefs.data["font_scale"])
        assertEquals("right", setup.prefs.data[SubtitleSlot.SECOND.alignmentPreference])
        assertEquals("model-a", setup.prefs.data["ai_translation_model_gemini"])
        assertEquals(1.25f, setup.prefs.data["tab_playback_speed"])
        assertEquals(0.3f, setup.prefs.data[PORTRAIT_PANEL_OFFSET_PREFERENCE])
        assertNull(setup.prefs.data["ai_access_method"])
        setup.switchTo(second)
        assertEquals("chatgpt", setup.prefs.data["ai_access_method"])
        assertEquals("gpt-x", setup.prefs.data["chatgpt_translation_model"])
        assertEquals(0.75f, setup.prefs.data["tab_playback_speed"])
        assertEquals(0.6f, setup.prefs.data[PORTRAIT_PANEL_OFFSET_PREFERENCE])
        assertEquals(true, setup.prefs.data[TRIPLE_SUBTITLES_PREFERENCE])
        assertEquals("en", setup.store.defaultProfile().values["target_language"]) // the Default never moved

        // Restart: a new controller over the same storage and preferences, with the preferences even reset to another tab's values.
        val restarted = TabSettingsController(setup.prefs, TabSettingsStore(setup.storage)) { setup.tabs.activeId }
        setup.prefs.data["target_language"] = "stale"
        restarted.bootstrap(setup.tabs)
        assertEquals("en", setup.prefs.data["target_language"])
        assertEquals(1.2f, setup.prefs.data["font_scale"])

        // A changed Default only reaches tabs created afterwards.
        setup.controller.updateDefault { it.with("target_language", "de") }
        val third = setup.open()
        assertEquals("de", setup.prefs.data["target_language"])
        setup.switchTo(1)
        assertEquals("fa", setup.prefs.data["target_language"])
        setup.switchTo(second)
        assertEquals("en", setup.prefs.data["target_language"])
        assertNotEquals(second, third)
    }

    @Test
    fun theSettingsOfATabSurviveChangingItsVideo() {
        val setup = started()
        setup.edit { putString("target_language", "fa") }
        setup.tabs = setup.tabs.withActiveUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        setup.controller.tabsChanged(setOf(1L, 2L), setup.tabs)
        setup.controller.writeThrough()
        assertEquals("fa", setup.store.profile(1)!!.values["target_language"])
    }

    @Test
    fun intAndFloatValuesOfStageTwoRoundTrip() {
        val profile = TabSettingsProfile().with(STUDY_REPEAT_COUNT_PREFERENCE, 3).with("tab_playback_speed", 1.75f)
        assertEquals(profile, decodeTabSettings(encodeTabSettings(profile)))
    }

    @Test
    fun aSwitchThatKeepsTheSameVideoReloadsItOnlyWhenTheSettingsChanged() {
        assertTrue(reloadsSameVideoAfterSwitch(true, "a", "a"))
        assertFalse(reloadsSameVideoAfterSwitch(false, "a", "a"))
        assertFalse(reloadsSameVideoAfterSwitch(true, "a", "b")) // a different video loads by itself
        assertFalse(reloadsSameVideoAfterSwitch(true, null, null))
    }

    @Test
    fun aSettingsScreenOpenedForOneTabClosesWhenAnotherTabOpens() {
        assertFalse(settingsOpenedForOtherTab(3L, 3L))
        assertTrue(settingsOpenedForOtherTab(3L, 4L))
        assertTrue(settingsOpenedForOtherTab(3L, null))
    }

    @Test
    fun aRetainedProfileCannotBeAttachedToANewTabAfterUnreadableTabData() {
        val setup = started()
        setup.edit { putString("target_language", "fa") }
        // The tabs were lost; the id floor (2 tabs were issued) gives the new first tab id 3, never 1.
        val fresh = initialBrowserTabs(stored = "{broken", lastUrl = null, idFloor = 3L)
        assertEquals(3L, fresh.activeId)
        assertEquals(4L, fresh.freeId)
        setup.tabs = fresh
        setup.controller.bootstrap(fresh)
        assertNull(setup.store.profile(1))
        assertNull(setup.store.profile(2))
        assertTrue(setup.store.profile(3) != null)
    }

    @Test
    fun theIdFloorRaisesTheCounterOfReadableTabsToo() {
        val saved = BrowserTabs().opened("https://m.youtube.com/")!!
        val restored = initialBrowserTabs(encodeBrowserTabs(saved), lastUrl = null, idFloor = 9L)
        assertEquals(9L, restored.opened("https://m.youtube.com/")!!.active?.id)
    }

    @Test
    fun aTabSpeedIsRecordedOnlyWhenStableAndNeverFromAnotherTabsVideo() {
        val tracker = TabSpeedTracker()
        fun sample(rate: Float, saved: Float? = null, video: String = "v", open: String? = "v", allowed: Boolean = true) =
            tracker.onSample(1L, open, video, rate, saved, allowed)
        assertEquals(TabSpeedAction.None, sample(1.5f))
        assertEquals(TabSpeedAction.None, sample(1.5f))
        assertEquals(TabSpeedAction.Record(1.5f), sample(1.5f))
        // Ads (4x), another video's sample, and skip silence never count.
        assertEquals(TabSpeedAction.None, sample(4f))
        assertEquals(TabSpeedAction.None, sample(1.25f, video = "other"))
        val silent = TabSpeedTracker()
        repeat(4) { assertEquals(TabSpeedAction.None, silent.onSample(1L, "v", "v", 1.5f, null, false)) }
    }

    @Test
    fun aSavedTabSpeedIsRestoredBeforeAnythingIsRecorded() {
        val tracker = TabSpeedTracker()
        // YouTube carried 1.5x over from another tab; this tab saved 0.75x.
        assertEquals(TabSpeedAction.Apply(0.75f), tracker.onSample(2L, "v", "v", 1.5f, 0.75f, true))
        assertEquals(TabSpeedAction.None, tracker.onSample(2L, "v", "v", 0.75f, 0.75f, true))
        repeat(3) { assertEquals(TabSpeedAction.None, tracker.onSample(2L, "v", "v", 0.75f, 0.75f, true)) }
        // A page that refuses the speed is not recorded over the saved one.
        val stubborn = TabSpeedTracker()
        repeat(3) { assertEquals(TabSpeedAction.Apply(0.75f), stubborn.onSample(2L, "w", "w", 1.5f, 0.75f, true)) }
        repeat(5) { assertEquals(TabSpeedAction.None, stubborn.onSample(2L, "w", "w", 1.5f, 0.75f, true)) }
    }

    @Test
    fun useThisTabAsDefaultCopiesEverythingTheDefaultEditorHasNoControlFor() {
        val setup = started()
        setup.edit {
            putFloat(OVERLAY_VERTICAL_POSITION_PREFERENCE, 0.2f)
            putFloat(LANDSCAPE_VIDEO_FRACTION_PREFERENCE, 0.7f)
            putString(STUDY_PLAY_MODE_PREFERENCE, "repeat")
            putBoolean(BLUR_BOX_PREFERENCE, true)
            putString("ai_translation_model_gemini", "model-a")
            putString("ai_translation_effort_gemini", "low")
        }
        val before2 = setup.store.profile(2)
        setup.controller.useOpenTabAsDefault()
        val default = setup.store.defaultProfile()
        assertEquals(0.2f, default.values[OVERLAY_VERTICAL_POSITION_PREFERENCE])
        assertEquals("repeat", default.values[STUDY_PLAY_MODE_PREFERENCE])
        assertEquals("low", default.values["ai_translation_effort_gemini"])
        assertEquals(before2, setup.store.profile(2)) // existing tabs keep their own
        assertEquals(0.7f, setup.prefs.data[LANDSCAPE_VIDEO_FRACTION_PREFERENCE]) // and the open tab is not touched
        val third = setup.open()
        assertEquals(true, setup.prefs.data[BLUR_BOX_PREFERENCE])
        assertEquals("model-a", setup.prefs.data["ai_translation_model_gemini"])
        assertNotEquals(2L, third)
    }

    @Test
    fun rapidSwitchingKeepsEveryTabsLastEdit() {
        val setup = started()
        setup.edit { putFloat("font_scale", 1.1f) }
        repeat(20) { round ->
            setup.switchTo(2)
            if (round == 0) setup.edit { putFloat("font_scale", 1.4f) }
            setup.controller.writeThrough() // a late callback in the middle of the burst
            setup.switchTo(1)
            setup.controller.writeThrough()
        }
        assertEquals(1.1f, setup.store.profile(1)!!.values["font_scale"])
        assertEquals(1.4f, setup.store.profile(2)!!.values["font_scale"])
        assertEquals(1.1f, setup.prefs.data["font_scale"])
    }

    @Test
    fun anUnreadableDefaultFallsBackToFactoryDefaultsWithoutTouchingTabs() {
        val setup = started()
        setup.edit { putString("target_language", "fa") }
        setup.storage.data["default"] = "garbage"
        assertEquals(TabSettingsProfile(), setup.store.defaultProfile())
        assertEquals("fa", setup.store.profile(1)!!.values["target_language"])
    }

    @Test
    fun noProfileKeyCanHoldASecretOrAGlobalAccount() {
        val suspicious = listOf("api_key", "apikey", "token", "secret", "password", "cookie", "oauth", "credential", "account")
        TAB_SETTING_KEYS.forEach { key -> suspicious.forEach { assertFalse(key, key.contains(it, ignoreCase = true)) } }
    }

    private class MapChoicePrefs(
        private val prefs: FakePrefs,
    ) : com.kienhoang.dualsubreplay.assistant.AiTranslationPrefs {
        override fun get(key: String): String? = prefs.data[key] as? String

        override fun put(key: String, value: String?) {
            if (value == null) prefs.data.remove(key) else prefs.data[key] = value
        }
    }

    @Test
    fun aTabWithoutASavedServiceFollowsTheAssistantOnceAndThenStaysIndependentOfChat() {
        val setup = started()
        val choices = com.kienhoang.dualsubreplay.assistant.AiTranslationChoices(MapChoicePrefs(setup.prefs)) { false }
        val gemini = com.kienhoang.dualsubreplay.assistant.AiProvider.GEMINI
        val openAi = com.kienhoang.dualsubreplay.assistant.AiProvider.OPENAI
        // Tab 1 has no saved service: it takes the chat's service (Gemini) and keeps it, as before this feature.
        assertEquals(gemini.key, choices.accessId(gemini))
        setup.controller.writeThrough()
        // Tab 2 also has none: it takes the chat's service at that moment, independent of tab 1's.
        setup.switchTo(2)
        assertEquals(openAi.key, choices.accessId(openAi))
        setup.controller.writeThrough()
        // Chat moves back to Gemini; both tabs keep their own translation service.
        setup.switchTo(1)
        assertEquals(gemini.key, choices.accessId(openAi))
        setup.switchTo(2)
        assertEquals(openAi.key, choices.accessId(gemini))
    }
}
