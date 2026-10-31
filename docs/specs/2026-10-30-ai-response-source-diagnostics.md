# Actual AI response source for the assistant chat

## Status

Owner request 2026-10-30. Chat-side counterpart of the subtitle `Show translation source (debug)`
([translation source diagnostics](2026-10-29-translation-source-diagnostics.md)). Builds on
[ChatGPT Subscription for the AI assistant chat](2026-10-29-chatgpt-subscription-chat.md). Implementation record at the end.

Start state: branch `feat_ai_chatgpt_subscription`, HEAD `851a6fa`, `git status --short` empty. Not committed without the owner's approval.

## Problem

The Chat AI Service setting says which provider *should* answer. Nothing shows which provider *did*, and with ChatGPT Subscription (OAuth,
no key) the owner cannot tell it from a keyed service. The answer must come from how the request actually ran, not from the setting.

## Design

### Provenance model (`assistant/AiResponseSource.kt`)

- `AiExecution`: what the transport that ran a request says: `providerId` (`AiProvider.key` or `chatgpt`), `auth` (`OAUTH` | `API_KEY`),
  `api` (`OPENAI_CHAT` | `ANTHROPIC_MESSAGES` | `CHATGPT_RESPONSES`), `requestedModel`, `planUsageVerified` (ChatGPT only).
- `AiResponseSource` (stored with an assistant message): the execution facts plus `reportedModel` (the model the server named, else null),
  `status` (`COMPLETED` | `FAILED` | `CANCELLED` | `INCOMPLETE`), `selectedProviderId` (the Chat AI Service when the question was sent),
  `requests` (how many provider requests produced the message, for tool rounds). Derived: `fallback = actual != selected`, `network =
  a request actually left the phone`. No field can hold a key, token, header or request body.

### Detection

- The transport that executes a request stamps its own facts on the reply (`AiReply.execution`) and on a failure
  (`AiChatException.execution`):
  - `AiTransportRouter` stamps keyed requests (`API_KEY`, the api family from the wire format, the provider id the controller put on
    the request, the model actually sent). It also stops a ChatGPT request from ever reaching a keyed transport.
  - `ChatGptChatTransport` stamps ChatGPT requests itself: `OAUTH`, `CHATGPT_RESPONSES`, the model sent. A failure before the request
    was sent (no session, plan usage not granted, gate blocking) carries **no** execution, so it is shown as "no request was sent".
- The controller never fills these in from the setting. It only records `selectedProviderId` (for the fallback comparison) and the
  status: normal end `COMPLETED`; `AiChatException` `FAILED` (`INCOMPLETE` for an incomplete or unreadable reply); coroutine
  cancellation (Stop) `CANCELLED`.
- Reported model: `AiReply.model` (keyed: the `model` field of the reply; ChatGPT: `response.model` of the `response.completed`
  event). The requested model is never presented as server-reported.

### ChatGPT OAuth evidence

A ChatGPT message is `COMPLETED` only when: the ChatGPT transport was chosen by the format (not the setting), a token came from the central
session (which refuses accounts without the plan-usage scope, so `planUsageVerified = true` means the scope was checked before sending),
the request went to the Responses route, and `response.completed` arrived. The panel says exactly that and nothing about billing or
allowance. A failure, a Stop or an incomplete stream is shown as such, never with a success label.

### Persistence

`AiResponseSource` is stored as one small JSON object per assistant message in the existing chat history (ids, enum keys, model names,
counts). Old messages have none and show "Source not recorded". Nothing is sent anywhere.

### UI and setting

- Setting `Show AI response source (debug)` (`ai_show_response_source`, off by default) in the chat's gear, next to the other chat
  settings. Off: the chat looks exactly as before.
- On: under each assistant message one line (`ChatGPT Subscription · OAuth`, with the status when it is not Completed); tapping opens a
  compact list: actual provider, selected service, authentication, API, requested model, reported model, status, fallback, requests, and
  for ChatGPT the plan-usage line. A failed or stopped question with no message shows the same line with its status under the error.
- No automatic provider fallback is added; `fallback` reports a difference if one ever exists.

## Acceptance criteria

1. ChatGPT chat: source ChatGPT Subscription, OAuth, Responses API, requested and reported model, plan-usage verified, Completed.
2. Gemini chat: Google Gemini API, API key, OpenAI-compatible chat API, Completed.
3. After switching the Chat AI Service, new messages carry the new provider and old ones keep theirs (also after a history reload).
4. A failed request has no Completed source; a Stop is Cancelled; an incomplete reply is Incomplete; a ChatGPT failure before sending says no request was sent.
5. `fallback` is computed from actual vs selected, not assumed.
6. No key, token or header appears in the stored JSON or the displayed facts.
7. A message without stored provenance shows "Source not recorded".
8. Subtitle translation, other chat features and the Settings layouts are unchanged.

## Validation

JVM unit tests only (no Python, no device test): stamping by both transports, controller statuses, history round trip, secrets, legacy
messages, facts text. The owner checks the screens on the phone.

## Implementation record

Implemented as designed. Files: new `assistant/AiResponseSource.kt` (model, stored form) and `ui/AiResponseSourceUi.kt` (line and details);
changed `AiChatClient.kt` (`AiChatRequest.providerId`, `AiChatException.execution`), `AiTools.kt` (`AiReply.execution`), `AiChatGptWire.kt`
(`ChatGptChatTransport` and `AiTransportRouter` stamp their executions), `ChatGptResponses.kt` / `ChatGptChatBackend.kt` (the model the server named,
`respondTracked` tells when a request is sent), `AiAssistantController.kt` (run trace, sources, `lastAttempt`, the setting), `AiConversation.kt` and
`AiChatHistory.kt` (`AiChatMessage.source`), the chat parts, the panel, the gear switch and `strings_ai_connection.xml` in 8 languages.

Details that matter: the ChatGPT transport marks a request as sent only after the session handed over a token (so the plan-usage permission was
checked); a failure before that has no execution and reads "No request was sent". A keyed bad address is also not stamped. The reply's model is what the
server named or empty, never the requested model. INCOMPLETE is used for `BAD_REPLY` (an `incomplete` event or an unreadable reply); a cut stream
(`UNAVAILABLE`) is FAILED. A message made only of actions after a failure still carries its FAILED/CANCELLED source. `fallback` is computed and is
always false today because nothing falls back.

**Checks run (Gradle/JVM only; no Python tests, no device or UI tests):**
- `testDebugUnitTest`: passed (new: `AiResponseSourceTest` 13, controller provenance tests, updated wire test).
- `lintDebug`: passed (this also clears the two lint errors left in the previous commit).
- `assembleDebug`, `assembleDebugAndroidTest`: passed.
- `complexityCheck`: fails only on the issue that already fails on a clean HEAD (`DualSubExperience`).
- `formatCheck`: not run (the project task calls Python).

**Not verified:** every screen (the indicator, the details, the gear switch) and any real ChatGPT or Gemini request; the owner checks these on the phone.
Known limits: the indicator is not shown while an answer streams (only on a finished message, or under the error); the assistant cannot change this
setting; messages saved before this show "Source not recorded".
