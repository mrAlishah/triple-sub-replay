package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.assistant.aiTranslationChoiceKeys

private fun spec(
    scope: SettingScope,
    type: SettingType,
) = SettingSpec(scope, type)

/**
 * The fork's own stored settings (triple subtitles, the central AI connections, tabs), kept apart from
 * [SETTING_SPECS] so an upstream sync only has to keep the one `+ FORK_SETTING_SPECS` there. Same rules as
 * upstream: a shared link never carries anything that uses an API key, and phone state never leaves the phone.
 */
internal val FORK_SETTING_SPECS: Map<String, SettingSpec> =
    mapOf(
        // The three subtitle slots: what they show, how, and where; each slot is keyed by its stable id.
        "triple_subtitles_enabled" to spec(SettingScope.SHARE, SettingType.BOOLEAN),
        "third_language" to spec(SettingScope.SHARE, SettingType.STRING),
        "subtitle_order" to spec(SettingScope.SHARE, SettingType.STRING),
        "subtitle_alignment" to spec(SettingScope.SHARE, SettingType.STRING),
        "show_translation_source" to spec(SettingScope.SHARE, SettingType.BOOLEAN),
    ) +
        listOf("original", "second", "third").flatMap { slot ->
            listOf(
                "font_scale_$slot" to spec(SettingScope.SHARE, SettingType.FLOAT),
                "rtl_$slot" to spec(SettingScope.SHARE, SettingType.BOOLEAN),
                "subtitle_alignment_$slot" to spec(SettingScope.SHARE, SettingType.STRING),
            )
        } +
        // AI: backups only, like upstream's AI keys. Provider, model and effort choices, never a key or a session.
        aiTranslationChoiceKeys().associateWith { spec(SettingScope.BACKUP, SettingType.STRING) } +
        mapOf(
            "ai_access_method" to spec(SettingScope.BACKUP, SettingType.STRING),
            "ai_assistant_service_access" to spec(SettingScope.BACKUP, SettingType.STRING),
            // Travels with the unified model list it describes, so a restore never re-runs that migration over it.
            "ai_models_unified" to spec(SettingScope.BACKUP, SettingType.STRING),
            "ai_show_response_source" to spec(SettingScope.BACKUP, SettingType.BOOLEAN),
            "chatgpt_translation_model" to spec(SettingScope.BACKUP, SettingType.STRING),
            // Never: counters and per-tab state of this phone.
            "browser_tab_id_floor" to spec(SettingScope.NEVER, SettingType.INT),
            "tab_playback_speed" to spec(SettingScope.NEVER, SettingType.FLOAT),
        )
