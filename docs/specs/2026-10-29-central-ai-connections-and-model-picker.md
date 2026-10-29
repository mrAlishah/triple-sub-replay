# Central AI connections in Settings → AI Assistant, and an editable model picker

## Status

Owner request 2026-10-29. Builds on [ChatGPT Subscription translation](2026-10-29-chatgpt-subscription-translation.md),
[AI translation provider selector](2026-10-29-ai-translation-provider-selector.md) and
[translation source diagnostics](2026-10-29-translation-source-diagnostics.md). Implementation record at the end (empty until done).

Start state: branch `feat_ai_chatgpt_subscription`, HEAD `f5c22bd`, `git status --short` empty. Nothing to protect.

## Owner decisions taken before coding (2026-10-29)

- **Chat adapter is phased.** ChatGPT Subscription is offered under AI Service and has a Connection panel and works for subtitle
  translation. The assistant *chat* does not yet talk to it (the chat transport is chat-completions with keys; a Responses
  adapter with tools, streaming, search and attachments is a separate spec). Choosing ChatGPT for chat shows a clear "not
  available for chat yet" state; the chat never falls back to a keyed, billable service on its own.
- **Disabled Claude/Gemini Subscription rows stay removed** (the earlier follow-up in the provider-selector spec stands). The
  "unsupported subscription" state is covered by a unit test of the connection model only; no login control exists for them.

## Revision 2 (owner decision 2026-10-29): central configuration, two independent assignments

This section **supersedes** every earlier statement that conflicts with it (notably "the AI Service dropdown selects the chat
service" and "Captions & translation keeps provider/model/effort").

- **Central configuration** lives only in Settings → AI Assistant: connection (API keys / ChatGPT sign-in), model discovery, Model
  and Thinking. The existing **AI service** row (approved look, tag `ai_service_menu`) now only chooses *which service's
  configuration is shown and edited*. It never changes an assignment.
- **Chat AI Service** (new dropdown in AI Assistant, below the AI service row) is the chat assignment. It is today's
  `AiAssistantSettings.provider` / `chatGptService`. ChatGPT Subscription is listed but marked "Chat not available yet"; choosing it
  keeps the existing temporary unavailable state and sends nothing.
- **Translation AI Service** (compact dropdown in Settings → Captions & translation) is the translation assignment, today's
  `ai_translation_provider` / ChatGPT switch. It serves both AI translation (best quality) and AI only. Captions & translation keeps
  only: that dropdown, a one-line status with **Manage AI connection**, and **Test translation**. The Model and Effort dropdowns and
  every sign-in/key control are removed there.
- The two assignments never change each other. Both rows list every service with its state (Connected / Not connected; unsupported
  for chat where it applies). An assigned service that is unavailable keeps its assignment and shows its status; no fallback to
  another provider.
- **Model and effort are stored per service.** Model: the existing per-service `models[provider]` (the text box, still the single
  source of truth; ChatGPT uses its own `chatgpt_model` preference). Thinking: new per-service `thinkings[provider]` falling back
  to the old global `thinking`. Chat and translation both read these for their assigned service.

### Migration (no silent change, nothing discarded)

1. **Assignments.** The old translation default "follow the assistant's service" is frozen on first read: if `ai_translation_provider`
   is absent and ChatGPT is not selected, the current chat service key is written there once. Chat assignment is untouched.
   An existing ChatGPT translation selection stays ChatGPT and never becomes a keyed service.
2. **Model.** Legacy per-service translation models (`ai_translation_model_<id>`, the old `ai_translation_model`) are kept on disk.
   Translation uses (`aiTranslationModelChoice`): the central model once the user has edited that service's Model box
   (`ai_models_unified`), otherwise the legacy translation model when there is one, otherwise the central model, otherwise the
   default. While the two differ and the box was not edited, the Model panel says which model translation still uses; editing the box
   resolves it for both uses.
3. **Effort.** Translation uses the service's `thinkings[provider]` when the user set one, otherwise the legacy
   `ai_translation_effort_<id>`. Legacy xhigh/max/minimal/none values keep working until the chips are used for that service.
   Known limit: the four existing Thinking chips (Auto/Low/Medium/High) cannot set the extra Claude levels any more; the owner asked
   not to change that panel.
4. Credentials, OAuth sessions and translation caches are not touched; cache scopes (provider/model/effort) are unchanged.

### Scope of this phase

Central configuration UI, conditional API Keys / Connection panels, two assignments, removal of duplicate Provider/Model/Effort/
Connection controls from Captions & translation, preference migration, wiring and tests. Not in scope: the ChatGPT Responses chat
adapter (next task), Google-first/AI Only/re-translate/diagnostics changes.

## Revision 3 (owner request 2026-10-29): AI Connection title, and the chat's own gear

- **Rename.** The Settings panel "AI assistant" is titled **AI Connection** (user-visible strings in all 8 languages; the section
  key `AI_ASSISTANT`, the `ai_assistant` preferences and every class name stay). Its content and behavior do not change.
- **Chat gear.** The gear in the AI assistant panel opens a compact chat settings page: **Chat AI Service** (one dropdown, the same
  `AiServiceMenu` and the same `provider` / `chatGptService` assignment as before, so it is also the one Settings → AI Connection
  edits), then the chat's own Search and screen, Memory, Chat history and Permissions. It no longer shows the Connection (keys,
  sign-in) or Model and Thinking sections; those are managed only in Settings → AI Connection, and the page says so.
- **What the dropdown lists.** The services that are configured (an API service with a saved key, ChatGPT Subscription connected),
  plus the service chat is assigned to now, so a choice is never hidden. Rows say Connected / Not connected. A choice is never
  replaced by another service.
- **ChatGPT Subscription for chat** had no Responses adapter when this was written (the "not available for chat yet" card). The adapter
  landed in [ChatGPT Subscription for the AI assistant chat](2026-10-29-chatgpt-subscription-chat.md), which replaced that card by the
  account's real state; the translation assignment is untouched.
- Independence: the Translation AI Service (Captions & translation) is another preference and neither assignment writes the other.
- Tests: title string (all languages), gear page shows the dropdown and none of keys/OAuth/Model/Thinking, only configured services
  listed, choosing persists and does not touch translation, ChatGPT chat shows the unavailable card.

## Context / problem

Authentication lives in two places: API keys in Settings → AI Assistant → API keys, and the ChatGPT sign-in (Continue,
Refresh, Disconnect, status) inside Settings → Captions & translation → AI (`ConnectionCard`, `ChatGptConnection` in
`ui/AiTranslationSettings.kt`). Credential management should live in one place; subtitle translation should only show a
summary and a link. The assistant's Model field is a bare text box with no list.

## Goals

1. Settings → AI Assistant is the only place to add, test, refresh, remove or disconnect any AI credential.
2. The section titled "API keys" becomes **Connection** whose content follows the selected service's access method:
   key services → the existing API keys UI unchanged; ChatGPT Subscription → OAuth Connection states.
3. Captions & translation → AI shows `AI connection: <provider> · <status>` and **Manage AI connection**, which opens the AI
   Assistant settings on that provider's connection without changing the assistant's own service.
4. **Model and Thinking** keeps its layout; one dropdown **Available models** is added directly above the existing Model text box.
5. Chat and subtitle translation keep independent provider/model/effort choices (`ai_translation_*` vs `AiAssistantSettings`).

## Non-goals / anti-goals

- No second token store, no new login code: `ChatGptSubscription`, `ChatGptSession`, `AiKeyStore` are reused as they are.
  Existing sessions and keys stay valid; no secret is read, copied, re-sealed or migrated.
- No Responses chat adapter (phased above). No new subscription providers. No change to Thinking/Effort controls, translation
  modes, Google-first upgrades, diagnostics, Re-translate, timing settings.
- No redesign of the AI Service dropdown, the API key rows, or any other Settings section.
- No hard-coded model catalog: lists come from the provider's model-list call or ChatGPT's authorized catalog.

## Design

### Connection model (`assistant/AiConnection.kt`, new, pure and unit-testable)

`AiConnectionTarget` = `Key(AiProvider)` | `ChatGpt`. `AiConnectionState` = `NotConfigured | Connecting | Connected |
CredentialExpired | AuthorizationRequired | PermissionMissing | RateLimited | Unavailable`, derived by one function from
`ChatGptUiState`/`ChatGptStatus` or from the key list plus the last key test. Selecting a service is never "connected";
only credential state is. `accessMethod(target)` is the metadata the UI branches on (`API_KEY` | `SUBSCRIPTION_OAUTH`), never
the display name.

### Conditional panel

`AiAssistantSettingsSection` already renders `AiSettingsSection.KEYS` as an expandable card. Its title/summary/body now come from
the target's access method: `API_KEY` → existing `AiKeySettings` body untouched; `SUBSCRIPTION_OAUTH` → new `AiChatGptConnection`
body, moved from `ChatGptConnection` (same states, test tags kept, plus Test connection and the typed errors). The card's
expansion key stays `keys` so saved expansion state keeps working; the card title string changes to "Connection" for ChatGPT.
Switching service changes only which body shows; no credential is touched.

### Which service the panel shows

`AiAssistantSettingsSection(host, nested, connectionTarget: AiConnectionTarget? = null)`. `null` follows the AI Service dropdown.
Manage AI connection passes the subtitle provider's target; the panel then shows it with a "Back to <assistant service>" action.
For a key target different from the assistant's service the key rows, add and test use the target provider (the controller's
`saveKey`/`testKeys`/`removeKey` get an explicit `provider` argument defaulting to `settings.provider`; no behavior change for
existing callers).

### ChatGPT in the AI Service dropdown (phased)

The dropdown gains a ChatGPT Subscription row beside the existing services (same row layout). The assistant's `AiProvider`
enum is not extended (it drives wire format, base URL, key store and many exhaustive `when`s). A persisted
`ai_assistant_service_access` preference ("chatgpt" or absent) records the ChatGPT choice; while set, the chat composer shows the
"not available for chat yet" state and sends nothing; the stored key service is untouched, so un-choosing restores chat.

### Captions & translation

`ConnectionCard`/`ChatGptConnection` are replaced by `AiConnectionSummary` (status line + Manage AI connection, tags
`ai_connection_summary`, `ai_manage_connection`). `AiTranslationActions.chatGpt` loses sign-in/refresh/disconnect/cancel from the
UI contract it exposes there (state and select/model/test stay for the Test translation action and the model dropdown). The wiring
`onOpenAiSettings` becomes `onOpenAiConnection(AiConnectionTarget)` and opens More settings → AI assistant with that target.

### Available models (Model and Thinking)

Above the unchanged `OutlinedTextField` (`ai_model`) a read-only dropdown **Available models** (`ai_models_picker`):

- Source: key services → `AiAssistantController.loadModels()` (existing `AiModelLister`, `aiModelCanChat`); ChatGPT target →
  the existing authorized catalog (`ChatGptSubscription` model list). No logging of keys or tokens (existing redaction used).
- Items show the readable name, carry the exact id. Choosing an item calls the existing `controller.setModel(provider, id)`, the
  same function the text box uses. The text box value is the only saved value; the dropdown's shown item is *derived*
  (`models.firstOrNull { it.id == typedValue }` else "Custom model").
- States: loading, refresh action, needs connection, empty, failed, and "not in list" for a typed id. A refresh or a late list
  never writes the text box. A failed or unsupported list leaves the text box fully usable.
- An empty or blank typed value is never sent: the request path uses the provider default shown as the placeholder only when the
  field is blank, and the field shows it (existing behavior kept; covered by a test).
- Thinking/Effort chips and hint are untouched.

### Strings

New strings in `strings_ai.xml` (or the file the AI strings already use) and all eight `values-*` folders: Connection title,
summary, Manage AI connection, Back to…, Available models, Custom model, Not in list, model states, chat-not-available state,
connection error lines. `AppLanguageTest` and lint `MissingTranslation` gate this.

## Acceptance criteria

1. Captions & translation contains no sign-in, refresh, disconnect, cancel, key add/test/remove control (test: node absence).
2. Gemini/Claude/OpenAI/OpenRouter/OpenCode/Other selected → API keys body; ChatGPT selected → Connection body; states
   Not connected / Connecting / Connected / Error show the specified controls and tags.
3. Switching service in both directions keeps the ChatGPT session and all keys (state read before/after).
4. A connected user stays connected after upgrade: no session field changes; verified by running the existing session tests
   untouched.
5. Subtitle translation uses the same session/keys with its own provider/model/effort; changing one use case never writes the
   other's preferences (unit test over the preference maps).
6. Manage AI connection opens AI assistant settings on the subtitle provider's connection without changing the assistant service.
7. Available models loads per provider; choosing sets the text box and persists through the existing setter; manual ids survive
   model-list load, refresh, recomposition, reopen and restart; invalid ids fail only at test/request with a visible error and no
   replacement; blank is handled explicitly.
8. Thinking/Effort, the AI Service dropdown rows and the approved screens render as before (existing UI tests unchanged and green).
9. Chat with ChatGPT chosen sends nothing and shows the not-available state; no keyed provider is activated by it.
10. Strings exist in all 8 languages; Light theme and 320dp checked in the saved UI evidence; no color literals added.
11. Standard checks run: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`;
    complexity failures compared with a clean baseline run on `f5c22bd`.

## Preference migration

None for credentials. One new optional key `ai_assistant_service_access`. Existing `ai_access_method`,
`ai_translation_provider`, `ai_translation_model_<id>`, `ai_translation_effort_<id>` and the assistant's model map are read as today.
Missing new key = current behavior. Rollback leaves one unused key.

## Implementation plan (ordered, each step ends green)

1. Baseline: run `testDebugUnitTest` and `complexityCheck` on HEAD and record failures.
2. `AiConnection.kt` + `AiConnectionTest` (state derivation, access method, unsupported/no-login, never "connected" from selection).
3. Controller: explicit `provider` argument on key save/test/remove; tests that the default path is unchanged.
4. `AiChatGptConnection` composable moved out of translation settings; Connection card switching in
   `AiAssistantSettingsSection`; wire `ChatGptActions` into the assistant settings host.
5. `ai_assistant_service_access` preference, ChatGPT row in `AiServiceMenu`, chat-not-available state, test it sends nothing.
6. Replace `ConnectionCard` with `AiConnectionSummary`; `onOpenAiConnection` navigation with `connectionTarget`.
7. Available models picker above the model text box; list sources; derived selection; tests for manual-id survival.
8. Strings in 8 languages; update `AiTranslationSettingsUiTest`, add `AiConnectionPanelUiTest` and `AiModelPickerUiTest`.
9. Run the standard checks; build the debug APK; screenshots from `saveUiEvidence`; superpowers code review; fill the record below.

## Validation scenarios

Covers the owner's 34 scenarios: connection switching (1–9), centralization (10–15), model picker (16–26), regression (27–34).
Unit-testable ones are plain JUnit; UI ones are Compose tests with fake network responses. Anything needing a phone or the live
ChatGPT sign-in is reported as unverified, as is any Compose test that was only compiled.

## Implementation record

Implemented across the three revisions above, on branch `feat_ai_chatgpt_subscription` (start `f5c22bd`).

**What changed.** `assistant/AiConnection.kt` (connection states, targets), `ui/AiConnectionPanel.kt` (API keys / ChatGPT Connection body),
`ui/AiModelPicker.kt` (Available models), per-service `thinkings` and `unifiedModels` in `AiAssistantSettings`, the migration rules
in `AiTranslationChoice.kt`, `AiTranslationConfig` reading the central model and thinking, Captions & translation reduced to the
Translation AI Service row, status and Test translation, the chat gear (`chatOnly`) with Chat AI Service, the unavailable card for
ChatGPT chat, the **AI Connection** title, strings in 8 languages, the assistant guide, `AGENTS.md`, `myCHANGELOG.md`.

**Deviations.** (1) The four Thinking chips cannot set Claude's extra levels (xhigh, max) any more; those saved levels keep working
until a chip is used for that service. (2) The Chat AI Service row also stays in Settings → AI Connection (it edits the same
assignment as the gear). (3) A key saved in AI Connection never changes the chat service, so choosing a service there and pasting
a key no longer switches chat; the setup card in the chat panel still does. (4) `AiTranslationConfig.selectModel` / `selectEffort`
remain for tests and legacy values but no screen calls them. (5) A model replaced because the service stopped listing it is no longer
swapped for an explicitly set model. (6) ChatGPT Subscription has no effort control: OpenAI's preview does not document `reasoning`.

**Checks.**
- Passed: `testDebugUnitTest` (1,280 tests) and `compileDebugAndroidTestKotlin`, `assembleDebug`, `assembleDebugAndroidTest`.
- Passed on the API 36 managed device: `AiConnectionPanelUiTest` (13 of 14 before its last fix; the fix was not re-run).
- Full `ui` package on the managed device, run before the chat-gear change: 189 tests, 17 failed. Four were this change's old
  assertions (fixed, not re-run); the rest are in subtitle, re-translate, practice popup and CPU-budget tests. **They were not compared
  with a clean baseline**, so whether they predate this work is unverified.
- `complexityCheck` fails on the clean HEAD already (`DualSubExperience` in `DualSubApp.kt`); not made worse by this change as far as
  the report shows, but not re-run after the last edits.
- **Not run to completion:** `lintDebug` (stopped), `formatCheck` (needs a `python` command this Mac lacks; ktlint reported findings
  that were not compared with the baseline file).
- Not verified: any run on a physical phone, the live ChatGPT sign-in, real Gemini/Claude keys and model lists, the Light theme and
  320dp screenshots for the new screens, TalkBack.
