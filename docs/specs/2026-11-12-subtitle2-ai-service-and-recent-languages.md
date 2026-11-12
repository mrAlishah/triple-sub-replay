# Subtitle 2 uses the selected Translation AI Service, and keeps recent languages

Owner request 2026-11-12. Fork-only (FC-01, FC-05).

## Problem

1. `AppViewModel` gave Subtitle 2 the AI model only with ChatGPT Subscription (`ai.takeIf { chatGpt.useSubscription }`), so a keyed
   Translation AI Service (Gemini API, OpenRouter, OpenAI, Claude, ...) translated Subtitle 1 but Subtitle 2 stayed on Google or the device.
2. The Subtitle 2 language picker never showed a Recent group; only Subtitle 1 ("Translate to") did.

## Decision

- Subtitle 2 gets the same model as Subtitle 1 (`selectedAiModel()`), keyed or ChatGPT Subscription. The mode keeps its meaning:
  Google only and On device ask no AI; AI with Google shows Google's/the device's line first and the AI's replaces it; AI only waits for the AI,
  and the existing fallback and diagnostics apply. Subtitle 2 is still translated straight from the Original, in its own language, through its
  own `AiWindowTranslation` (own cache scope per language pair, one model so both lines share pacing and the stop on a limit).
  ChatGPT Subscription never falls back to a keyed service (unchanged: the model is whatever `selectedAiModel()` chose).
- Subtitle 2 has its own history `recent_third_languages` (newest first, 3 kept, same helpers as `recent_target_languages`). It is global like
  Subtitle 1's history, not per tab; backup scope BACKUP (never shared), cleared by Reset all settings.

## Acceptance

- A keyed model given to `ThirdLineTranslation.start` is asked in Subtitle 2's language from the Original (`ThirdLineTranslationTest`).
- No AI model: no AI request, device/Google line stays.
- AI line replaces the fallback line and each sentence is asked once.
- `RecentLanguagesTest`: independent key, dedupe, unknown codes dropped, round trip, reset, backup spec.

## Implementation record

Changed `AppViewModel` (pass `ai`), `TripleSlotSettings.setThirdLanguage` (remember), `DualSubUiState.recentThirdLanguages`,
`TripleSubtitleControls.recentLanguages`, `LearningPlayerRoot`, `SubtitleSettingsDialog` (THIRD recent group), `ForkSettingSpecs`,
`RESETTABLE_SETTING_KEYS`. Offline JVM tests: full suite 1666 passed, Debug APK built. Manual Android acceptance by the owner (2026-11-12): passed.

## Known follow-up

`AiTranslationConfigTest.resettingStartsAgainFromTheAssistantsService` failed once in a full run (`ComparisonFailure` at the `config.state.value.accessId` assertion after `resetChoice()`); it passed in 25 isolated reruns and in later full runs. Suspected cause: `state` can lag one emission while a model-list load writes from a background thread (`current()` is the exact read). Not reproduced, not changed here.
