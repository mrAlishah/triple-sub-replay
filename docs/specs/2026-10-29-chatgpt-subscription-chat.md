# ChatGPT Subscription for the AI assistant chat

## Status

Owner request 2026-10-29. Builds on [ChatGPT Subscription translation](2026-10-29-chatgpt-subscription-translation.md) (OAuth, session,
Responses transport) and [central AI connections](2026-10-29-central-ai-connections-and-model-picker.md) (Chat AI Service, the
"not available for chat yet" state this removes). Implementation record at the end.

Start state: branch `feat_ai_chatgpt_subscription`, HEAD `828d387`, `git status --short` empty.

## Sources read (OpenAI, 2026-10-29)

`siwc/token-sharing-open-source/` pages *models-and-inference*, *preview-limitations*, *errors-and-recovery*, *token-reference*.
What they fix, and what they leave open:

- `POST https://api.openai.com/v1/responses`, `Authorization: Bearer <access token>`, `store: false`, `stream: true`, `input` an array
  that carries the context of every request. Never `backend-api`.
- Omit: `background`, `conversation`, `max_output_tokens`, `max_tool_calls`, `metadata`, `moderation`, `multi_agent`, `prompt`,
  `prompt_cache_retention`, `safety_identifier`, `temperature`, `top_logprobs`, `top_p`, `truncation`, `user`, `previous_response_id`.
- System text: `instructions` or developer messages; `{type: message, role: system}` items are rejected.
- Function and custom tools: "group in namespaces or supply through `additional_tools` input items". A plain top-level
  `{"type":"function"}` entry is **not stated** to be accepted. `tool_search`, `file_search`, Code Interpreter, native computer use,
  hosted MCP, image generation are unsupported. `web_search` is "subject to model and account/workspace policy" (not stated as supported).
- Inputs: text, images and files when the selected model accepts them; audio, video, the Files upload API and transcription are not supported.
- Reasoning, effort, `include`, `parallel_tool_calls`, `tool_choice`, `text.format`: **not stated**.
- Errors: use `error.code` / `error.param`; before a stream opens the body may be `{"detail": "..."}`; 401 identity/permission, 403 policy,
  503 direct routing unavailable (retry with bounded backoff). OpenAI never moves a request to another billing path.

## Design

One new wire format and one new transport; the chat engine, tools, permissions, memory, history and UI stay.

- `AiWireFormat.CHATGPT_RESPONSES`. `ChatGptChatTransport` (an `AiChatTransport`) turns an `AiChatRequest` into a Responses body
  (`assistant/AiChatGptWire.kt`, pure) and the SSE stream into an `AiReply`; `AiTransportRouter` sends keyed formats to
  `AiHttpTransport` and this one to `ChatGptChatTransport`. Nothing keyed can ever receive a ChatGPT request or the other way round.
- Authentication is **not duplicated**: `ChatGptChatBackend` (in `chatgpt/`, built by the one `ChatGptSubscription`) uses the same
  `ChatGptSession` (one refresh lock), `ChatGptCredentialStore` and `ChatGptGate` as translation. A 401 renews the token once, a
  passing failure before any text retries (1 s, 3 s), a blocking failure is recorded in the shared gate so chat and translation stop
  asking together (usage limit 5 min; ineligible / sign-in until Refresh). The retry loop is shared with `ChatGptTranslationModel`.
- Request body: `model`, `instructions` (the system messages), `input` items, optional `tools`, `store:false`, `stream:true`. No
  `reasoning` (not verified), no other fields. Input items: user → `{role:user, content:[input_text, input_image/input_file…]}`; assistant
  text → `{role:assistant, content: text}`; an assistant tool call → a minimal `function_call` item (`call_id`, `name`, `arguments`,
  `namespace`); a tool result → `function_call_output` (`call_id`, `output`). Items are replayed without server ids because nothing is stored.
- Tools: the app's actions go in one `namespace` tool (`name: "triplesub"`) holding `function` tools. **Unverified against the live
  service.** If the service refuses a request that carried tools (400), the transport reports `UNSUPPORTED_TOOLS`; the existing
  `AiAnswerRun` then asks again without tools (first round), so plain chat keeps working and the choice is remembered per setup.
- Web search: the service's own search is **not sent** (not stated as supported); with Search mode "Service" ChatGPT shows it as
  unavailable. Tavily (the app runs the search as a local tool) works as for any service. Never substituted by another provider.
- Images and PDFs from attachments and the screen tool go as `input_image` / `input_file` (data URLs); a refusal becomes
  `UNSUPPORTED_ATTACHMENT`, handled by the existing code. Audio, video and the Files API are never used.
- Model: the Model box of ChatGPT Subscription in AI Connection (`chatgpt_model`, shared with translation by the central design).
  Effort: none (Automatic); the chat bar's model and thinking controls are hidden while ChatGPT is the chat service.
- Streaming: `AiAssistantUiState.draft` holds the text of the reply so far; the panel shows it as the assistant bubble in place of the
  thinking row. It is cleared on completion, failure or Stop. Only a `response.completed` reply becomes a stored message; an
  incomplete, failed or cancelled stream stores nothing.
- Readiness: ChatGPT is ready when the session is READY and a model is set. Otherwise the panel shows an actionable card (connect in
  Settings → AI Connection, or the real status: not eligible, limit, sign-in needed) instead of the old "not available yet" text.
  No fallback to another provider, ever.
- Independence: chat reads `Chat AI Service` (`chatGptService`), translation reads its own assignment; neither writes the other.

## Capability matrix

| Capability | ChatGPT Subscription chat | Notes |
|---|---|---|
| Text, multi-turn | Yes | Bounded local history (`buildAiRequestMessages`), `store:false`, replayed in `input` |
| Streaming | Yes | `response.output_text.delta`; Stop cancels the call |
| App actions (tools) | Attempted, **unverified** | namespace-grouped function tools; falls back to text-only on refusal |
| Permissions / confirmation | Unchanged | Same `AiAnswerRun`, `aiActionAsks`; provider gains no authority |
| Memory, instructions, subtitle context | Yes | Same prompt builders |
| YouTube search | Yes | Local tool, no provider involved |
| Web search (service) | No | Not stated as supported; shown unavailable |
| Web search (Tavily) | Yes | Local tool with the user's Tavily key |
| Screen / pictures | Yes if the model accepts | `input_image`; refusal → existing message |
| Files (PDF) | Yes if the model accepts | `input_file` with data URL; text files are inline text |
| Reasoning effort | No | Not verified; Automatic |
| Files API, audio, video, Code Interpreter, file search | No | Unsupported by the preview |

## Acceptance criteria

1. Chat AI Service = ChatGPT Subscription routes chat only through `ChatGptChatTransport`; Gemini and the other keyed services still use the keyed transport (test).
2. No OpenAI/Gemini key is read or required; a disconnected, ineligible or limited account shows its real state and sends nothing (test).
3. Every body has `store:false`, `stream:true`, `model`, `instructions`/`input`, and none of the forbidden fields or `previous_response_id` (test).
4. SSE: text deltas arrive in order without duplicates; `response.completed` finishes; failed / incomplete / cut streams throw and store nothing (test).
5. Multi-turn: follow-ups carry the earlier turns; a new chat carries none; retention and delete clear context (test on the input builder and controller).
6. Tool calls are parsed, run through `AiAnswerRun` with the existing permission rules, answered with `function_call_output` of the same `call_id` (test).
7. A refused tools request retries without tools once and text chat works (test).
8. Shared session: one renewal on 401, a blocked gate stops chat and translation alike, concurrent use calls the refresh lock once (test).
9. Stop cancels the HTTP call; the draft is cleared (test).
10. Changing the chat service never writes the translation assignment and vice versa (test).
11. The old unavailable text and `ai_service_chat_unsupported` note are gone; new strings exist in 8 languages.

## Implementation plan

1. Tests first: `ChatGptChatWireTest` (body, items, tools, events), `ChatGptChatBackendTest` (renew, gate, retry, cancel), controller tests with a fake ChatGPT transport.
2. `chatgpt/ChatGptResponses.kt`: `ChatGptResult`, `readChatGptResponse`; `ChatGptApi.respond` (default over `stream`); `ChatGptHttpApi.respond`.
3. `chatgpt/ChatGptChatBackend.kt` + shared call loop; `ChatGptTranslationModel` uses it; `ChatGptSubscription.chatBackend()`.
4. `assistant/AiChatGptWire.kt`, `ChatGptChatTransport`, `AiTransportRouter`, error mapping to `AiErrorKind` (three new kinds).
5. Controller: `chatGptReady`, route in `request`, `draft`, web-search route, hide bar; panel: draft bubble and state card; ViewModel attach.
6. Strings (8 languages), guide, AGENTS.md, myCHANGELOG.md; remove the old unavailable text.
7. Run unit tests, compile instrumented tests, lint, format, assemble; managed-device run; review.

## Validation scenarios

Unit tests with fake OAuth and Responses transports for criteria 1 to 10; Compose tests for the draft bubble, the state card and the hidden bar.
The live scenario (sign in, stream, multi-turn, subtitle question, switch to Gemini and back, translation unchanged) needs the owner's
phone and a real ChatGPT plan: **UNVERIFIED** until then, as is the namespace tool shape.

## Implementation record

Implemented as designed in commit `c48676f`; later fixes in the following commit. Beyond the plan: a chat request the service refuses is
not remembered in the shared gate (so the retry without tools works and translation is not paused); the account is loaded when ChatGPT is
chosen for chat and at start-up when it already was; the Chat AI Service row is only in the chat's gear.

**Verified (JVM unit tests, passed before the last small edits):** request body rules, input items, tools namespace, SSE parsing, shared
session / gate / retry / cancel behavior, controller routing, draft and Stop, error mapping, router isolation.
**Not verified:** any run against the live ChatGPT service (streaming, follow-ups, tool calls and their namespace shape, image / PDF
input, model behavior); lint after the last two fixes; `formatCheck`; any device or UI run of the final code. Tool calls across turns are
not replayed (only the text of earlier turns), by design.
