# Per-tab settings profiles (Stage 1)

Status: Stage 1 hardened and Stage 2 implemented (owner request and approval 2026-11-02). Device checks are pending (see the implementation record).

## Goal

Each YouTube tab remembers its own subtitle, translation and appearance settings and restores them on switch and after a restart. One editable **Default for New Tabs** profile seeds new tabs by copy (snapshot-on-creation). Accounts, keys and app-wide services stay global.

## Design (Option A, approved)

- `dual_sub_preferences` stays the **effective** settings of the open tab, so every existing reader and setter (ViewModel, translators, `WordPronouncer`, AI assistant actions) keeps working unchanged.
- `TabSettingsStore` (`tab_settings_profiles` preferences, fork-owned, `ui/TabSettings.kt`) keeps one JSON snapshot per tab id plus one default snapshot. A snapshot holds only the whitelisted keys below; a missing key means "factory default".
- **Write-through:** a preference listener copies the whitelisted keys of the effective preferences into the open tab's profile. It is idempotent (it always reads the current preferences and the current open tab), so delayed callbacks cannot write one tab's values into another.
- **Switch:** save the leaving tab's snapshot, set the open tab, apply the incoming profile to the effective preferences, refresh the in-memory copies (UI state, ChatGPT choice, AI translation choice), then load the tab's video. All on the main thread with no suspension in between.
- **Startup:** the open tab's profile is applied to the preferences before the UI state is built (the profile is authoritative; a crash between the two writes cannot desync them).
- **Default editor:** a separate editor reads and writes only the Default snapshot. It never touches the effective preferences, the UI state or the playing video.
- **New tab:** copies the Default snapshot. **Reset current tab to Default:** replaces the open tab's snapshot with a copy of Default and applies it.
- **Tab ids** never repeat: `next_tab_id` is stored inside the `browser_tabs` JSON (written atomically with the tabs). Closing a tab deletes its profile; start-up drops profiles of unknown tabs.
- **Migration** (once, `migrated` flag, one atomic batch): Default and every existing tab's profile start as the current effective values, so nothing visibly changes. After that the profiles diverge. Nothing outside the whitelist is read or written.

## Settings ownership

| Scope | Settings |
|---|---|
| PER_TAB | The whitelist `TAB_SETTING_KINDS` in `ui/TabSettings.kt`. Languages and slots: `preferred_caption_language`, `target_language`, `third_language`, `triple_subtitles_enabled`, the two caption visibilities. Translation: `translation_engine`, `ai_subtitle_translation`, `ai_subtitle_translation_only`, `google_wait`, Translation AI Service (`ai_translation_provider`, `ai_access_method`) and its saved model and thinking (`ai_translation_model*`, `ai_translation_effort_*`, `chatgpt_translation_model`). Look: `font_scale`, per-slot size, alignment and RTL, `subtitle_order`, `translation_position`, `caption_format`, the three subtitle colors, box background, word highlight and custom colors switches, hide sound cues, natural subtitles, show translation source. Panel: portrait offset, overlay and collapsed-button positions, landscape split and its fraction, lock overlay to video. Playback and learning: `tab_playback_speed`, word learning (switch, target, active only), tap to learn, auto pronounce, play mode, repeat count, skip silence, blur box |
| DEFAULT_TEMPLATE | The same keys. Settings → Default for new tabs edits languages, Subtitle 2, translation mode, AI service, text size, alignment, RTL, order, position, Google wait, caption format and visibility, colors, word learning and playback speed. Dragged positions, split fraction, play modes, blur box and per-service model/thinking are copied from the Default snapshot but have no control there |
| GLOBAL_RESOURCE_WITH_PER_TAB_SELECTION | ChatGPT sign-in, API keys, model lists: global and never stored in a profile. Which service translates, and which model and thinking it uses for translation, is chosen per tab. A tab without a saved choice follows the AI assistant's service as before |
| GLOBAL | App language and theme, Chat AI Service and chat settings, AI keys, OAuth tokens, saved words, Practice, Anki, known words, ads and sponsors, updates, recent languages, tab accounts and WebView profiles, player mode and the automatic overlay switches, pronunciation volume, preload models |

## Risks and how Stage 1 handles them

- Listener delivery is asynchronous, so a flag cannot gate it. The write-through is idempotent instead (see Design).
- `AiTranslationChoices.accessId` freezes the assistant's service into `ai_translation_provider` on first read; the write-through stores that in the open tab, which keeps translation independent of chat.
- In-memory copies of the ChatGPT choice and the AI choice revision do not follow preference changes by themselves; a switch calls their new `reloadChoice()`, which makes no network call, does not reset the gate and does not reload the video.
- `openVideo` ignores a video that is already open, so a switch that changed settings reloads it explicitly.
- A Settings screen or the Subtitle languages popup edits the tab that was open when it appeared. If the open tab changes under it, it closes (`DismissWhenOpenTabChanges`), so a control the person was about to touch can never write to another tab. The assistant's waiting cards are cancelled and its Undo buttons removed when the open tab changes (`openTabChanged`), for the same reason.
- `Reset all settings` removes the whitelisted keys from the effective preferences; the write-through then resets the open tab only. Default and other tabs keep their profiles.
- `tab_settings_profiles` is backed up like `dual_sub_preferences` (which holds the tabs), so tabs and profiles restore together; a tab without a profile after a restore gets one from the effective settings. It holds no secrets.

## Acceptance

1. Tab 1 (Google, Subtitle 1 Persian, Subtitle 2 off, 150 %) and Tab 2 (ChatGPT Subscription, Subtitle 1 English, Subtitle 2 Persian, centered) restore exactly on Tab 1 → Tab 2 → Tab 1 and after a restart.
2. Editing the Default changes no existing tab and the playing video; a new tab copies the Default.
3. Reset current tab replaces only that tab.
4. Closing a tab and opening another never reuses its id or profile.
5. Upgrading keeps every visible setting; the migration runs once.
6. Keys, OAuth, Chat AI Service, Saved Words, Anki are untouched.

## Validation

Focused JVM tests in `TabSettingsTest` and `BrowserTabsTest`; one `assembleDebug`. Device checks are the owner's.

## Implementation record

Implemented 2026-11-02 in two passes (Stage 1, then hardening and Stage 2 on the owner's approval).

- Files: `ui/TabSettings.kt` (whitelist, store, controller, `withTabSettings`, `ProfilePreferences`), `ui/TabSettingsEditor.kt` (scope selector, reset, Default editor, `DismissWhenOpenTabChanges`), `ui/TabPlaybackSpeed.kt` (`TabSpeedTracker`, `BindTabPlaybackSpeed`), `strings_tab_settings.xml` in 8 languages. Small edits: `AppViewModel`, `BrowserTabs`, `SubtitleSettingsDialog` (selector hook, three composables made `internal`), `LearningPlayerRoot`, `DualSubApp` (speed binder, landscape-split listener), `ChatGptSubscription.reloadChoice`, `AiTranslationConfig.reloadChoice`, `AiAssistantController.openTabChanged`.
- **Tab ids:** `next_tab_id` lives in the `browser_tabs` JSON; a separate `browser_tab_id_floor` preference, written in the same edit and never lowered, lifts the first tab's id above every id ever issued when the tab data is unreadable. Stale profiles below the floor are dropped as orphans.
- **Stale writers:** the preference listener only copies the whitelisted keys of the current preferences into the current open tab; a tab switch saves the leaving tab, applies the entering one and loads the video on the main thread without suspending. The Settings screen and the languages popup close when the open tab changes; the assistant's waiting cards and Undo buttons are dropped. Results of loads and AI refreshes are already tied to a load generation that every reload or video change bumps; a switch to a tab on the same video with different settings reloads it (`reloadsSameVideoAfterSwitch`).
- **AI scope:** the Translation AI Service, its model and thinking (`ai_translation_model_*`, `ai_translation_effort_*`, `chatgpt_translation_model`) are per tab. Keys, the ChatGPT sign-in, model lists and the Chat AI Service are never in a profile. `reloadChoice()` refreshes the two holders that cache the choice without any network call, gate reset or sign-out. A tab with no saved choice follows the assistant's service as before.
- **Playback speed:** YouTube keeps one speed for the page, so a tab's speed is recorded from the page video after three equal samples in the 0.25 to 2 range (ads at 4x, skip-silence and other tabs' videos never count) and set again when the tab's video opens; nothing is recorded while a saved speed is being restored. A tab that never recorded a speed follows YouTube's.
- **Not per tab (global by design):** player mode and its automatic overlay switches, pronunciation volume, known words, Study export targets, app theme and accent.
- **Default editor coverage:** the editor has a control for every setting that can be chosen from a list, switch or slider. Dragged positions, the landscape split fraction, play modes, blur box and per-service model and thinking are set by using the tab, then **Use the settings of this tab as the default** (asks first): the Default becomes a copy of the open tab's tab-owned settings. It changes no existing tab and not the open tab or its video.
- **Backup:** the app has no settings export or import. Backup is Android auto-backup of whole preference files (`backup_rules.xml`, `data_extraction_rules.xml`, unchanged): `dual_sub_preferences` (the tabs, `browser_tab_id_floor`, the open tab's effective settings including `tab_playback_speed`) and `tab_settings_profiles` (all profiles, no secrets) travel together; keys, ChatGPT and chats stay excluded as before. A restore of only `dual_sub_preferences` (an older backup) makes the next start seed the Default and every tab from it. `PracticeTransfer` (words, Anki) carries no settings and is unchanged. No backup format changed.
- **Restore without a readable tab list:** the id floor (in `dual_sub_preferences`) lifts the first tab above the issued ids and the stale profiles are dropped.
- Validation (final): `TabSettingsTest` (27), `BrowserTabsTest` (17), the whole `ui` JVM test package, `AiTranslationChoiceTest` and `AppLanguageTest` pass; `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` succeed. Not run: the full suite, lint, formatCheck, complexityCheck, instrumented tests.
- Unverified on a device: tab switching during AI translation, ChatGPT in one tab with Google in another, restart restore, restoring a saved playback speed on the live YouTube page, dragged-position restore after a switch, the Settings UI on the Light theme, the Default editor on small screens.