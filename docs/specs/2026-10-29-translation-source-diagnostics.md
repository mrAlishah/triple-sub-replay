# Show where each subtitle translation came from

## Status

Owner request 2026-10-29. The ChatGPT Subscription sign-in works on the owner's phone
(`Connected · plan usage allowed`, and Test translation succeeds), but during playback the owner cannot
tell whether Subtitle 1 and Subtitle 2 show Google's lines or ChatGPT's. Implementation record at the end.

## Context / problem

With "AI translation (best quality)" Google's line shows first and each sentence switches to the AI's as soon
as it arrives; if the AI fails or is slow, Google's line simply stays. Both look the same on screen, so a
silently failing ChatGPT session cannot be told apart from a working one.

Traced in this worktree:

- Subtitle 1: `AppViewModel.followGoogleTranslation` builds the `translate` lambda (AI cache, then Google, then
  device) and `TranslationUpgrades` (AI replaces what is shown). `translatePlaybackWindow` turns the returned
  `String` into `SubtitleSegment.translatedText` through `withTranslation` / `withUpgrades`. The string carries no
  origin, so it is lost at the first boundary.
- Subtitle 2: `AppViewModel.startThirdLine` + `runThirdLanguage` run the same loop and publish `ThirdLines`
  (`Map<Long, String>`).
- YouTube's captions and the device translator feed the same lambda (`followOnDevice`).
- AI results are cached in memory and on disk by `AiSubtitleTranslator`; the cache key already contains
  `model.scope` (`chatgpt/<model>/<account hash>` or `<service>/<model>`), so a stored entry's provider and model are
  provable from its key. Whether it was written in this run (live) or read from disk (cached) is not recorded.

## Goals

- An optional setting, **Show translation source (debug)**, in Settings → Captions & translation, off by default.
- While on, a small label under each translated line (Subtitle 1 and Subtitle 2, in the overlay and in the subtitle
  list) names the source of the text **that is on screen**: ChatGPT Subscription, ChatGPT Subscription (cached),
  Google Translate, On-device translation, YouTube translated captions, AI provider (the user's own key),
  Unknown.
- The label is set at the moment a translation is produced or read, by the code that produced or read it. It is never
  derived from the selected settings.
- Tapping a label opens a compact details view: source, live or cached, slot and target language, model, and the
  last AI/fallback events for that language.
- Off: no label, no extra layout, no behavior change.

## Non-goals / anti-goals

- No change to prompts, translation quality, timing, sentence mapping, Google-first behavior, AI-only fallback
  semantics, slot identity, selection, Google Translate sharing, Anki, Practice or Vocabulary.
- No new persisted data: `SubtitleSegment` gains an in-memory field only (rows are rebuilt from the store on every
  window read; the store never serializes `translatedText`). No change to cache files, so no migration.
- No guessing: text whose origin was not recorded shows **Unknown**.
- Diagnostics never hold tokens, codes, e-mail addresses, subtitle text or server bodies; only counts, language
  codes, a provider/model name and a short reason code.

## Design

### Provenance model

`translation/TranslationOrigin.kt`: `TranslationOrigin(provider, cached, model)` with providers `CHATGPT`,
`AI_PROVIDER`, `GOOGLE`, `ON_DEVICE`, `YOUTUBE`, `UNKNOWN`. `Translated(text, origin)` is what a translation step
returns inside the app.

### Where it is attached

1. Each producer states its origin when it returns text (inside the existing `translate` lambdas in
   `AppViewModel`): AI cache or live AI reply → AI origin; `googleTranslator` → Google; YouTube's track →
   YouTube; `translateOnDevice` → on-device.
2. AI origin comes from `AiSubtitleTranslator.originOf(model, ...)`: provider and model from `model.scope`
   (`chatgpt/...` is ChatGPT), and `cached` is true when the entry was read from the disk cache and was not
   written by a request in this app run.
3. `translatePlaybackWindowTracked` (the existing loop; `translatePlaybackWindow` stays as a thin wrapper so callers
   and tests are unchanged) copies the origin into `SubtitleSegment.translationOrigin` with the text, keeps it with
   the text across window reads, and clears it with the text.
4. `withUpgrades` replaces text and origin together, and only when the AI's text differs from what is shown. When
   the AI's text equals the shown Google text the row keeps `Google` and a diagnostics event records that the AI
   result was available but identical.
5. Subtitle 2 does the same through `runThirdLanguage` and `ThirdLines.origins` (keyed by row id), so each slot has
   its own origin independent of the other.

### Diagnostics events

`TranslationDiagnostics` (in memory, last 60 events, thread safe, `StateFlow`): AI request started / succeeded
(sentences) / failed (reason code), AI result matching the shown text, and fallback (AI only: a row took Google, the
device or YouTube because the AI failed or left it out). With Google first, Google showing while the AI loads is the
normal order and is not an event; the AI request and failure events are the signal there. A fallback or matching
event repeated within the last 12 events is dropped. Cleared when a video loads. No text.

### Failure reason codes

`ChatGptException` already carries `ChatGptErrorKind`; it exposes it as a `reason` string through a small
`DiagnosticReason` interface. Other errors show the exception class name only.

### UI

- Preference `show_translation_source` (default false) → `DualSubUiState.showTranslationSource`; a switch in
  `OnlineTranslationSettingsRows`; added to `RESETTABLE_SETTING_KEYS` so Reset all settings clears it.
- `TranslationSourceTag` (10 sp, subdued) under the Subtitle 1 and Subtitle 2 lines in `LearningSubtitleOverlay` and
  `CompactSubtitleCard`; shown only while the setting is on and the line has real translated text.
- `LocalTranslationSourceInfo` provides the flag and the events to both places; the details dialog is a plain
  `AlertDialog` in the app theme. Strings in `strings_translation_source.xml` in all 8 languages.

## Acceptance criteria

1. Setting off (default): nothing new on screen; existing tests unchanged.
2. Google first → label `Google Translate`; ChatGPT result replaces it → `ChatGPT Subscription`.
3. ChatGPT fails → Google stays and is labeled `Google Translate`; an event records the failed AI request and its
   reason.
4. AI only, success → ChatGPT; AI only, failure → the real fallback source.
5. Subtitle 1 ChatGPT and Subtitle 2 Google (and both ChatGPT) are labeled independently.
6. A ChatGPT cache hit reads `ChatGPT Subscription (cached)`; a reply of this run reads `ChatGPT Subscription`.
7. Changing the target language, seeking or reloading never shows another language's or video's label.
8. A stream that fails before completing creates no ChatGPT label for text the model did not finish.
9. Identical Google and ChatGPT text keeps the Google label and records the identical-result event.
10. Text without a recorded origin reads `Unknown`.

## Test strategy

- JVM (JUnit4, deterministic fakes, no mocks): origin selection per producer; `translatePlaybackWindowTracked` with
  upgrades (cases 2, 3, 9); `AiSubtitleTranslator` live vs cached origin and failure event; Subtitle 1/2 independence;
  language-keyed `ThirdLines`; a failed stream; label text mapping; diagnostics ring, dedupe and no-secret check.
- Compose (instrumented, compiled and packaged here): tag shown with the setting on, hidden off, text updates when
  the origin changes. Not executed without a device.
- `testDebugUnitTest`, `formatCheck`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`, Superpowers review.

## Release intent

Touches `app/src/main/**`, so the shipped APK changes: **patch** by default (AGENTS.md). Not decided by the agent;
the owner may choose another. No push, PR or release without explicit approval.

## Known limitations

- The label reports what produced the text on screen. "Cached" means read from the AI disk cache, not produced by a
  request in this app run; a memory hit of a sentence translated in this run stays "live".
- The live-caption fallback (no stored track) translates one line at a time and has no per-row record, so it shows
  no label at all rather than a guess.
- A ChatGPT reply that dies half way keeps the sentences it finished (existing behavior); those are labeled
  ChatGPT because the model really wrote them, the unfinished ones keep Google's label, and the diagnostics record
  the request as failed with the number of sentences kept.
- `AI result available but identical` is an event, not a second label: the shown text and its label are unchanged.
  "Identical" is a whitespace-insensitive containment test (the existing `alreadyShows`), so it also fires when the
  AI's text merely contains Google's; the label is still correct because the shown text stays Google's.
- A sentence read back from the disk cache after the memory cache evicted it in the same run still reads as live.
- An event from a request of the previous video can land just after the list is cleared at a new load; events are
  filtered by language only. Harmless, and the list is a debug aid.
- The AI assistant does not know this debug switch (it is not a learner setting).
- Verified only with fakes until the owner plays a real video.

## Implementation record

Implemented as designed, with these points:

- `translatePlaybackWindow` and `runThirdLanguage` stay as thin wrappers (origin `null`), so existing callers and tests
  are untouched; the app uses `translatePlaybackWindowTracked` and `runThirdLanguageTracked`.
- The per-row source order (AI, AI wait, Google, device) moved from an inline lambda in `followGoogleTranslation` into
  `chooseTranslation` (`ui/TranslationChoice.kt`) so AI only and its fallback could be tested; order and error handling
  are unchanged, confirmed by the Superpowers review. The prefetch step keeps its former behavior inline.
- Review finding fixed: an entry is marked as written by this run before it enters the cache, so a live line can no
  longer read "(cached)". Fallback events are recorded for AI only; see Diagnostics events.

Checks (JDK 25 from Android Studio, `python` shim for `formatCheck`):

- `testDebugUnitTest`: 1187 tests, 0 failures (17 new in `TranslationSourceTest`: Google first, ChatGPT replaces,
  ChatGPT fails, AI only success and fallback, Subtitle 1/2 independence, disk-cache provenance, language/video/seek,
  incomplete stream, identical text, unknown, labels, diagnostics). `SubtitleHighlightTest` lists the new
  preference.
- `formatCheck`, `lintDebug` (including MissingTranslation for the 8 languages), `assembleDebug` and
  `assembleDebugAndroidTest`: passed. `complexityCheck` fails on `DualSubExperience` 25/25 exactly as on a clean HEAD.
- `TranslationSourceUiTest` (Compose) is compiled and packaged, **not executed**: no device or emulator here.
- **Not verified:** the labels on a real German video with ChatGPT Subscription, tap handling over the WebView overlay,
  and the look on the Light theme. The owner should check the acceptance scenario on the phone.
