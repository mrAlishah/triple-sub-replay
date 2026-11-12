package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import com.kienhoang.dualsubreplay.chatgpt.AI_ACCESS_METHOD_PREFERENCE
import com.kienhoang.dualsubreplay.translation.GOOGLE_WAIT_PREFERENCE
import com.kienhoang.dualsubreplay.translation.storedGoogleWait
import com.kienhoang.dualsubreplay.translation.storedTranslationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// Per-tab settings profiles (docs/specs/2026-11-02-per-tab-settings-profiles.md). dual_sub_preferences stays the
// effective settings of the open tab so every reader keeps working; this file keeps one snapshot per tab and a
// Default snapshot for new tabs, and swaps them in when the open tab changes.

internal const val TAB_SETTINGS_PREFERENCES = "tab_settings_profiles"

private const val DEFAULT_PROFILE_KEY = "default"
private const val TAB_PROFILE_PREFIX = "tab_"
private const val MIGRATED_KEY = "migrated"
private const val PROFILE_VERSION = 1

private enum class Kind { STRING, BOOLEAN, INT, FLOAT }

/** The speed this tab's video last played at; recorded from and restored to the page by [BindTabPlaybackSpeed]. */
internal const val TAB_PLAYBACK_SPEED_PREFERENCE = "tab_playback_speed"

// Which preference keys a tab owns, with their stored type. Anything not listed stays global (app language and theme, Chat AI
// Service, keys and sign-ins, saved words, Practice, Anki, known words, ads and updates, the player mode and its automatic
// overlay switches). The AI translation model and thinking keys name a saved choice only; models lists, keys and the ChatGPT
// session stay global.
private val TAB_SETTING_KINDS: Map<String, Kind> =
    buildMap {
        listOf(
            "preferred_caption_language",
            "target_language",
            THIRD_LANGUAGE_PREFERENCE,
            ORIGINAL_VISIBILITY,
            TRANSLATED_VISIBILITY,
            com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE,
            GOOGLE_WAIT_PREFERENCE,
            com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_PROVIDER_PREFERENCE,
            AI_ACCESS_METHOD_PREFERENCE,
            com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_MODEL_PREFERENCE,
            com.kienhoang.dualsubreplay.chatgpt.CHATGPT_MODEL_PREFERENCE,
            SUBTITLE_ORDER_PREFERENCE,
            LEGACY_SUBTITLE_ALIGNMENT_PREFERENCE,
            TRANSLATION_POSITION_PREFERENCE,
            CAPTION_FORMAT_PREFERENCE,
            SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
            SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
            SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
            SUBTITLE_BOX_BACKGROUND_PREFERENCE,
            WORD_LEARNING_TARGET_PREFERENCE,
            STUDY_PLAY_MODE_PREFERENCE,
            SKIP_SILENCE_PREFERENCE,
        ).plus(SubtitleSlot.entries.map { it.alignmentPreference })
            .plus(
                com.kienhoang.dualsubreplay.assistant
                    .aiTranslationChoiceKeys(),
            ).forEach { put(it, Kind.STRING) }
        listOf(
            TRIPLE_SUBTITLES_PREFERENCE,
            com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_PREFERENCE,
            com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_ONLY_PREFERENCE,
            WORD_HIGHLIGHT_ENABLED_PREFERENCE,
            CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE,
            LOCK_OVERLAY_TO_VIDEO_PREFERENCE,
            "landscape_split_enabled",
            NATURAL_SUBTITLES_PREFERENCE,
            HIDE_SOUND_CUES_PREFERENCE,
            SHOW_TRANSLATION_SOURCE_PREFERENCE,
            WORD_LEARNING_ENABLED_PREFERENCE,
            WORD_LEARNING_ACTIVE_ONLY_PREFERENCE,
            TAP_TO_LEARN_PREFERENCE,
            "auto_pronounce",
            BLUR_BOX_PREFERENCE,
        ).plus(SubtitleSlot.entries.map { it.rtlPreference }).forEach { put(it, Kind.BOOLEAN) }
        put(STUDY_REPEAT_COUNT_PREFERENCE, Kind.INT)
        listOf(
            "font_scale",
            PORTRAIT_PANEL_OFFSET_PREFERENCE,
            OVERLAY_VERTICAL_POSITION_PREFERENCE,
            OVERLAY_HORIZONTAL_POSITION_PREFERENCE,
            COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE,
            COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE,
            LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
            TAB_PLAYBACK_SPEED_PREFERENCE,
        ).plus(SubtitleSlot.entries.map { it.scalePreference }).forEach { put(it, Kind.FLOAT) }
    }

/** The preference keys a tab owns; everything else stays global. */
internal val TAB_SETTING_KEYS: Set<String> = TAB_SETTING_KINDS.keys

/** The tab-owned settings of one tab (or of the Default). A missing key means the factory default. */
internal data class TabSettingsProfile(
    val values: Map<String, Any> = emptyMap(),
) {
    /** A copy with [key] set, or cleared back to its factory default when [value] is null. Keys outside the whitelist are refused. */
    fun with(
        key: String,
        value: Any?,
    ): TabSettingsProfile {
        val kind = TAB_SETTING_KINDS[key] ?: return this
        if (value != null && !kind.accepts(value)) return this
        return copy(values = if (value == null) values - key else values + (key to value))
    }
}

private fun Kind.accepts(value: Any): Boolean =
    when (this) {
        Kind.STRING -> value is String
        Kind.BOOLEAN -> value is Boolean
        Kind.INT -> value is Int
        Kind.FLOAT -> value is Float
    }

/** The tab-owned values among [all] (the content of a SharedPreferences). */
internal fun tabSettingsOf(all: Map<String, *>): TabSettingsProfile =
    TabSettingsProfile(
        TAB_SETTING_KINDS.mapNotNull { (key, kind) -> all[key]?.takeIf(kind::accepts)?.let { key to it } }.toMap(),
    )

internal fun encodeTabSettings(profile: TabSettingsProfile): String {
    val values = JSONObject()
    profile.values.toSortedMap().forEach { (key, value) -> values.put(key, if (value is Float) value.toDouble() else value) }
    return JSONObject().put("v", PROFILE_VERSION).put("values", values).toString()
}

/** The profile in [stored], or null when nothing was saved or the text can't be read (the caller then reseeds it). */
internal fun decodeTabSettings(stored: String?): TabSettingsProfile? {
    if (stored.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(stored).getJSONObject("values")
        val values =
            TAB_SETTING_KINDS
                .mapNotNull { (key, kind) ->
                    if (!json.has(key)) return@mapNotNull null
                    val value: Any =
                        when (kind) {
                            Kind.STRING -> json.getString(key)
                            Kind.BOOLEAN -> json.getBoolean(key)
                            Kind.INT -> json.getInt(key)
                            Kind.FLOAT -> json.getDouble(key).toFloat()
                        }
                    key to value
                }.toMap()
        TabSettingsProfile(values)
    }.getOrNull()
}

/** Where profiles are kept; [write] applies all [changes] together (null removes a key). */
internal interface TabProfileStorage {
    fun get(key: String): String?

    fun keys(): Set<String>

    fun write(changes: Map<String, String?>)
}

internal class PreferencesTabProfileStorage(
    private val preferences: SharedPreferences,
) : TabProfileStorage {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun keys(): Set<String> = preferences.all.keys

    override fun write(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        val editor = preferences.edit()
        changes.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.apply()
    }
}

/** Default and per-tab snapshots. Every write that touches more than one entry goes through one [TabProfileStorage.write]. */
internal class TabSettingsStore(
    private val storage: TabProfileStorage,
) {
    fun profile(tabId: Long): TabSettingsProfile? = decodeTabSettings(storage.get(tabKey(tabId)))

    /** The profile new tabs copy; empty means every factory default. */
    fun defaultProfile(): TabSettingsProfile = decodeTabSettings(storage.get(DEFAULT_PROFILE_KEY)) ?: TabSettingsProfile()

    fun saveTab(
        tabId: Long,
        profile: TabSettingsProfile,
    ) {
        val encoded = encodeTabSettings(profile)
        if (storage.get(tabKey(tabId)) != encoded) storage.write(mapOf(tabKey(tabId) to encoded))
    }

    fun saveDefault(profile: TabSettingsProfile) = storage.write(mapOf(DEFAULT_PROFILE_KEY to encodeTabSettings(profile)))

    /**
     * Brings the stored profiles in line with the open [tabs]: a tab that is new since [previousIds] copies the Default,
     * a known tab whose profile is missing or unreadable gets [effective] when it is the open one and the Default otherwise,
     * and profiles of tabs that no longer exist go.
     */
    fun sync(
        tabs: BrowserTabs,
        previousIds: Set<Long>,
        effective: TabSettingsProfile,
    ) {
        val changes = LinkedHashMap<String, String?>()
        val ids = tabs.tabs.map { it.id }.toSet()
        val default = defaultProfile()
        ids.forEach { id ->
            if (profile(id) != null) return@forEach
            val seed = if (id in previousIds && id == tabs.activeId) effective else default
            changes[tabKey(id)] = encodeTabSettings(seed)
        }
        storage
            .keys()
            .filter { it.startsWith(TAB_PROFILE_PREFIX) && it.removePrefix(TAB_PROFILE_PREFIX).toLongOrNull() !in ids }
            .forEach { changes[it] = null }
        storage.write(changes)
    }

    /**
     * Start-up: the first start after the upgrade gives the Default and every tab the current (legacy, shared) settings;
     * later starts only repair missing profiles. Returns the open tab's profile, which the caller applies.
     */
    fun bootstrap(
        tabs: BrowserTabs,
        effective: TabSettingsProfile,
    ): TabSettingsProfile? {
        if (storage.get(MIGRATED_KEY) == null) {
            val snapshot = encodeTabSettings(effective)
            val changes = LinkedHashMap<String, String?>()
            changes[DEFAULT_PROFILE_KEY] = snapshot
            tabs.tabs.forEach { changes[tabKey(it.id)] = snapshot }
            changes[MIGRATED_KEY] = "1"
            storage.write(changes)
        } else {
            sync(tabs, tabs.tabs.map { it.id }.toSet(), effective)
        }
        return tabs.activeId?.let(::profile)
    }

    private fun tabKey(tabId: Long) = TAB_PROFILE_PREFIX + tabId
}

/**
 * Keeps the effective preferences and the open tab's profile together. Everything here runs on the main thread, and
 * [writeThrough] always reads the current preferences and the current open tab, so a delayed preference callback can
 * only ever store the open tab's own values under its own id.
 */
internal class TabSettingsController(
    private val preferences: SharedPreferences,
    private val store: TabSettingsStore,
    private val openTabId: () -> Long?,
) {
    private val mutableDefault = MutableStateFlow(store.defaultProfile())

    /** What the "Default for New Tabs" editor shows. */
    val defaultProfile: StateFlow<TabSettingsProfile> = mutableDefault.asStateFlow()

    val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in TAB_SETTING_KEYS) writeThrough()
        }

    fun snapshot(): TabSettingsProfile = tabSettingsOf(preferences.all)

    fun bootstrap(tabs: BrowserTabs) {
        store.bootstrap(tabs, snapshot())?.let(::apply)
    }

    fun writeThrough() {
        openTabId()?.let { store.saveTab(it, snapshot()) }
    }

    /** After the tab list changed from [previousIds]: new tabs copy the Default, closed tabs lose their profile. */
    fun tabsChanged(
        previousIds: Set<Long>,
        tabs: BrowserTabs,
    ) {
        if (tabs.tabs.map { it.id }.toSet() != previousIds) store.sync(tabs, previousIds, snapshot())
    }

    /**
     * The open tab changed from [outgoing] to [tabs]'s open tab: store the leaving tab's values (unless it was closed), then
     * make the entering tab's profile the effective settings. Returns true when the effective settings changed.
     */
    fun activate(
        outgoing: Long?,
        tabs: BrowserTabs,
    ): Boolean {
        val incoming = tabs.activeId
        if (outgoing != null && outgoing != incoming && tabs.tabs.any { it.id == outgoing }) store.saveTab(outgoing, snapshot())
        incoming ?: return false
        return apply(store.profile(incoming) ?: snapshot().also { store.saveTab(incoming, it) })
    }

    /** Replaces the open tab's settings with a copy of the Default. Returns true when they changed. */
    fun resetOpenTab(): Boolean {
        val id = openTabId() ?: return false
        val default = store.defaultProfile()
        store.saveTab(id, default)
        return apply(default)
    }

    /**
     * "Use this tab's settings as the default": the Default becomes a copy of the open tab's tab-owned settings, including what
     * the Default editor has no control for (dragged positions, split, play modes, blur box, AI model and thinking). Existing
     * tabs and the playing video do not change.
     */
    fun useOpenTabAsDefault() {
        if (openTabId() != null) updateDefault { snapshot() }
    }

    fun updateDefault(change: (TabSettingsProfile) -> TabSettingsProfile) {
        val updated = change(mutableDefault.value)
        if (updated == mutableDefault.value) return
        store.saveDefault(updated)
        mutableDefault.value = updated
    }

    private fun apply(profile: TabSettingsProfile): Boolean {
        if (snapshot() == profile) return false
        val editor = preferences.edit()
        TAB_SETTING_KINDS.keys.forEach { key ->
            when (val value = profile.values[key]) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.apply()
        return true
    }
}

/**
 * openVideo ignores a video that is already open, so a tab switch that changed the settings but kept the same video must
 * load it again (bumping the load generation, which drops results still in flight for the old settings). A different video
 * always loads, and an unchanged setting keeps the running load.
 */
internal fun reloadsSameVideoAfterSwitch(
    settingsChanged: Boolean,
    videoBefore: String?,
    videoAfter: String?,
): Boolean = settingsChanged && videoBefore != null && videoBefore == videoAfter

/** The tab-owned fields of the UI state, read from [preferences] (the effective settings, or a [TabSettingsProfile] view). */
internal fun DualSubUiState.withTabSettings(preferences: SharedPreferences): DualSubUiState {
    val mode = preferences.storedTranslationMode()
    val position = storedTranslationPosition(preferences.getString(TRANSLATION_POSITION_PREFERENCE, null))
    return copy(
        sourcePreference = storedSourcePreference(preferences.getString("preferred_caption_language", "auto")),
        targetLanguage =
            preferences
                .getString("target_language", DEFAULT_SECOND_LANGUAGE)
                ?.takeIf(com.kienhoang.dualsubreplay.translation.TranslationLanguages::isSupported)
                ?: DEFAULT_SECOND_LANGUAGE,
        originalVisibility = storedCaptionVisibility(preferences.getString(ORIGINAL_VISIBILITY, null)),
        translatedVisibility = storedCaptionVisibility(preferences.getString(TRANSLATED_VISIBILITY, null)),
        fontScale = normalizeFontScale(preferences.getFloat("font_scale", 1f)),
        captionFormat = storedCaptionFormat(preferences.getString(CAPTION_FORMAT_PREFERENCE, null)),
        translationPosition = position,
        translationEngine = mode.engine,
        aiTranslation = mode.ai,
        aiTranslationOnly = mode.aiOnly,
        googleWait = storedGoogleWait(preferences.getString(GOOGLE_WAIT_PREFERENCE, null)),
        tripleSubtitlesEnabled = preferences.getBoolean(TRIPLE_SUBTITLES_PREFERENCE, false),
        thirdLanguage = storedThirdLanguage(preferences.getString(THIRD_LANGUAGE_PREFERENCE, null)),
        slotRtl = storedSlotRtl(preferences),
        subtitleOrder = storedSubtitleOrder(preferences.getString(SUBTITLE_ORDER_PREFERENCE, null), position),
        slotFontScale = storedSlotFontScale(preferences),
        slotAlignment = storedSlotAlignment(preferences),
        originalColorKey =
            storedSubtitleColorKey(
                preferences.getString(SUBTITLE_ORIGINAL_COLOR_PREFERENCE, null),
                DEFAULT_ORIGINAL_COLOR_KEY,
            ),
        translatedColorKey =
            storedSubtitleColorKey(preferences.getString(SUBTITLE_TRANSLATED_COLOR_PREFERENCE, null), DEFAULT_TRANSLATED_COLOR_KEY),
        highlightColorKey =
            storedSubtitleColorKey(preferences.getString(SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE, null), DEFAULT_HIGHLIGHT_COLOR_KEY),
        wordHighlightEnabled = storedFeatureEnabled(preferences.getBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, true)),
        customColorsEnabled = storedFeatureEnabled(preferences.getBoolean(CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE, true)),
        portraitPanelOffsetFraction =
            normalizePortraitPanelOffsetFraction(
                preferences.getFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION),
            ),
        landscapeSplitEnabled = preferences.getBoolean("landscape_split_enabled", true),
        lockOverlayToVideo = storedFeatureEnabled(preferences.getBoolean(LOCK_OVERLAY_TO_VIDEO_PREFERENCE, false)),
        naturalSubtitlesEnabled = storedFeatureEnabled(preferences.getBoolean(NATURAL_SUBTITLES_PREFERENCE, true)),
        hideSoundCues = preferences.getBoolean(HIDE_SOUND_CUES_PREFERENCE, true),
        showTranslationSource = preferences.getBoolean(SHOW_TRANSLATION_SOURCE_PREFERENCE, false),
        wordLearningEnabled = storedFeatureEnabled(preferences.getBoolean(WORD_LEARNING_ENABLED_PREFERENCE, true)),
        wordLearningTarget =
            preferences.getString(WORD_LEARNING_TARGET_PREFERENCE, DEFAULT_WORD_LEARNING_TARGET) ?: DEFAULT_WORD_LEARNING_TARGET,
        wordLearningActiveOnly = storedFeatureEnabled(preferences.getBoolean(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, true)),
        autoPronounce = preferences.getBoolean("auto_pronounce", true),
        tapToLearnEnabled = storedFeatureEnabled(preferences.getBoolean(TAP_TO_LEARN_PREFERENCE, true)),
        studyPlayMode = storedStudyPlayMode(preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null)),
    )
}

/** A read-only SharedPreferences over a profile, so the existing `stored*` readers can show a profile that is not in force. */
internal class ProfilePreferences(
    private val values: Map<String, Any>,
) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only profile view")

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
