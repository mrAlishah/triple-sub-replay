# Translation mode survives an app restart

## Status

Owner request 2026-10-30 (bug fix). Related: [AI subtitle translation](2026-10-28-ai-subtitle-translation.md) (the modes) and
[ChatGPT Subscription for the AI assistant chat](2026-10-29-chatgpt-subscription-chat.md) (shipped in the same request). Implementation
record at the end.

## Problem

Settings → Captions & translation → Translate subtitles with: choosing **AI translation (best quality)** or **AI only** works until the
app is closed; after the next start the mode is **Google Translate** again. **On this device only** and **Google** were not affected.

## Root cause

The mode is not one value. It is three saved preferences: `translation_engine` (Google or the device), `ai_subtitle_translation` and
`ai_subtitle_translation_only`, and the selected row is derived from them (`subtitleTranslator(engine, ai, aiOnly)`).

`setSubtitleTranslator` saved all three. The start-up state (`AppViewModel`'s initial `DualSubUiState`) restored only
`translationEngine`; `aiTranslation` and `aiTranslationOnly` kept their defaults (`false`). So every start read engine = Google, AI =
off, which is Google Translate, while the two AI values were still correctly stored and never read.

History: the original change (`9396b2b`, `09c116e`) read both flags at start-up and upstream `main` still does. The fork's
`mydevelop` history, which this branch descends from, has no read of either key, so the two lines were lost there (most likely in the
merge resolution of that state block; the exact merge was not identified). Nothing else overwrites the mode: the runtime fallbacks
(Google refused, AI failed, on-device taking over) only set display state (`onDeviceFallback`, failure detail), and the only writers of
the three keys are the Settings setter, the assistant's engine switch (engine key only) and "Reset all settings".

## Fix

- `translation/TranslationEngine.kt`: `StoredTranslationMode`, `SharedPreferences.storedTranslationMode()` (reads the three values, Google
  only when nothing is saved) and `SharedPreferences.saveTranslationMode(translator)` (writes them). One pair of functions for both
  directions, so saving and restoring cannot drift apart again.
- `AppViewModel`: the start-up state takes `translationEngine`, `aiTranslation` and `aiTranslationOnly` from `storedTranslationMode()`;
  `setSubtitleTranslator` saves through `saveTranslationMode`. Nothing else changes: not the translators, the Google-first upgrade, the
  AI-only wait, the caches, Re-translate, the diagnostics, the provider assignment or the saved models and effort.

## Acceptance criteria

1. Each of Google, AI translation (best quality), AI only and On this device is still selected after the app is closed and opened again.
2. A new installation with nothing saved starts on Google Translate.
3. Changing the mode leaves no stale AI flag (AI only then Google reads back as Google).
4. Reading the saved mode writes nothing; a runtime fallback never changes the saved mode.
5. The assistant's Google / on-device switch changes only the engine; the AI choice is still there when it switches back.

## Validation

JVM unit tests only (`TranslationModePersistenceTest`, with a simulated restart that keeps only the stored values, plus a source guard on
the exact start-up lines). No device test, no Python. The owner checks the restart on the phone.

## Implementation record

Fix as designed above; changed files: `translation/TranslationEngine.kt`, `ui/AppViewModel.kt`, the shared `FakePrefs` in
`AiTranslationConfigTest.kt` (now also stores booleans), new `TranslationModePersistenceTest.kt`.

**Checks run (Gradle/JVM only, no Python, no device):**
- `testDebugUnitTest`: passed, including the new persistence tests.
- `assembleDebug`, `assembleDebugAndroidTest`: passed.
- `complexityCheck`: fails on the one issue that already fails on a clean HEAD (`DualSubExperience` in `DualSubApp.kt`); nothing new.
- `lintDebug`: reported 2 errors, both from the ChatGPT chat commit and not from this fix (`remember` returning `Unit` in
  `LearningPlayerRoot.kt`, a mis-indented line in `AiAssistantController.kt`). Both were fixed; **the re-run was stopped before it
  finished, so the fixes are not lint-verified.**
- `formatCheck`: not run (the project's task calls Python, which the owner excluded).
- No device or UI test was run. **The restart behavior on a phone is unverified** and is left to the owner (select AI translation,
  close the app completely, reopen, open Captions & translation; repeat for AI only, Google and On this device).
