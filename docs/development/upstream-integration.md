# Upstream integration guide (for future syncs)

Use with [fork-customizations.md](fork-customizations.md). This guide describes how to decide, not a permission to merge: start a sync only when the owner asks, and follow the Git rules in `AGENTS.md`.

## Before touching a conflict

1. Find the feature in the registry (FC-xx) for each conflicted file.
2. Read the upstream change and the fork's invariants. Neither side wins by default.
3. Prefer the smallest change at the seam (a call, a field, a parameter) over editing fork-owned files.
4. After resolving, grep the other fork paths for the same upstream change (the third-line translation path, the ChatGPT path) because a conflict-free merge can still miss them.

## Decision matrix

| Change category | Strategy |
| --- | --- |
| Pure upstream-owned functionality | Take upstream |
| Pure fork-owned functionality | Keep the fork |
| Upstream bug fix in shared core | Take the fix, keep the fork behavior |
| Upstream API or signature change | Adapt the fork seam only |
| Shared file changed by both | Compare behavior and invariants, reconcile both |
| Upstream feature overlapping a fork feature | Check whether it can replace fork logic without losing a requirement; otherwise ask |
| Fork persistence | Keep stored formats and key names |
| Upstream removes or deprecates something | Trace fork uses before accepting |
| Ambiguous behavior conflict | Stop and ask the owner |

## Known seams to adapt first

- Per-service thinking: upstream code reading `settings.thinking` should read `thinkingFor(settings.provider)`.
- Translation effort rules: edit `aiTranslationEffort` in `assistant/AiTranslationChoice.kt`.
- Triple slot settings: `ui/TripleSlotSettings.kt`; `AppViewModel` only delegates.
- Translation problem and notices: apply upstream additions to both `followGoogleTranslation` and `ThirdLineTranslation.start` where they fit.
- Settings reset: add new upstream preference groups to the reset list in `ui/SubtitleColors.kt`.

## Validation after a sync

Compile with `./gradlew :app:assembleDebug --console=plain`; the owner runs tests and device checks. Do not claim device behavior.

## Third-line translation seam (Stage 2)

- Fork code: `ui/ThirdLineTranslation.kt`. Upstream-compatible code touching it: one property and one call in `AppViewModel` (`thirdLineTranslation`, `thirdLineTranslation.start(...)` in the translation run) plus one `noteAiFallback` call in `followGoogleTranslation`.
- When upstream changes `followGoogleTranslation`, `translateOnDevice`, `aiFillTexts`, `currentGoogleWaitMs`, `isCurrentLoad`, `aiTranslator`, `googleTranslator`, `playbackRequests` or `GoogleWebTranslator.translate`, update the constructor arguments or the class's use of them; do not copy upstream logic into the class.
- Adopt upstream improvements to Google or on-device translation for Subtitle 1 and check that `translateFallback` in the class still matches their semantics (wait, then device).
- Preserve: Subtitle 2 comes from the Original; stale-load protection; `noteAiFallback` rules.
- After an integration, run (owner): `ThirdLine`/triple-subtitle unit tests, the translation tests under `app/src/test`, and a device check of the third line while seeking.

## AI settings: ChatGPT parts (Stage 4)

- Fork code: `ui/AiChatGptSettings.kt` (`AiChatGptStateCard`, `AiChatGptModelSettings`, `aiModelSummary`, `aiConnectionLabel`, `aiConnectionSummary`), moved unchanged from `ui/AiAssistantSettings.kt`; they read the existing `AiAssistantUiState` and `ChatGptActions` and own no state.
- Still inline in upstream-shaped `AiAssistantSettingsSection`, `AiServiceMenu`, `AiKeySettings` (+187 vs `main`): the `AiConnectionTarget` branches. Take upstream layout changes there and re-apply those branches; do not move them without a design review, they are interleaved with upstream rows.
- Chat AI Service (`provider`/`chatGptService`) and Translation AI Service (`ai_translation_provider`) stay independent; credentials stay in `AiKeyStore` and the `chatgpt/` store.
- Owner checks after a sync: AI Connection screen per service, ChatGPT sign-in states, model box and picker, Thinking, chat card when ChatGPT cannot answer.

## Subtitle settings dialog (Stage 5)

`ui/SubtitleSettingsDialog.kt` stays a shared file (+160/-67): the Languages accordions (`SlotAccordion`, the `SubtitleSlotRow` calls, picker modes in `LanguagePickerButtons` / `showLanguagePickerIfOpen`) are woven into upstream's picker. On a sync, take upstream's changes to the dialog, sections and `SettingsExpandableCard`, then re-check that Original, Subtitle 1 and Subtitle 2 each keep their own accordion, language card and `TripleSubtitleControls` rows (`AGENTS.md` fork rule). Fork-owned pieces called from it: `SubtitleSlotLayout.kt`, `TripleSubtitles.kt`.

## AI settings: remaining inline seams (Stage 6)

`AiAssistantSettingsSection` is reshaped by the fork (two modes: `chatOnly` for the chat gear, otherwise Settings → AI Connection) so it needs semantic reconciliation on every sync; do not take upstream's version wholesale. Chat AI Service row now lives in `AiChatServiceSettings` (`AiChatGptSettings.kt`, takes `aiState`, `controller`, `chatGpt`, `chatTarget`).

Intentional inline seams (kept because they interleave with upstream rows or state):
- `AiAssistantSettingsSection`: `chatTarget`/`shownId`/`target` selection, the AI service row, the one-call `chatOnly` branch, section filter (KEYS/MODEL hidden in chat), KEYS title and the `AiConnectionBody` / `aiConnectionSummary` / `aiModelSummary` hooks. Invariant: Chat AI Service and Translation AI Service stay independent; the row here only chooses which service is shown.
- `AiServiceMenu` (now `internal`, parameters `target`, `offered`, `tag`, `showChatGpt`): ChatGPT row. `AiKeySettings` / `AiKeyRow` / `AiModelSettings`: `AiConnectionTarget` branches for key vs ChatGPT; ChatGPT never takes a key and keeps no thinking control.
- On a sync: take upstream's key, model and section changes, then re-apply those branches and check ChatGPT shows sign-in, not keys.

## Application shell: `ui/DualSubApp.kt` (Stage 7)

Mostly upstream-owned. Fork hunks: (1) in `DualSubApp`, the `CompositionLocalProvider(LocalVideoTitleEditor provides titleEditor)` wrapper around `DualSubExperience` (this re-indents the whole 55-line argument list, so any upstream change to those arguments conflicts: take upstream's arguments, then re-wrap them in the provider); `onOpenVideo` and the `BindPhraseActions(..., viewModel::translateThirdLine)` argument; (2) `SubtitlePanel` / `SideSubtitlePanel` call `PanelHeaderActions` (fork-owned, `ui/RetranslateUi.kt`) instead of upstream's inline Settings and Close buttons; (3) small `SelectedWordDialog`, `SubtitleTimeline` and `DualSubExperience` argument edits.
- Invariant for the provider: it must wrap only `DualSubExperience`. Widening it would show the title-edit pencil inside Saved words (`SavedWordsScreen` reads the same local and currently gets null, a behavior change), so move it only with owner approval.
- Navigation, back handling, `showVocabulary` / `showProgress` state and `LifecycleStartEffect` are upstream shape; keep them.

## AI subtitle translator and assistant controller (Stage 8)

- `translation/AiSubtitleTranslator.kt` (+202/-3) stays shared on purpose. Almost purely additive: `forceTranslate` / `forceNow` (about 104 lines, Re-translate with AI), `originOf` / `owes` / `restMs` / `notOnDisk` (provenance and level-triggered asking), `diagnostics`, `forcing`, `written`, `isBlocked`. They read and write the class's private state (`cache`, `attempted`, `asking`, `requests`, `restUntil`, `recent`, `arrivals`, `save`), so moving them would mean exposing that state and raise coupling. Upstream changes to the request, cache, parse or batch code must be mirrored in `forceNow` (it duplicates the ask, parse, commit steps by design: not streamed, replace only after a valid reply, commit under `NonCancellable`).
- On a sync: take upstream's changes in `translate`, `translateNow`, `missing`, `save` and the cache; then re-read `forceNow` against them. Keep: one request lock, the local failure rest ignored only by force, stale entries replaced only after a whole valid reply, `arrivals` raised after commit.
- `assistant/AiAssistantController.kt` (+134/-31): fork parts are members using private state (`attachChatGpt`, `requestChatGpt`, effort retry, trace handling), so they stay. `AiRunTrace` (pure, no controller state) is fork-owned in `assistant/AiRunTrace.kt`.
- Tests after a sync (owner): the AI translation unit tests under `app/src/test`, ChatGPT chat tests, and a device check of Re-translate with AI and each translation mode.

## Practice and vocabulary (Stage 9)

- Fork-owned: `ui/PracticeMeanings.kt` (`editableMeanings`, `ReviewMeanings`, `EditMeanings`, `withPendingEdits`, `withMeanings`: Subtitle 2 meanings in review and edit; pure UI and one pure copy function, no storage), `ui/PracticeSelectableText.kt`, `ui/PracticeSendToAnki.kt`, `data/AnkiTripleNote.kt`, `data/AnkiDroidProvisioning.kt`.
- Still shared (diff vs `main`): `SavedWordsScreen.kt` +86/-20 (calls into the helpers, the send flow, search `matchesSearch`), `data/SavedWord.kt` +65/-7, `data/VocabularyRepository.kt` +48/-7, `data/PracticeTransfer.kt` +59/-7, `data/AnkiConnectNotes.kt` +52/-14, `data/AnkiDroidNotes.kt` +39/-96. These carry stored fields and note mappings: do not change keys, ids, defaults or note type names without a migration plan and owner approval (`AGENTS.md`: v4 note type `TripleSub Replay v4`, `Subtitle 1`/`Subtitle 2`/`Text`, `Source Title`; JSON backup keys `meaning`/`meanings`/`personalNotes`).
- On a sync: take upstream's list, search and review-flow changes, keep the three calls into `PracticeMeanings.kt`, and check `SavedWord` serialization stays backward compatible (old backups and TSV headers still import).
- Tests after a sync (owner): vocabulary, import/export and Anki unit tests under `app/src/test`, then a device check of saving, editing meanings, Practice and send to Anki.

## Re-translate with AI session seam (Stage 10)

- Fork code: `ui/RefreshSession.kt` (3 inputs: the UI state flow, `isCurrentLoad`, the `TranslationRefresher`). It owns the installed `RefreshContext`, the cached whole-video texts and the `slots` flow. `AppViewModel` keeps `refresher` (read by `LearningPlayerRoot`), `refreshSession`, `refreshSlots`, three one-line delegates (`refreshTranslation`, `cancelRefresh`, `refreshSentenceCount`) and two calls in the translation run (`refreshSession.install(...)` before it, `refreshSession.end(...)` in `finally`).
- Invariants: `end` ignores a context a newer run replaced; the context's `isCurrent` uses `isCurrentLoad`; `refreshSession` must be declared after `_state` in the ViewModel (initialization order).
- On a sync: if upstream changes the translation run or `isCurrentLoad`, keep those two calls and the run's `finally`; check `rowTranslationTexts` / `translationTexts` still give the AI's form of the rows. Test (owner): `ForceRetranslateTest`, then Re-translate with AI for sentence, window and video on a device.

## Data contracts read from source (Stage 11, read-only)

Do not change any of these in a sync without a migration plan and owner approval.

| Contract | Source of truth | Stored form | Notes |
| --- | --- | --- | --- |
| Record id | `savedWordId` (`data/SavedWord.kt`) | SHA-256 hex of spelling (Persian normalized, else lowercase), word and meaning language, videoId, start and end ms | Saving again updates, never copies; `mergeResaved` keeps schedule and stored translations |
| Record body | `encodeWord` / `decodeWord` (`VocabularyRepository.kt`) | SQLite rows `id` + `payload` (JSON): `id, word, reading, wordLanguage, meaningLanguage, meaning, sentence, translatedSentence, videoId, startMs, endMs, translated, online, dueAt, intervalMs`; written only when non-empty: `sentenceTranslations, meanings, personalNotes, sourceTitle` | Optional keys read with `opt*`, so older rows without them load |
| Review state | `reviewWord`, `reviewInterval` | `dueAt` and `intervalMs`, epoch and interval in ms; interval clamped 10 min to 3650 days | Rating changes only these two |
| Source Title | `SavedWord.sourceTitle`, `normalizeSourceTitle` | `sourceTitle` key | Absent when none |
| JSON backup | `PracticeTransfer.kt` | `format` = `DualSubReplay`, `version` = 1, `words` array of the record body | Import requires exactly that format and version |
| TSV | `transferColumns`, `TRANSLATIONS_COLUMN`, `MEANINGS_COLUMN` = `subtitle2`, `NOTES_COLUMN` = `text` | header names; `subtitle1`, `subtitle1Language`, `sourceTitle` among them | Read by header name; `LEGACY_COLUMNS` map old headers; limits 8 MiB, 20,000 records |
| Anki note type | `AnkiTripleNote.kt` | `TripleSub Replay v4` = v3 fields + `Source Title`; `TripleSub Replay v3` kept untouched | AnkiConnect: `allowDuplicate=false`, `duplicateScope=deck`, a duplicate error counts as skipped |
| Anki fields and tags | `ankiTriple fields` in `TRIPLE_FIELDS` order, `ankiTags` | no language names or codes in fields | A changed field list needs a new note type name |

Tests covering these (JVM, pass in the Stage 11 full run): `SavedWordTest`, `SourceTitleTest`, `PracticeTransferTest`, `LegacyVocabularyUpgradeTest`, `AnkiTripleNoteTest`, `AnkiConnectNotesTest`, `AnkiDroidNotesTest`, `AnkiDroidSenderTest`.
