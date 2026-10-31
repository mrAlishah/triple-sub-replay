# Fork customization registry (TripleSub Replay)

The authoritative list of what this fork adds to DualSub Replay and what must survive an upstream sync. Read it before any upstream integration (rules: [upstream-integration.md](upstream-integration.md)). Paths are under `app/src/main/java/com/kienhoang/dualsubreplay/` unless they start with `app/`. Baseline measured 2026-10-30 against local `main` (merge base `6bf6510`); statuses mean "present in code", never "tested on a device" (the owner tests on a device).

Ownership: **Fork** = file exists only here. **Shared** = upstream file with fork edits (churn is lines changed against `main`). Refresh the numbers with `git diff --numstat main HEAD -- app/src/main`.

## FC-01 Triple subtitles (three slots)

- Ownership: Fork, with Shared integration. Status: Implemented.
- Purpose: Original, Subtitle 1 and Subtitle 2 as independent slots with their own language, visibility, RTL, alignment, size and order.
- Fork files: `ui/TripleSubtitles.kt`, `ui/SubtitleSlotLayout.kt`, `ui/TripleSlotSettings.kt` (the slot setters, since 2026-10-30), `ui/ThirdLineTranslation.kt` (the Subtitle 2 translation loop and `noteAiFallback`, since 2026-10-30), `ui/TranslationSourceTag.kt`, `res/values*/strings_subtitle_roles.xml`.
- Integration files (Shared): `ui/AppViewModel.kt` (state fields `tripleSubtitlesEnabled`, `thirdLanguage`, `thirdLines`, `slotRtl`, `subtitleOrder`, `slotFontScale`, `slotAlignment`; property `thirdLineTranslation` and its one call `thirdLineTranslation.start(...)` in the load; delegating setters), `ui/DualSubApp.kt`, `ui/LearningPlayerRoot.kt`, `ui/SubtitleCard.kt`, `ui/SubtitleSettingsDialog.kt`, `ui/SubtitleColors.kt`, `ui/CaptionPresentation.kt`, `ui/PlaybackTranslation.kt`, `ui/PhraseSelection.kt`.
- Upstream dependency: caption selection state, the display `SubtitleStore`, the playback clock, `TranslationOrigin`, `Translated`.
- Invariants: slot identity (`stableId`) is separate from visual order; settings stay keyed by slot; hidden slots keep their place; the third line is translated from the Original, never from Subtitle 1; alignment follows direction unless set; exactly one WebView.
- Persistence: preferences `triple_subtitles_enabled`, third language, `subtitle_order`, `font_scale_<id>`, `subtitle_alignment_<id>`, per-slot RTL. Reset all settings must clear them (`TRIPLE_SETTING_KEYS`).
- Conflict sensitivity: high (`AppViewModel`, `DualSubApp`, `SubtitleCard`, `SubtitleSettingsDialog`).
- Update guidance: take upstream changes to captions, clock and cards; keep the three-slot contract; adapt only the call into fork code. Subtitle language UI (Settings → Languages accordions) follows the fork rule in `AGENTS.md`.
- Tests: `app/src/androidTest/.../ui/SubtitleSlotLayoutUiTest.kt` and the triple unit tests under `app/src/test`.

## FC-02 Central AI Connection

- Ownership: Fork, with Shared integration. Status: Implemented.
- Purpose: one place for API keys, ChatGPT sign-in, model list and thinking level, kept per service.
- Fork files: `assistant/AiConnection.kt`, `ui/AiConnectionPanel.kt`, `ui/AiModelPicker.kt`, `res/values*/strings_ai_connection.xml`.
- Integration files (Shared): `ui/AiAssistantSettings.kt` (+155 after Stage 6; fork-only ChatGPT and Chat AI Service parts live in `ui/AiChatGptSettings.kt`), `assistant/AiSettings.kt`, `assistant/AiAssistantController.kt`, `assistant/AiProvider.kt`.
- Invariants: models and thinking are per service (`thinkingFor(provider)`); the AI service row only chooses what is shown; keys stay encrypted and out of backups.
- Persistence: `models`, `thinkings`, per-service key preferences (`ai_assistant_keys`).
- Conflict sensitivity: high (`AiAssistantSettings.kt`, `AiSettings.kt`).
- Update guidance: when upstream changes `thinking` or `modelFor`, keep the per-service accessors; upstream helpers that read `settings.thinking` must read `thinkingFor(settings.provider)` (done for `aiAssistantEffort`).
- Tests: `app/src/test/.../assistant/AiConnectionStateTest.kt`, `app/src/androidTest/.../ui/AiConnectionPanelUiTest.kt`.

## FC-03 ChatGPT Subscription (OAuth, translation and chat)

- Ownership: Fork (almost fully). Status: Implemented; live sign-in unverified on a device when it landed.
- Purpose: translate and chat with the user's own ChatGPT plan, without a key.
- Fork files: `chatgpt/ChatGptOAuth.kt`, `ChatGptSession.kt`, `ChatGptSignIn.kt`, `ChatGptLoopback.kt`, `ChatGptHttpApi.kt`, `ChatGptResponses.kt`, `ChatGptChatBackend.kt`, `ChatGptTranslationModel.kt`, `ChatGptSubscription.kt`; `assistant/AiChatGptWire.kt`, `assistant/AiResponseSource.kt`; `ui/ChatGptSubscriptionSettings.kt`, `ui/AiResponseSourceUi.kt`.
- Integration files (Shared): `assistant/AiAssistantController.kt` (+172; `attachChatGpt`, `requestChatGpt`, run trace), `assistant/AiChatClient.kt`, `ui/AiAssistantChatParts.kt`.
- Invariants: no ChatGPT website, cookies or undocumented endpoints; never fall back from it to a keyed service; the wire format reaches only `ChatGptChatTransport`; every answer is stamped with who really ran it; tokens sealed and excluded from backups.
- Persistence: `chatgpt_subscription` preferences.
- Conflict sensitivity: medium (only the controller seam).
- Update guidance: take upstream chat-loop fixes inside `answer()`/`request()`; keep the transport router separation.
- Tests: `app/src/test/.../chatgpt/ChatGptChatTest.kt`, `ChatGptHttpApiTest.kt`, `ChatGptOAuthTest.kt`; `assistant/AiChatGptWireTest.kt`, `AiResponseSourceTest.kt`; `app/src/androidTest/.../ui/AiChatGptChatUiTest.kt`.

## FC-04 Translation and Chat AI service assignment

- Ownership: Fork, Shared at the translator. Status: Implemented.
- Purpose: Translation AI Service (`ai_translation_provider`) and Chat AI Service (`provider` / `chatGptService`) are independent.
- Fork files: `assistant/AiTranslationChoice.kt`, `ui/AiTranslationConfig.kt`, `ui/AiTranslationSettings.kt`, `ui/TranslationChoice.kt`, `res/values*/strings_ai_translation.xml`.
- Integration files (Shared): `assistant/AiTranslationAccess.kt`, `translation/AiSubtitleTranslator.kt` (+205), `ui/AiWindowTranslation.kt`.
- Invariants: the translation row is frozen from what translated before (`AiTranslationChoices.accessId`); saved models and efforts under `ai_translation_model_<id>` / `ai_translation_effort_<id>` are never deleted; effort goes through `aiEffortWire`; Automatic keeps the old cache scope.
- Conflict sensitivity: medium-high.
- Update guidance: upstream effort rules (for example `openAiNoThinkingEffort`) belong in `aiTranslationEffort` in `AiTranslationChoice.kt`, not in `AiTranslationAccess.kt`.
- Tests: `app/src/test/.../assistant/AiTranslationChoiceTest.kt`, `AiTranslationAccessTest.kt`; `app/src/androidTest/.../ui/AiTranslationSettingsUiTest.kt`.

## FC-05 Translation modes, fallback, cache and re-translate

- Ownership: Shared. Status: Implemented.
- Purpose: Google, AI with Google first, AI only, on-device; Google first with AI upgrades; manual Re-translate.
- Fork files: `ui/AiUpgrades.kt`, `ui/TranslationRefresh.kt`, `ui/RetranslateUi.kt`, `translation/TranslationOrigin.kt`.
- Integration files (Shared): `ui/AppViewModel.kt` (`followGoogleTranslation`, `runStoredTranslation`, refresh), `ui/PlaybackTranslation.kt` (+134), `translation/TranslationEngine.kt`, `ui/OnlineTranslationNotice.kt`.
- Invariants: Google keeps what the AI leaves out; saved mode survives restart; provenance (`TranslationOrigin`) is kept per row.
- Update guidance: semantic review of every upstream change to `AppViewModel` translation methods; upstream additions (such as `aiTranslationProblem`) must also reach the fork's third-line path if they apply.
- Tests: `app/src/androidTest/.../ui/RetranslateUiTest.kt`, `TranslationSourceUiTest.kt`.

## FC-06 Practice, saved words, Source Title and filtering

- Ownership: Shared + Fork. Status: Implemented.
- Purpose: source video title and id on saved words, editable title, filtering Practice and Due Words by source, personal notes.
- Fork files: `data/ManualVideoTitles.kt`, `data/SourceFilter.kt`, `ui/SourceFilterPicker.kt`, `ui/VideoTitleHeader.kt`, `ui/PersonalNotesEditor.kt`, `ui/PracticeSelectableText.kt`.
- Integration files (Shared): `data/SavedWord.kt`, `data/VocabularyRepository.kt`, `ui/SavedWordsScreen.kt` (+183), `ui/WordLearningSheet.kt`, `data/PracticeTransfer.kt`.
- Invariants: do not rename `SavedWord.meaning`/`meanings`/`personalNotes` or JSON backup keys; old backups still import; review history and scheduling unchanged.
- Persistence: saved-word JSON, `files/known-words/`, backups.
- Conflict sensitivity: medium-high (`SavedWordsScreen.kt`).
- Tests: `app/src/androidTest/.../data/PersonalNotesRepositoryTest.kt`, `PracticeSelectableTextUiTest.kt`.

## FC-07 Anki mapping and transfer

- Ownership: Shared + Fork. Status: Implemented.
- Purpose: note type `TripleSub Replay v4` (fields `Subtitle 1`, `Subtitle 2`, `Text`, `Source Title`), v3 kept as an AnkiConnect fallback; TSV columns `subtitle1`, `subtitle1Language`, `subtitle2`, `text`, old headers still imported.
- Fork files: `data/AnkiTripleNote.kt`, `data/AnkiDroidProvisioning.kt`, `ui/PracticeSendToAnki.kt`.
- Integration files (Shared): `data/AnkiDroidNotes.kt` (+135), `data/AnkiConnectNotes.kt`, `data/PracticeTransfer.kt`.
- Invariants: never change an existing note type's field list; a field change needs a new note type name; export and import keep backward compatibility.
- Tests: `app/src/androidTest/.../ui/PracticeSendToAnkiUiTest.kt`.

## FC-08 Customized navigation and Settings

- Ownership: Shared. Status: Implemented.
- Integration files: `ui/SubtitleSettingsDialog.kt` (+227; Original, Subtitle 1, Subtitle 2 accordions are `mydevelop`-owned), `ui/DualSubApp.kt`, `ui/StudyTools.kt`, `ui/GoogleWaitSettings.kt`, `ui/GoogleTranslateLink.kt` (Google Translate hand-off is `mydevelop`-owned).
- Invariants: the merge priorities in `AGENTS.md` ("Merge conflict priorities") apply; the app theme and string-resource rules apply to every added UI.

## Hotspots by remaining fork churn against `main` (Shared files)

Measured 2026-10-30 after Stage 4, working tree vs `main` (`git diff --numstat main`, insertions/deletions): `ui/AppViewModel.kt` +297/-92, `ui/AiAssistantSettings.kt` +155/-44, `ui/SubtitleSettingsDialog.kt` +160/-67, `ui/DualSubApp.kt` +86/-103, `translation/AiSubtitleTranslator.kt` +202/-3, `assistant/AiAssistantController.kt` +134/-31, `ui/SavedWordsScreen.kt` +86/-20. Earlier figures in this file and the spec were insertions plus deletions or pre-stage values.
## Seam: `ThirdLineTranslation` (Stage 2, 2026-10-30)

- Owner: fork (`ui/ThirdLineTranslation.kt`). `AppViewModel` owns the load lifecycle, `loadGeneration` and the translation scope; it builds the class once (`thirdLineTranslation`) and calls `start(...)` once per translation run. The returned `Job` is cancelled with the run, as before.
- Inputs (9, all explicit): the UI state flow (read and publish), `isCurrentLoad`, `aiTranslator`, `translationDiagnostics`, `googleTranslator`, `playbackRequests`, and three ViewModel functions passed as lambdas: `aiFillTexts` (shared with the Google path), `translateOnDevice` (needs the device translator and the model-download notice), `currentGoogleWaitMs` (needs the network state).
- Invariants: Subtitle 2 is asked from the Original, never from Subtitle 1; AI only waits for the AI; Google first then the device; rows from a stale load are dropped by `isCurrentLoad`; `noteAiFallback` records only AI-only fallbacks.
- Shared code still calling fork code: `followGoogleTranslation` calls `translationDiagnostics.noteAiFallback(...)` for Subtitle 1.
