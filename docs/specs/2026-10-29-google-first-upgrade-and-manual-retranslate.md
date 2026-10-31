# Google-first AI upgrades that always arrive, and a manual "Re-translate with AI"

## Status

Owner request 2026-10-29, after device testing. Verified by the owner: ChatGPT Subscription sign-in and Test translation; **AI only**
translates Subtitle 1 and Subtitle 2. Reported: with **AI translation (best quality)** Google's lines show and are not replaced by
ChatGPT's. Builds on [ChatGPT Subscription](2026-10-29-chatgpt-subscription-translation.md),
[source diagnostics](2026-10-29-translation-source-diagnostics.md) and the
[provider selector](2026-10-29-ai-translation-provider-selector.md) (still uncommitted in the working tree; this change touches
overlapping files and keeps them intact). Implementation record at the end.

## Reproduction

1. Settings → Captions & translation: Translate subtitles with = AI translation (best quality); AI provider = ChatGPT Subscription.
2. Original German, Subtitle 1 English, Subtitle 2 Persian. Play, or pause, a German video.
3. Google's lines appear. They stay Google's although ChatGPT is signed in and AI only works.

## Investigation (what was traced and what was confirmed)

Pipeline traced in the current local files: `AppViewModel.followGoogleTranslation` → `translatePlaybackWindowTracked` (the window loop) →
`prefetch` → `AiWindowTranslation.ask` → `AiSubtitleTranslator.translateAll/missing/translateNow` (`attempted`, `asking`, `restUntil`)
→ `arrivals`/`updates` → `TranslationUpgrades` → `withUpgrades` → `publishSubtitleWindow`; Subtitle 2: `startThirdLine` →
`runThirdLanguageTracked` → `publishThirdLines`.

| Question | Finding |
| --- | --- |
| Is the AI scheduled in Google-first? | Yes, but **only from `prefetch`**, which the loop calls **only while some row still lacks a translation** |
| Does an AI answer reach the screen? | Yes: deterministic fakes through the real loop, the real `AiWindowTranslation` and the real `AiSubtitleTranslator` show Google's line replaced by the AI's, for plain and sentence-split rows (probe tests) |
| Is a failure retried? | **No.** `translateQuietly` swallows it, `restUntil` blocks asking for 30 s, and nothing asks again once the window is fully Google-translated (paused video, or no new rows) |
| Does a seek while the AI is busy upgrade the new position? | **No.** `ask` is a no-op while a request runs; when it ends nothing asks for the new window |
| Hypotheses ruled out | Update event not observed (observed), update not applied to rows (applied), slot mix-up (independent), `alreadyShows` hiding a *different* text (does not), generation overwrite (guarded by `isCurrentLoad`) |
| Cache / `attempted` suppression | Only suppresses sentences the model was asked about and left out; not the cause |
| Identical text | Kept as Google plus an `agrees` event, by design; not a missing upgrade |

**Confirmed cause:** the AI request is edge-triggered by "a row lacks a translation" instead of level-triggered by "the window still
lacks AI translations". Any condition that makes the first request not satisfy the window (a failed or slow request, a seek, a request
that omitted sentences, a pause) leaves Google's text permanently. Both variants are reproduced by failing tests before the fix.
What could not be confirmed here: the exact trigger on the owner's phone (no device); the new diagnostics events record it next time.

## Goals

- **A.** In Google-first mode the AI keeps being asked for what the playing window still lacks until it has it (bounded retry, never a
  tight loop), for Subtitle 1 and Subtitle 2 independently, playing or paused, across seeks. AI only is untouched.
- **B.** A deliberate **Re-translate with AI** for Subtitle 1, Subtitle 2 or both, for the current sentence (default), the current window
  or the entire video (warning, confirmation, progress, cancel), that really asks the provider again.
- **C.** Provenance stays truthful and the diagnostics record the scheduling, sending, receiving, upgrading and refreshing.

## Non-goals / anti-goals

- No second translation engine; `AiSubtitleTranslator` and `AiTranslationModel` are extended. No change to prompts, sentence mapping,
  timing, slot identity, selection, Google Translate sharing, Practice, Vocabulary, Anki, or the provider/model/effort settings.
- Force never means bypassing a service limit: the model's own gate (usage limit, ineligible, key rest) still applies.
- Subtitle 2 keeps its policy (AI only with ChatGPT Subscription): the refresh offers Subtitle 2 only where it uses the AI.
- No global cache clearing; no logging of tokens, e-mail or subtitle text.

## Design

### A. Level-triggered AI upgrades

- `TranslationUpgrades` gains `ensure(texts): Long?`. When the window loop is idle (everything shown), it calls `ensure` with the window's
  texts. `ensure` returns null when the AI owes nothing, else the delay before looking again; the loop then waits for the next playback
  change, AI arrival **or** that delay.
- `AiWindowTranslation.ensure` asks the AI for the window again when `AiSubtitleTranslator.owes(...)` says some sentence has no AI
  translation and was not already answered/omitted (`attempted`), throttled to one look per second and one retry per
  `AI_RETRY_MS` (10 s). A request in flight is left alone (`ask` no-ops), so there are never parallel duplicates; the translator's
  30 s failure rest still spaces real requests, and the model's gate/pacer still stop calls locally.
- Wired for Subtitle 1 (`followGoogleTranslation`) and Subtitle 2 (`startThirdLine`) when the mode is not AI only.
- `withUpgrades`: a row whose AI text is **different** is replaced even if the old AI text is contained in the new one (the
  containment shortcut stays for rows showing another source's text, which keeps the `agrees` behavior).

### B. Forced refresh at the translator boundary

- `AiSubtitleTranslator.forceTranslate(model, source, target, texts, onProgress)`: for the sentences of `texts`, one request per batch
  that **ignores the memory and disk caches and `attempted`**, waits its turn on the same request lock (no concurrent duplicates),
  ignores only the local failure rest, validates the whole reply, and **only then** replaces the cache entries (memory + disk, scope
  and language unchanged), marks them written-this-run (live) and bumps `updates`. On any failure nothing is changed. A sentence
  already being refreshed is skipped, so rapid taps do not queue duplicates.
- `TranslationRefresher` (UI layer, testable) runs the request for the chosen slots (each target requested **directly from the
  Original**, one slot at a time), exposes `RefreshState` (running slots, failed slots, done/total), supports cancel, and ignores
  results when the context (video, generation, languages, model) changed.
- Context captured by `runStoredTranslation` (store, languages and AI model per slot, window texts), cleared when the run ends.
- Displayed text is untouched until success; the open loops pick the new text up through the same `updates` path as an upgrade.

### C. UI

- Source-label details dialog gets **Re-translate with AI**; the overlay's action row (the settings/close buttons) gets a compact
  refresh button shown whenever an AI translation mode is on, so it works without the debug labels.
- `RetranslateDialog`: slot (Subtitle 1 / Subtitle 2 / Both; default the one opened from), scope (Current sentence / Current window /
  Entire video), usage note, an extra confirmation line and sentence/request estimate for Entire video, progress and Cancel.
- While a slot refreshes, its line shows a small "Refreshing…" mark; a failed refresh shows a concise error until the next try.
  The recorded origin of the text on screen does not change during a refresh.

### Diagnostics events (counts, language, provider/model, reason code only)

`AI_SCHEDULED`, `AI_REQUEST` (sent), `AI_SUCCEEDED` (response complete, count), `AI_UPGRADED` (rows replaced), `AI_IDENTICAL` (agrees),
`REFRESH_REQUESTED`, `CACHE_BYPASSED`, `REFRESH_SUCCEEDED`, `REFRESH_FAILED`, `FALLBACK`, `AI_FAILED`.

## Acceptance criteria

1. Google first, then a deliberately different AI text, with a controllable delay: the screen shows Google's, then the AI's, and the
   source label changes; independently for Subtitle 1 and Subtitle 2; playing and paused.
2. AI failure, timeout, rate limit: Google stays; once the rest ends the AI is asked again and the upgrade arrives, without a seek.
3. A seek while the AI is busy: the new position is upgraded after the busy request ends.
4. Identical text keeps the Google label and an `agrees` event.
5. AI only behaves exactly as before (no `ensure` there); Google only and on-device only are unchanged.
6. Re-translate: with the sentence cached, on disk and `attempted`, a **new** provider request is made, the visible text changes without a
   reload, the right slot and only that slot's cache entry change; a failure keeps the old text; repeated taps do not duplicate;
   Both asks each target from the Original.
7. Entire video asks for confirmation, shows progress and can be cancelled; stale results after a video, language, model or provider
   change are dropped.
8. No regression in the existing suites.

## Test strategy

JUnit4 with fakes (no mocks): the real window loop + `AiWindowTranslation` + `AiSubtitleTranslator` with a controllable AI and a Google
stub; `forceTranslate` with a request counter and a disk cache; `TranslationRefresher` with fake contexts; the `withUpgrades` rule;
events. Compose tests (compiled; run only on a device): the dialog, the overlay button, the refreshing mark, labels.

## Release intent

Touches `app/src/main/**`: **patch** by default (AGENTS.md); not decided by the agent. No push, PR or release.

## Implementation record

Implemented as designed. What the work established and changed:

- **Root cause (confirmed, reproduced by failing tests before the fix):** the AI was asked from `prefetch` only, which the window loop calls
  only while some row lacks a translation, and `AiWindowTranslation.ask` no-ops while a request runs. Variants reproduced: (1) a failed
  first request with the window already Google-translated, (2) a seek while the AI is busy. A paused video upgrades fine when the
  first request succeeds (kept as a regression guard). The exact trigger on the owner's phone could not be observed (no device); the
  new events (`AI_SCHEDULED`, `AI_REQUEST`, `AI_SUCCEEDED/FAILED`, `AI_UPGRADED`) record it next time.
- **Fix:** `TranslationUpgrades.ensure` called in the loop's idle branch; `AiWindowTranslation.ensure` (new sentences asked at once, the
  same ones again only after `AI_RETRY_MS`, the translator's failure rest waited out, a request in flight never doubled);
  `AiSubtitleTranslator.owes/restMs`; shared `aiTranslationUpgrades` used by Subtitle 1 and Subtitle 2 and absent for AI only.
- **`withUpgrades`:** an AI-origin row is replaced by any different AI text (a fresh answer may only extend the old one) when all rows
  of its sentence are in the window; other sources' text that the AI merely contains keeps its label.
- **Force refresh:** `AiSubtitleTranslator.forceTranslate` ignores memory, disk and `attempted`, ignores only the local failure rest,
  queues on the request mutex, validates the whole reply and commits memory, disk and `updates` in one `NonCancellable` step. A test
  found and fixed a real race where a cancel between the memory and disk writes left them different.
- **UI:** `RetranslateDialog` (slot, scope, allowance note, entire-video warning with estimate and confirmation, progress, stop), an
  overlay button that needs no debug setting, a "Re-translate with AI" action in the source-label details, a Refreshing mark and a
  failure mark under the line. The text on screen and its recorded origin do not change while a refresh runs.
- Review (Superpowers, final code) fixed: uncaught exceptions in the refresh path, dialog scrolling, the button's accessible name and
  48 dp rows, event flooding (coalesced), retry spacing for any failure kind, exact comparison at window edges, a vacuous test.

Checks (JDK 25 from Android Studio, `python` shim for `formatCheck`): see the final report in the conversation; `testDebugUnitTest` all
pass, `formatCheck`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` pass, `complexityCheck` only the known `DualSubExperience`
25/25. Compose tests (`RetranslateUiTest`) are compiled and packaged, **not executed** (no device).

Not verified / known limits: the live ChatGPT behavior on the phone; the overlay's fullscreen width budget still reserves two action
buttons while the new button makes three (not changed, `OverlayDragRegressionTest` pins those numbers); the entire-video walk reads the
track in 400-row chunks by start time and can skip rows that share the last start time of a chunk; a multi-batch entire-video run keeps
the batches already committed if it fails or is stopped; the retry loop asks again every 10 s at most while a permanent block (for
example "not eligible") lasts, which costs no network call because the model's gate refuses locally.

## Follow-up (owner request 2026-10-29)

- The Re-translate button is **always shown**: in the transcript panel header between the settings gear and hide, and in the overlay's
  action row. The shared header actions live in one helper (`PanelHeaderActions`).
- It works in **every mode that translates with Google**, including Google only: a manual Re-translate always asks the chosen AI
  provider (ChatGPT Subscription or a keyed service). In Google only the AI model is prepared only to show what the manual request
  brought (`TranslationUpgrades` without `ensure`), so the AI is never asked by itself there. With no provider ready, the dialog says
  to connect one and Start stays disabled; on-device-only has no manual refresh.
- Not validated: the final check run after this follow-up was stopped before it finished, so this last step has unit tests written
  (`inGoogleOnlyAManualRefreshBringsTheAiTextWithoutAnyAutomaticRequest`) but they have not been run.
