# AI translation: one provider, model and effort selector

## Status

Owner request 2026-10-29. Builds on [ChatGPT Subscription translation](2026-10-29-chatgpt-subscription-translation.md) (verified on the
owner's phone: sign-in, plan usage, single-sentence translation) and
[translation source diagnostics](2026-10-29-translation-source-diagnostics.md) (committed as `1ad08b8`). Implementation record at the end.

Start state: branch `feat_ai_chatgpt_subscription`, `git status --short` empty (the diagnostics work the request calls
"uncommitted" was committed in `1ad08b8`, so nothing needs protecting; it stays untouched).

## Context / problem

Settings → Captions & translation → AI today is a mix: an "AI access" chip pair (keys / ChatGPT), a wrapped row of model
chips (the AI assistant's service only), and, for ChatGPT, a second chip row of models. The translation provider is not a
setting of its own: it is whatever service the AI assistant uses, so choosing Gemini for subtitles means changing the
assistant. There is no effort control, and Claude and Gemini subscriptions are asked about but have no documented
third-party path.

## Goals

- One **AI provider** dropdown, one **AI model** dropdown, one **Reasoning effort** dropdown, a provider-specific connection
  card and one **Test translation** action, replacing the chip rows.
- The translation provider is independent of the AI assistant's provider; existing users keep today's behavior until they
  choose (the assistant's service is the starting choice).
- Model lists come from the provider (ChatGPT: the account's catalog; keyed services: their model list), filtered to chat
  models; a saved model that disappeared is replaced by a safe default with a visible note.
- Effort options come from what the model supports and are sent only when valid.
- Selections persist per provider and come back when the user switches away and back.

## Non-goals / anti-goals

- No fake or non-functional "Continue with Claude / Gemini" button. No reuse of Claude Code or Gemini CLI credentials, cookies,
  spoofed client identities or undocumented endpoints.
- No second translation engine: `AiTranslationModel` and `AiSubtitleTranslator` are reused; the ChatGPT Subscription
  implementation, OAuth and its Subtitle 2 policy stay as they are.
- No automatic billing, no key creation, no paid fallback. Google or the device remains the fallback; a selected provider
  that is not usable never turns into a different provider.
- Keyed services other than ChatGPT keep today's policy: Subtitle 2 uses the AI only with ChatGPT Subscription.
- No change to selection, Google Translate sharing, Vocabulary, Practice, Anki; no change to the AI assistant's own provider,
  model or thinking settings.

## Provider eligibility matrix (audited 2026-10-29)

| Access | Status in this change | Evidence |
| --- | --- | --- |
| ChatGPT Subscription | **Supported, tested on the owner's device** (OAuth PKCE, plan usage, one-sentence translation) | developers.openai.com/siwc; [spec](2026-10-29-chatgpt-subscription-translation.md) |
| Claude API | **Supported** with the user's `sk-ant-` key (existing `AiKeyStore`), billed at standard API rates | platform.claude.com API docs |
| Claude monthly API credits (Max/Team) | Not a subscription path. They are Console credits for API use; the app cannot see the balance and says only that API usage may be charged or covered by Console credits | Anthropic support: credits for Max/Team plans are separate from plan usage and are not usable in Claude Code or the Claude apps |
| Gemini API | **Supported** with the user's key (existing). Free tier where the project/model is eligible: decided by the server's answer, never guessed; a key is needed even when usage is free; billing is not enabled by the app | ai.google.dev pricing and quota docs |
| OpenAI API, OpenRouter, OpenCode Zen/Go, custom OpenAI-compatible | **Preserved** (existing keys and transport) | existing code |

If an officially permitted subscription path appears later it becomes one more entry of `AiAccessOption` plus a
`TranslationAccess` implementation; the dropdown, persistence and effort code do not change.

## Design

### Concepts kept apart

*Provider/access* (`chatgpt`, `gemini`, `claude`, `openai`, `openrouter`, `opencode_zen`, `opencode_go`, `custom`) ≠
*credential* (OAuth session vs API key) ≠ *model id* ≠ *effort*. "Claude API" and "Claude Subscription" are different ids;
only the first exists.

### Pure model (`assistant/AiTranslationChoice.kt`)

- `AiAccessOption(id, labelRes, noteRes)` and `aiAccessOptions()` give the dropdown: ChatGPT Subscription first,
  then the API providers.
- `AiEffort` (Automatic, Off, Minimal, Low, Medium, High, Extra high, Max) maps to the wire value.
- `aiEffortOptions(access, model)` returns only valid levels; `effectiveAiEffort(saved, options)` falls back to Automatic when
  the saved level does not exist on the chosen model, without erasing the saved choice.
- Storage keys: `ai_translation_provider` (empty: follow the assistant's service, today's behavior), `ai_translation_model_<id>`,
  `ai_translation_effort_<id>`. The legacy `ai_translation_model` is read as the model of the assistant's service when no
  per-provider value exists; `ai_access_method` / `chatgpt_translation_model` keep their meaning and are still written, so
  downgrading or an older build reads them correctly. Reset all settings clears all of them. No secret is stored here.

### Effort rules (sent only when valid; never both knobs)

| Provider | Options | Wire | Notes |
| --- | --- | --- | --- |
| ChatGPT Subscription | Automatic only | none | OpenAI's preview pages list rejected request fields and do not list `reasoning`, but also do not document it or advertise per-model levels in `/v1/models`. Not validated, so omitted. Enabling it later is a capability flag plus a body field |
| Claude API | Automatic + the levels the model's `capabilities.effort` reports (low, medium, high, xhigh, max) | `output_config.effort` | Automatic omits the field: the model's default. List unavailable → Automatic only |
| Gemini API | Automatic, Low, Medium, High; Minimal on Gemini 3 models; Off on Gemini 2.5 non-Pro models | `reasoning_effort` on Google's OpenAI-compatible endpoint, which Google maps to `thinking_level` or `thinking_budget` per model, so only one is ever sent | Automatic keeps today's behavior (thinking off where the model allows it, dropped automatically if the model refuses); it is not sent for Gemini 2.5 Pro or Gemini 3, which cannot turn thinking off |
| OpenAI API | Automatic (today: `minimal`), Low, Medium, High | `reasoning_effort` | A model that rejects it falls back through the existing "drop the setting" path |
| OpenRouter | Automatic, Low, Medium, High | `reasoning_effort` | as above |
| OpenCode Zen/Go, custom | Automatic only | none | no documented contract |

A rejected effort is dropped for the rest of the session (existing behavior) and the failure is shown in the diagnostics; the
saved choice is not overwritten.

### Models

- Keyed: the existing `AiModelLister` (`GET {base}/models`, Anthropic with `limit=1000`) with a stored key, filtered by
  `aiModelCanChat`. `AiModelInfo` gains the display name and, for Claude, the effort levels read from `capabilities.effort`.
  The provider's `suggestedModels` fill the list only while no key or no list is available, with a visible "add a key / refresh"
  state. Loading, failed, empty and refresh are states of the dropdown.
- ChatGPT: the account's catalog already loaded by `ChatGptSubscription` (`visibility == "list"`).
- The id is stored; the display name is only shown.

### Request path and cache

`aiTranslationModel(...)` builds `KeyedAiTranslationModel(provider, model, effort, ...)`; the cache scope is
`<provider>/<model>` for Automatic (unchanged, so existing caches stay valid) and `<provider>/<model>@<effort>` for an explicit
effort, so output that may differ never shares an entry. ChatGPT's scope is unchanged (effort not sent). Switching provider,
model or effort reloads the playing video exactly as a model change does today (`loadVideo` bumps the load generation, so
stale answers are dropped; rows are rebuilt from the new run). Nothing is sent to a provider until a translation is needed
or Test translation is tapped.

### UI (Settings → Captions & translation, below "Translate subtitles with", for the AI modes)

1. **AI provider** dropdown (Material 3 `ExposedDropdownMenuBox`, read-only field, 48 dp rows). Unsupported entries disabled with
   a one-line reason.
2. **AI model** dropdown: loading, empty, failed and refresh rows; display names; the saved model if still offered.
3. **Reasoning effort** dropdown: valid levels only; the helper line explains Automatic or why the choice is fixed.
4. **Connection card** by provider: ChatGPT (status, Continue/Refresh/Disconnect, account), key providers (key present or
   missing, billing/eligibility line, button to the existing AI assistant key screen).
5. **Test translation**: provider, model, effort, language pair, result with the verified origin label, error without secrets.
6. **Show translation source (debug)** stays below, unchanged.

Responsive at 320 dp (single column, fields full width), light and dark theme through `MaterialTheme.colorScheme`, content
descriptions on the fields and menu items, `testTag`s on each field and option.

## Acceptance criteria

1. The provider dropdown lists ChatGPT Subscription, the API providers and the two unsupported subscriptions (disabled); a
   disabled entry cannot be selected and starts no sign-in.
2. ChatGPT keeps its connection, model and translation without an API key; Gemini and Claude use only their own stored keys;
   an OAuth token is never used as a key and a key is never used for ChatGPT.
3. Switching ChatGPT → Gemini → ChatGPT restores each provider's model and effort.
4. Each model offers only supported effort levels; unsupported parameters are absent from the serialized request.
5. Google-first/AI-upgrade, AI-only fallback, Subtitle 1/2 sources, source diagnostics and cache boundaries are unchanged.
6. A missing key for the selected provider means Google or the device translates, never another provider.
7. Provider/model/effort changes during playback cannot show an older run's text.
8. Test translation uses the selected provider's real integration and shows its verified origin.
9. All new strings exist in the eight languages; lint passes.
10. Existing users: same provider, model and behavior after upgrade without touching Settings.

## Risks

- **Model lists and capability fields can change.** Mitigation: tolerant parsing; unknown capability → Automatic only.
- **Gemini's compat layer** is Google's mapping to `thinking_level`/`thinking_budget`; Gemini 3 vs 2.5 differ. Mitigation: option
  lists by family, Automatic safe everywhere, refusal drops the setting.
- **Claude `xhigh`/`max`** want a large `max_tokens`; the translation request keeps its existing token limit. Mitigation: those
  levels are offered but labeled as slower, and a 4xx drops the setting.
- **Live providers are not testable here** (no accounts, no device): only fake transports. See the implementation record.

## Test strategy

JUnit4 with fake transports and storages: option lists, persistence and migration, effort tables and effective-effort fallback,
serialized Claude/Gemini/OpenAI request bodies, model-list parsing (Claude capabilities, display names), cache scopes, provider
switching/stale request, no cross-use of credentials, strings per language. Compose tests (compiled; run only with a device):
dropdown operation, disabled entries, states, 320 dp, dark theme, TalkBack semantics.

## Release intent

Touches `app/src/main/**`: **patch** by default (AGENTS.md); not decided by the agent. No push, PR or release.

## Implementation record

Implemented as specified, with these points and deviations:

- **Provider is its own choice, defaulting to the assistant's service.** `AiTranslationChoices.accessId` reads the saved choice
  every time, so a user who never chose follows the AI assistant's service exactly as before; choosing even the provider
  already in force pins it. ChatGPT Subscription keeps `ai_access_method`; the API provider is `ai_translation_provider`.
- **Automatic is the subtitle default, not "the model's default" everywhere.** It sends nothing for Claude, OpenRouter, OpenCode
  and custom services, `minimal` for OpenAI and `none` for Gemini as before, except on Pro and Gemini 3 models (also behind an
  alias such as `gemini-pro-latest`), which cannot turn thinking off and get nothing. The existing "drop a refused setting"
  path stays for subtitles; Test translation reports a refused explicit level instead.
- **Gemini uses `reasoning_effort` on Google's OpenAI-compatible endpoint** (existing transport) rather than a second native
  client; Google maps it to `thinking_level` or `thinking_budget`, so one knob is ever sent.
- **ChatGPT effort stays Automatic only.** OpenAI's preview pages neither list `reasoning` as rejected nor document it, and
  `/v1/models` shows no per-model levels, so nothing was validated; enabling it needs a capability flag and a body field.
- **Saved Claude level before the list is read.** The saved level is trusted so a restart translates with it; when the list
  later says the model lacks it, Automatic takes over and the video translates again once.
- **A vanished model** is replaced in force (a visible note), while the saved choice is kept until the user picks again.
- Removed: the chip composables, `selectAiTranslationModel`, `Context.aiTranslationModel`, 13 unused strings in 8 languages, and
  `ChatGptSubscriptionUiTest` (its cases moved to `AiTranslationSettingsUiTest`). `aiTranslationModelChoices/Name` stay only
  because the older `aiTranslationModel(settings, …)` and its tests use them.
- Review (Superpowers, final code): no critical findings; fixed a provider stuck on "Loading models…", a frozen provider
  snapshot, the unapplied saved Claude level, Gemini Pro aliases, unused imports, too-strong billing text, and weak tests.

Checks (JDK 25 from Android Studio, `python` shim for `formatCheck`):

- `testDebugUnitTest`: all pass (50+ new tests in `AiTranslationChoiceTest`, `AiTranslationConfigTest`; existing suites
  updated for the removed state field). `formatCheck`, `lintDebug` (MissingTranslation, 8 languages), `assembleDebug` and
  `assembleDebugAndroidTest`: passed. `complexityCheck`: only the known baseline failure (`DualSubExperience` 25/25).
- `AiTranslationSettingsUiTest` and `OnlineTranslationUiTest` are compiled and packaged, **not executed**: no device or
  emulator is available here. Dark theme was not exercised.
- **Not verified live:** Gemini and Claude keys, real model lists, a real effort refusal, the ChatGPT dropdown flow on a phone.

## Follow-up (owner request 2026-10-29): only ChatGPT Subscription

The disabled "Claude Subscription" and "Gemini Subscription" rows, their constants, strings and tests were removed. ChatGPT Subscription is
the only subscription sign-in. Claude and Gemini remain available only through their API keys. Anthropic documents no third-party path for
Claude subscriptions, and no Google documentation was found for Gemini AI Pro/Ultra; if either appears it is added as a new provider.
