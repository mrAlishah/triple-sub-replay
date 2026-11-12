# Low-divergence fork architecture

Status: in progress (stages 2 and 3 done). Owner request 2026-10-30. Release intent: `release:skip` does not apply (APK code changes); patch if released.

## Problem

(Stage 1 baseline figures; current ones are in fork-customizations.md.) TripleSub Replay is a fork of DualSub Replay. Measured against local `main` (the upstream baseline, merge base `6bf6510`) in `app/src/main`: 97 files modified (45 Kotlin), 86 files added (38 Kotlin), +10,602 / -891 lines. The largest mixed-ownership files are `ui/AppViewModel.kt` (+461 / -92 after stage 2), `ui/AiAssistantSettings.kt` (+299), `ui/SubtitleSettingsDialog.kt` (+160), `ui/DualSubApp.kt` (+114 / -102), `translation/AiSubtitleTranslator.kt` (+202) and `assistant/AiAssistantController.kt` (+172). Every upstream sync conflicts in these.

## Goal

Fewer fork edits inside upstream-owned files, small explicit seams, a registry future agents can read (`docs/development/fork-customizations.md`), and rules for future syncs (`docs/development/upstream-integration.md`). No behavior, storage or UI change. This spec does not merge, rebase or sync anything.

## Ownership boundaries

- Upstream-owned: YouTube WebView, playback, caption retrieval, navigation, Google translation core, learner data.
- Fork-owned: the files in the registry marked Fork-owned (triple subtitles, `chatgpt/`, AI connection and translation choice, source title and filter, Anki triple note).
- Shared: the files in the registry's "Integration files" column. Only a call into fork code, a state field, or a parameter belongs there.

## Selected targets (risk-adjusted)

1. Stage 2 (done): the six triple-slot setters in `AppViewModel` (enable, third language, RTL, order, alignment, font scale) move to fork-owned `ui/TripleSlotSettings.kt`; `AppViewModel` keeps its public method names and delegates, so no call site changes.
2. Stage 3 (backlog, not started): third-line translation (`startThirdLine`, `translateThird`, `publishThirdLines`) into a fork-owned collaborator. Deferred: it uses `aiTranslator`, `googleTranslator`, `playbackRequests`, `translateOnDevice`, `noteFallback` and `isCurrentLoad`, so it needs an explicit dependency list that needs its own review.
3. Not selected: `DualSubUiState` fork fields (7) into a nested holder; it would touch every reader of those fields and increase, not reduce, edits in upstream-shaped call sites.

## Behavior that must not change

Preference keys, defaults and normalization (`storedThirdLanguage`, `normalizeSlotScale`, `moved`, `serializedOrder`); the order of the preference write then the state update; the public `AppViewModel` method names and signatures.

## Acceptance criteria

- `./gradlew :app:assembleDebug` compiles.
- `AppViewModel` fork-specific setter code shrinks from 48 lines to 18 lines of delegation, and its diff against `main` shrinks (480 to 461 insertions).
- Registry and integration guide exist, with every path verified to exist.
- No commit, push or sync is performed by the agent.

## Validation and rollback

Compile check only; unit tests and device tests are the owner's. Rollback: delete `TripleSlotSettings.kt` and restore the six method bodies in `AppViewModel` from Git history.

## Implementation record

- Stage 1 (audit, this spec) and stage 2 done 2026-10-30. Build result is recorded in `myCHANGELOG.md`'s entry and the final report.
- Not verified: behavior on a device.

## Stage 3 record: third-line translation (2026-10-30)

- Decision (option A, fork-owned collaborator with narrow inputs): `startThirdLine`, `translateThird`, `publishThirdLines` and `noteFallback` left `AppViewModel` for `ui/ThirdLineTranslation.kt`. Only fork code used them (none exist on `main`), the loop reads and writes just the UI state flow, and every other dependency is an explicit constructor input (9 inputs, 3 of them lambdas). Passing the ViewModel or a wide interface was rejected. Keeping the code in the ViewModel (option C) was rejected because the dependencies are few and explicit.
- Not moved: `selectedAiModel` and `reloadAfterAiAccessChange` (shared by Subtitle 1), `aiFillTexts`, `translateOnDevice`, `currentGoogleWaitMs` (shared with the upstream-shaped Google path; passed as lambdas).
- Behavior preserved: the code was moved as is; the same calls in the same order, `noteFallback` became the extension `TranslationDiagnostics.noteAiFallback`.
- Measured: `AppViewModel` insertions against `main` 480 to 370 (-110), file 2,631 to 2,521 lines; four fork-only members removed from it; the new file is 144 lines.
- Validation: `./gradlew :app:assembleDebug --console=plain` passed. No unit or device tests were run. Device check needed: third line while seeking, pausing, switching translation mode, and Google failing.

## Stage 4 record: AI settings (2026-10-30)

- Audit: `AiAssistantSettings.kt` was +299/-43 vs `main`. About 110 lines were whole fork-only functions; the rest are inline `AiConnectionTarget` branches inside upstream functions. `AiConnectionPanel.kt` and `AiModelPicker.kt` were already fork-owned.
- Decision (option B, only the cohesive fork-only functions): moved `AiChatGptStateCard`, `AiChatGptModelSettings`, `aiModelSummary`, `aiConnectionLabel`, `aiConnectionSummary` unchanged to `ui/AiChatGptSettings.kt` (the private ones became `internal`). No state, storage, key or signature change. Inline branches stay (extracting them would add parameter-heavy wrappers).
- Measured: `AiAssistantSettings.kt` 888 to 776 lines; insertions vs `main` 299 to 187; new file 150 lines.
- Validation: `./gradlew :app:assembleDebug --console=plain` passed. Not run: unit, lint, device tests.

## Stage 5 record: subtitle settings audit (2026-10-30)

- Result: no extraction. `SubtitleSettingsDialog.kt` is +160/-67 vs `main` in 20 small hunks, none a whole fork-only function. The largest (about 25+20+18+16 lines) sit inside upstream's `LanguagePickerButtons` and `showLanguagePickerIfOpen` (the Original / Subtitle 1 / Subtitle 2 accordions and `SlotAccordion`, the area where `AGENTS.md` says the fork wins). They share `LanguagePickerState`, `SettingsExpandableCard` and the picker dialog with the upstream rows, so a separate section would need the picker state, slot controls and callbacks as parameters and would duplicate the layout (Option D).
- Next candidate checked and also left: `selectedAiModel` (4 lines) and `reloadAfterAiAccessChange` (6 lines) in `AppViewModel` use `_state`, `aiConfig` and `loadVideo`; moving them adds a seam bigger than the code.
- Corrected stale metrics in `fork-customizations.md` (hotspot list was insertions plus deletions). Current: AppViewModel +370/-92, AiAssistantSettings +187/-43, SubtitleSettingsDialog +160/-67, DualSubApp +114/-102.
- Validation: no code changed this stage, so no new build; the Stage 4 debug build is the latest.

## Stage 6 record: AI settings inline branches (2026-10-30)

- Per-hunk audit: the 187 insertions were not all `AiConnectionTarget` logic. Whole-block fork content was the `chatOnly` Chat AI Service block (about 30 lines); the rest are parameters, the shown-service selection, the section filter and small branches interleaved with upstream rows (Option D for them).
- Implemented (option B, leaf composable): `AiChatServiceSettings` in `ui/AiChatGptSettings.kt`, moved unchanged; call site is one line with 4 arguments. `AiServiceMenu` became `internal`.
- Measured: `AiAssistantSettings.kt` +187/-43 to +155/-44, 776 to 743 lines; `AiChatGptSettings.kt` 150 to 191 lines. No new state, store or dependency edge besides the file-to-file call.
- Validation: `./gradlew :app:assembleDebug --console=plain` passed; no unit, lint or device tests. DualSubApp (secondary candidate) not touched.

## Stage 7 record: DualSubApp (2026-10-30)

- Audit: `DualSubApp.kt` +114/-102 was one 58/55 hunk (the `LocalVideoTitleEditor` provider wrapper re-indenting the `DualSubExperience` argument list), a 27-line fork-only `PanelHeaderActions` appended at the end of the file, 9/7 hunks in `SubtitlePanel` and `SideSubtitlePanel` (call to that function replacing inline buttons) and a few one-line argument edits.
- Decision: moved `PanelHeaderActions` unchanged to fork-owned `ui/RetranslateUi.kt` (`private` to `internal`; end-of-file appends are a classic conflict spot). Rejected moving the provider: Compose wrappers must indent, and moving it to the caller changes scope, which would show the title-edit pencil in Saved words. Rejected a parameter bag for the 55 `DualSubExperience` arguments (almost all upstream's own).
- Measured: `DualSubApp.kt` +114/-102 to +86/-103, 1,352 to 1,323 lines; `RetranslateUi.kt` +310 to +337. Build passed; no unit, lint or device tests.

## Stage 8 record: AI translation boundary (2026-10-30)

- `AiSubtitleTranslator.kt` (+202/-3): two additive blocks (`forceTranslate`/`forceNow` 104 lines, provenance and asking 46 lines) plus small field and diagnostics lines. All touch private translator state, so extraction would expose that state (coupling moved, not removed). Option A: keep, document the seam.
- Secondary `AiAssistantController.kt` (+172/-31): `AiRunTrace` (34-line private class appended at the end of the file, no controller state) moved unchanged to fork-owned `assistant/AiRunTrace.kt` (`private` to `internal`). The other fork parts use private controller state and stay. Measured +172/-31 to +134/-31, 1,309 to 1,272 lines; new file 41 lines.
- Validation: `./gradlew :app:assembleDebug --console=plain` passed; no unit, lint or device tests.

## Stage 9 record: Practice and vocabulary (2026-10-30)

- Audit: `SavedWordsScreen.kt` +163/-20 had one 71-line fork-only block appended after the screen (meaning editing and review helpers) and a 34-line edit inside `SavedWordsScreen` (send flow), plus small edits. Only the appended block is a cohesive, storage-free unit.
- Done: `editableMeanings`, `meaningTextStyle`, `ReviewMeanings`, `EditMeanings`, `withPendingEdits`, `withMeanings` moved unchanged to `ui/PracticeMeanings.kt` (three `private` became `internal`). No change to `SavedWord`, `VocabularyRepository`, serialization, ids, Source Title, review scheduling or Anki mapping, which were not modified.
- Not audited in depth: persistence serializers and Anki mapping hunks (`SavedWord.kt`, `VocabularyRepository.kt`, `PracticeTransfer.kt`, Anki files); left unchanged because their compatibility was not established. The 34-line send-flow hunk inside `SavedWordsScreen` stays (uses the screen's local state).
- Measured: `SavedWordsScreen.kt` +163/-20 to +86/-20, 380 to 303 lines; new file 89 lines.
- Validation: `./gradlew :app:assembleDebug --console=plain` passed; focused JVM tests `MeaningLanguagesTest` and `PracticeSendTest` passed. No device tests; no real data touched.

## Stage 10 record: AppViewModel residual coupling (2026-10-30)

- Audit: `AppViewModel.kt` +370/-92 after stages 1 and 3. Largest remaining fork hunks: an 87-line block (Re-translate session, `selectedAiModel`, `reloadAfterAiAccessChange`), a 35-line block and a 24-line block (AI config and refresher fields), then small edits in `loadVideo`, `followGoogleTranslation` and `resetAllSettings`. I read the first block and the field declarations; the 35-line block and the small edits were not individually audited this stage.
- Decision (option A): the re-translate session (`installRefreshContext`, `endRefreshContext`, `refreshTexts`, the context, cached video texts and slot flow) moved to `ui/RefreshSession.kt`, 3 inputs, no ViewModel reference. `refresher` stays in the ViewModel because the UI reads it. `selectedAiModel` and `reloadAfterAiAccessChange` stay: they use `aiConfig` and `loadVideo`.
- Existing boundaries checked: `TripleSlotSettings` (prefs and state flow only), `ThirdLineTranslation` (9 inputs, no ViewModel). No defect found; their behavior was not re-tested.
- Measured: `AppViewModel.kt` +370/-92 to +297/-92, 2,521 to 2,449 lines; new file 110 lines, 3 inputs; two unused imports removed.
- Validation: `./gradlew :app:assembleDebug --console=plain` passed; `ForceRetranslateTest` passed. No other tests, lint or device checks.

## Stage 11 record: stabilization and verification (2026-10-30)

- No code changed. Worktree verified: branch `low_divergence_fork`, HEAD `78c83bb`, 16 uncommitted entries, no merge, rebase or cherry-pick.
- `./gradlew :app:testDebugUnitTest`: 1,394 tests, 0 failures, 0 errors, 0 skipped.
- `./gradlew :app:lintDebug`: 0 errors, 156 warnings, 3 hints. Warnings in moved code are old patterns moved as is (`UseKtx` x6 in `TripleSlotSettings.kt`, copied from `AppViewModel`; `ModifierParameter` in `RetranslateUi.kt` line 78, committed code). Whether the 156 equal the pre-refactoring count was not measured (no baseline run).
- `./gradlew :app:assembleDebug`: passed (`app/build/outputs/apk/debug/app-debug.apk`).
- `./gradlew complexityCheck`: FAILED, two findings: `DualSubExperience` (`DualSubApp.kt:254`, complexity 25, threshold 25) and `AiAssistantSettingsSection` (`AiAssistantSettings.kt:286`, complexity 28). These functions were not made more complex by the stages (Stage 6 removed a branch from the second; the first was untouched), but no baseline run exists, so "pre-existing" is an inference, not a measurement.
- `./gradlew formatCheck`: not executed in effect: it starts `python`, which is not on this machine and which the owner forbids running. Unverified.
- RefreshSession review: declared at line 1395, after `_state` (554) and `refresher`; `install` and `end` are called in the translation run and its `finally`; `end` ignores a replaced context; `thirdLine?.cancel()` still in `finally`. `install` is called before the `try` as before the move (unchanged structure). No test covers a concurrent refresh or a video switch mid-refresh.
- Data contracts: see the table in `upstream-integration.md`; read from source, no data touched.

## Upstream sync record: `main` (83d21f9) into `merge_upstream` (2026-11-04)

Merge in progress, not committed. Resolved by meaning, hunk by hunk; merge base `d91b044`, 26 incoming upstream commits. Debug build only (`./gradlew :app:assembleDebug`); no tests, lint, formatting or complexity checks were run (owner tests on a device).

### Accepted from upstream

- **Practice decks** (spec `2026-11-02-practice-decks.md`): `data/PracticeDecks.kt`, `ui/PracticeDecks.kt`, `SavedWord.deck` (null = the language's automatic deck, never part of the id), deck chip on the word card and in Practice, deck list, rename and delete (`VocabularyRepository.updateWhere`; `retitleVideo` is now a one-line wrapper over it), the assistant saving into the last deck used for the language. Deck and Source Title stay independent: the deck narrows the words first, the source picker then works inside it; Due Words, search and counts follow both. Deck is stored only when set (`deck` JSON key; `deck` is the last TSV column so Anki's positional mappings do not shift); saving from the word card sets the deck it shows (Auto clears it), and import (`keepSavedNotes`) never clears an existing deck. Renaming or deleting a deck moves words only; vocabulary and review history are untouched.
- **Centred transcript and Forever line loop** (spec `2026-11-03-centred-transcript-and-line-loop.md`): `TranscriptFollow`, `FollowPlayingRow`, segment-id play-mode tracking, the assistant's `transcript_follow` and `forever` values. Only transcript scrolling and study-mode seeks changed; the subtitle card's Play button (`onReplay`) and tap, selection and Re-translate behaviour are the fork's and were not touched.
- **Azerbaijani** interface (`az`) and the online-only translation languages; the 206 fork-only strings it lacked were machine-translated (`values-az/`, marked "not native-reviewed" in each file).
- Screen-off playback and WebView fixes, `updateWhere`, CI and dependency bumps (setup-gradle 6.4.1, CPU-scenario regex, webkit 1.18.0, OkHttp 5.5.1, AGP 9.5.0, Gradle 9.8.2).
- Upstream JSON backups import: `notes` is read as `personalNotes`, and a single `thirdSentence` + `thirdLanguage` becomes one more `sentenceTranslations` entry (fork keys win).

### Intentionally excluded (fork behaviour kept)

- **Upstream's optional third line** (`ui/ThirdSubtitleLine.kt`, its settings switch and picker entry, `thirdLineEnabled`, `ThirdLineChoice`, its card and overlay rendering, `withThirdLineOnScreen`): replaced by the fork's three slots and `ThirdLineTranslation`; no second pipeline. Its stale-result protection already exists here as `isCurrentLoad`.
- **Upstream saved-word fields** `notes`, `thirdSentence`, `thirdLanguage` and `cleanSourceTitle`: the fork keeps `personalNotes`, `sentenceTranslations`, `meanings`, `sourceTitle` and their JSON/TSV keys. `PracticeVideoFilter.kt` (video filter) is replaced by the fork's Source filter.
- **AI thinking for translation** (`AiTranslationThinking`, its model and thinking settings, `ai_translation_thinking` preference, assistant setting and strings): covered by the per-service model and effort in Settings → AI Assistant (`aiTranslationEffort`, `AiEffort`); upstream's Off/Low/Medium map to Automatic/Low/Medium there. `AiTranslationAccess.kt` and its test equal the fork's. No cache scope or saved preference changed.
- Practice's upstream presentation changes (example box with the highlighted word, two-by-two rating buttons, `MeaningPanel` editing) and the legacy AnkiDroid translation field carrying a third line.
- Upstream README, site and promo-video changes merged as upstream's own pages (no fork branding existed there to lose); the Obtainium line is gone.
- Their tests for the excluded behaviour were removed: `ThirdLineOnSavedWordsTest`, `ThirdLineAndVideoFilterTest`, `SavedWordNotesAndSourceTest`, the AI-thinking tests in `OnlineTranslationUiTest`, `AiTranslationAccessTest` (restored to the fork's), and `SavedWordsScreenTest.practiceShowsTheThirdSubtitleLineAfterTheMeaning`.

### Remaining manual verification (owner)

- Subtitle card: a plain tap does not seek or play, only its Play button does; centred transcript with the playing line in the middle, and the Jump back pill while browsing; Forever repeat and Repeat each line (no runaway counts); text selection and the segment dropdown (Re-translate with AI) are independent.
- Three slots: order, size, alignment, RTL, language per slot, overlay, portrait and landscape; the third line while seeking.
- Practice: decks list and one open deck; deck chip when saving and editing; rename and delete a deck (words stay, schedule unchanged); Source Title filter inside a deck; Due Words for a deck and source; search; old words show in their language's deck.
- Data: import an old fork JSON backup, an upstream JSON backup (notes, third line), old and new TSV (`deck` column), then export again; Anki (AnkiDroid and AnkiConnect, note type `TripleSub Replay v4`) still sends and detects duplicates.
- AI: Settings → AI Assistant per-service Thinking and Model, Translation AI Service row, ChatGPT sign-in, Test translation, Re-translate with AI.
- Azerbaijani: pick it in the interface language list and read the screens; the machine translation needs a native review.
- Not run: unit tests, lint, formatting, complexity, instrumented tests (`AppLanguageTest` and lint `MissingTranslation` should now pass for `az`, unverified).

## Upstream sync record: `e88009a` into `mydevelop` (2026-11-07)

- Incoming: line-loop button on the playing line, Search settings with Often changed, the video picture and channel in the media notification, Reading and Default view merged into one section, Languages as a section, README/site licence wording.
- Owner decisions (2026-11-07): keep upstream's single-open outer sections (Languages, Reading including Default view); keep the fork's independent Original / Subtitle 1 / Subtitle 2 accordions; Loop button visibility stays global (not in `TAB_SETTING_KINDS`).
- Resolutions: `SubtitleSettingsDialog` takes upstream's search structure with the fork's `LanguagePickerButtons` accordions inside the Languages section and the Per-tab settings card above the search field; `DualSubApp` combines `LocalVideoTitleEditor` and `LocalLineLoop` in one `CompositionLocalProvider`; `SubtitleCard` keeps `thirdText`/`thirdOrigin` and adds `loop`; strings are the union minus upstream's `settings_third_line*`. Not adopted: upstream third-line settings, AI-translation model and thinking rows (and their search entries), README promotional and licence-name text.
- Search seam: a slot's language button registers `settingAnchor("<tag>_picker")` (`source_language_picker`, `target_language_picker`, `third_language_picker`); `LanguagePickerState.expandForAnchor` opens that slot's panel on a jump. The `THIRD_LINE` search entry points at the third slot (`TRIPLE_SUBTITLES_PREFERENCE`, `THIRD_LANGUAGE_PREFERENCE`). A new fork setting inside a collapsed panel needs the same treatment.
- Validated: `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin`, `:app:compileDebugUnitTestKotlin` compile. Not run: unit, instrumented, lint, format, complexity.
- Manual checks (owner, device): search for Original, Subtitle 1, Subtitle 2 and Third subtitle (section opens, slot panel opens, scroll, glow, saved value unchanged); search a Reading, Layout and Translation setting; Often changed; Languages and Reading open one at a time while the three slot panels stay independent; Per-tab settings card; Loop button (tap, stop, long seek, ads) and that a card tap still does not seek; media notification picture and controls.

## Upstream sync record: `main` (ed6e09c) into `mydevelop` (2026-11-12)

Merge base `ae9c1be`. Resolved in the working tree, uncommitted. JVM-checked only; nothing was run on a device.

### Accepted from upstream

- Backup and shared setups (`ui/BackupSharing.kt`, `data/SettingsTransfer.kt`), settings groups with current values and Search settings entries for every switch, Flashcards section, Hide while playing in the quick language popup, close X in the settings top bar.
- Card sound, picture and card front, Dictation play mode, AI subtitles for videos without captions, Meaning in this sentence (AI), smaller app updates and interrupted-update retry, earlier PR preview, line sound replay.

### Adapted to the fork

- Card media reaches the fork's note type as `TripleSub Replay v5` through `AnkiDroidSender` / `tripleCardTemplate` ([spec](2026-11-12-triplesub-anki-v5-card-media.md), owner pick).
- Settings page: upstream's groups and `PageSectionSettings` kept; Languages uses the fork's slot accordions; the Translation hand-off to AI connections (`onOpenAiConnection`, `connectionTarget`) and the Subtitle 1 position rule stay the fork's. Quick popup keeps the slot rows plus Hide while playing.
- `tracksVisible()` counts the third line too. Word card keeps the extra meaning languages beside the in-sentence meaning; Search lists the translation-source and AI response-source switches.
- Meaning in this sentence asks `aiConfig.translationModel()` (the Translation AI Service and its effort), not upstream's removed `aiTranslationModel`.
- Fork keys are sorted for backup and sharing in `data/ForkSettingSpecs.kt` (never SHARE for AI or phone state).

### Excluded

- Upstream's third subtitle line (`ThirdSubtitleLine.kt`, its switch, `setThirdLine`, `thirdLine` in the quick popup): the fork's Subtitle 2 slot replaces it. The only upstream change to that file was a comment.
- AI thinking for translation (stays out, per-service Thinking covers it); upstream's `sendToGoogleTranslate` hand-off (the fork's Google Translate link wins); upstream README wording that does not apply to the fork's README.

### Manual verification (owner)

- AnkiDroid and AnkiConnect: first send creates v5, all four card fronts, sound on the card, a picture, a card sent before its clip, v4 and v3 notes untouched, a Basic card.
- Settings page groups, Languages accordions, Search for the new entries; quick popup (slots, Hide while playing); Backup and Share setup round trip with slots, AI choices (never in a shared link) and per-tab state.
- Meaning in this sentence with each Translation AI Service, including ChatGPT sign-in and no key; AI subtitles for a video with no captions; Dictation.
- Not run: lint, `formatCheck`, `complexityCheck`, the full unit suite, `assembleDebug`, androidTest on a device.

