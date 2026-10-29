# Translate subtitles with the user's ChatGPT subscription

## Status

In progress (2026-10-29). Owner request in the `feat_ai_chatgpt_subscription` worktree: an
optional "ChatGPT Subscription" way to run the AI subtitle translation, for **both** Subtitle 1 and
Subtitle 2, translated directly from the Original Subtitle. The owner approved this spec, the
proof of concept, the implementation and local validation, and local commits. Push, PR, merge and
release are **not** authorised.

Live subscription access is **UNVERIFIED** until Phase 2's gate passes on a device with an eligible
ChatGPT account. Nothing below may be reported as working before that. As of the implementation record at the
end of this file, the code is complete and mock-tested, and the live gate has **not** been run.

## Context / problem

AI translation ([spec](2026-10-28-ai-subtitle-translation.md)) needs an API key the user pays for.
Many users already pay for ChatGPT. OpenAI's *Sign in with ChatGPT → ChatGPT plan usage* preview
lets an open-source, locally run app call the Responses API with the user's own plan allowance and no
API key.

Verified against this worktree (2026-10-29):

- `AiTranslationModel` (`translation/AiSubtitleTranslator.kt:48`) is the seam: `scope` + `ask(system, user, onText)`.
  `KeyedAiTranslationModel` (`assistant/AiTranslationAccess.kt:156`) is the API-key implementation.
- `AiSubtitleTranslator` batches sentences, maps rows from JSON, streams partial rows and caches by
  `pair(model.scope, prompt version, source) → target`.
- Subtitle 1 uses `followGoogleTranslation` → `AiWindowTranslation` (`ui/AppViewModel.kt:1967`).
- **Subtitle 2 does not use the AI.** `startThirdLine` → `translateThird` (`ui/AppViewModel.kt:1893/1913`)
  goes Google, then on-device. Wiring the AI into it is new work.
- The model is chosen once per run: `Context.aiTranslationModel(chosenModel, transport)`, null without a key.

## Goals

1. Settings → Translation gains **AI access: AI service and keys (default) / ChatGPT Subscription**.
2. Sign in with ChatGPT through OpenAI's official flow, authorise plan usage, pick a model from the
   account's own catalog, disconnect.
3. With ChatGPT Subscription selected, Subtitle 1 **and** Subtitle 2 are translated by the AI directly
   from the Original, each with its own language, cache and lifecycle.
4. No behavior change for anyone who does not enable it (off by default, never forced).

## Non-goals / anti-goals

- No automation of the ChatGPT website, cookies, `backend-api`, session-token extraction, shared accounts,
  limit evasion, or any undocumented endpoint.
- No project server, shared key or client secret. No fallback to a chargeable API key unless the user chooses it.
- The AI assistant cannot connect, read or change the ChatGPT access (like keys and the other AI settings).
- No Claude consumer-subscription sign-in (no documented flow; separate future feature). API-key Claude is untouched.
- No change to phrase/word/sentence selection (`nextPhraseRange` etc.), Google Translate hand-off, POS, Anki, player.
- No rewrite of `AiSubtitleTranslator`'s batching, JSON row mapping or caching.

## Owner-approved exception

Mission/tech-stack already allow two optional, off-by-default exceptions to "no account, no key":
Google Translate and the user-keyed AI assistant. This is a **third**: an optional OpenAI account
connection that the user starts, that sends only subtitle text to OpenAI, and that is off until they
connect. Approved by the owner on 2026-10-29 in this thread; `docs/project/mission.md` and
`tech-stack.md` record it in this change.

## Official contract (verified 2026-10-29 from developers.openai.com/siwc)

| Item | Value |
| --- | --- |
| Audience | "open-source and locally hosted apps"; paid or remote apps use the interest form |
| Authorize | `https://auth.openai.com/api/accounts/authorize` |
| Token / refresh | `https://auth.openai.com/api/accounts/oauth/token` (form POST) |
| Revocation | `https://auth.openai.com/api/accounts/oauth/revoke` (from OIDC discovery) |
| Discovery / JWKS / issuer | `.well-known/openid-configuration`; `https://auth.openai.com/.well-known/jwks.json`; `https://auth.openai.com` ; RS256 |
| First sign-in | `client_id=dynamic_agent_client` + `ext_agent_host_id` + `agent_name_hint` (first registration only); callback returns issued `oaiapp_…` `client_id` which is saved and used for exchange, refresh and later sign-ins |
| Scopes | `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`; `resource=https://api.openai.com/v1` |
| Flow | code + PKCE S256 + `state` + `nonce`, system browser |
| Redirect | `http://127.0.0.1:<port>/auth/callback`; only the port may vary; not `localhost`; no custom schemes documented; identical URI at authorize and exchange |
| Exchange | `grant_type=authorization_code`, `client_id`, `code`, `code_verifier`, `redirect_uri`, `resource` |
| Refresh | `grant_type=refresh_token`, issued `client_id`, `refresh_token`, `resource`; no `scope`; rotating; serialise refreshes |
| Lifetimes | access token 1 h, refresh token 30 days (renewed on each refresh) |
| Plan usage granted? | decided from the **token response** `scope`, requires `chatgpt.tokens.use.direct` |
| Models | `GET https://api.openai.com/v1/models` with the access token → `models[]` with `slug`, `display_name`, `visibility`; show `visibility == "list"` |
| Inference | `POST https://api.openai.com/v1/responses`, `store:false`, `stream:true`, `input` array, `instructions` or developer message (system-role items rejected) |
| Unsupported fields | `background conversation max_output_tokens max_tool_calls metadata moderation multi_agent prompt prompt_cache_retention safety_identifier temperature top_logprobs top_p truncation user`; no `previous_response_id` over HTTP |
| Success | only after `response.completed`; handle `response.failed`, `response.incomplete`, broken stream |
| Errors | 401 re-check account/scopes; 403 policy/region; 503 bounded backoff, keep credentials; `subscription_sharing_user_not_eligible` (403, do not retry or loop OAuth); `…usage_limit_exceeded` (429, pause, link to ChatGPT settings → Usage); `…usage_unavailable`; disconnection is discovered only on request/refresh |
| Branding | button label "Continue with ChatGPT" |

### Blockers and unknowns (not guessed)

- `earliest_refresh_at`: listed as a token-response field; no definition or format is published. It is stored
  as received and **not acted on**; refresh is "near access-token expiry" as documented. Revisit if OpenAI documents it.
- Whether `dynamic_agent_client` registration is open to a mobile app distributed outside "open-source and
  locally hosted" is not stated. The repository is open source and the app runs on the user's device, so it fits
  the documented audience, but **only a live sign-in proves it**. A refusal is a product-eligibility blocker.
- Exact ID-token claims beyond `sub`, `iss`, `aud`, `exp`, `iat`, `nonce`, and the exact refresh error bodies are not
  fully published; code treats unknown claims/errors as opaque and fails closed.
- Android + loopback: the browser must reach `127.0.0.1` on the same device. Risks: browser blocks or warns on
  cleartext loopback (Chrome allows it), the app process is killed while the browser is open (the listener dies),
  Private-DNS/VPN apps that intercept loopback, a second app listening on the port. If it fails on a device, record the
  exact failure; do not substitute a custom scheme or App Link.

## Design

### Security architecture

- Loopback listener (implemented in `ChatGptLoopback.kt`): a plain `ServerSocket` bound to `InetAddress` `127.0.0.1` only, port 0 (OS-chosen), started before the
  browser opens. It accepts one request, only `GET /auth/callback`, replies with a small static page, then closes.
  Timeout ~3 min; closed on success, cancel, failure and app teardown. A raw socket is outside
  `network_security_config`, so **global cleartext stays off**.
- Per attempt: random `state`, `nonce`, PKCE verifier (`SecureRandom`), kept in memory only. `state` is compared in
  constant time before the code is used. `error=access_denied` stops the attempt.
- ID token: RS256 signature against the JWKS (fetched over HTTPS at each sign-in; not cached), `iss`, `aud == issued client_id`,
  `exp`, `iat`, `nonce`. `sub` + issued `client_id` identify the account. Email is display only, after validation.
- Granted plan usage = `chatgpt.tokens.use.direct` in the token response scope; otherwise the state is
  "Connected without plan usage".
- Storage: a new `ChatGptCredentialStore` reuses `SharedPreferencesSecretStorage` + `KeystoreSecretCipher`
  (AES-GCM, Android Keystore) in a new preferences file `chatgpt_subscription` that is added to
  `backup_rules`/`data_extraction_rules` exclusions (`BackupRulesTest`). Record = issued client id, `sub`, host id,
  tokens, expiry, scopes, `earliest_refresh_at` raw. One record is replaced atomically (single `apply` of one sealed blob).
- `ext_agent_host_id`: random `urn:uuid:…`, created once, stored with the credential file; opaque, not tied to the user.
- Refresh: one `Mutex` single-flight; re-reads the stored record inside the lock (another caller may have refreshed),
  replaces access token, expiry, scopes and refresh token together, and validates the account is unchanged. Network
  failure keeps credentials; `invalid_grant`/401 on refresh marks "sign-in required" and stops using them.
- Disconnect: call revocation (best effort), then delete the local record and the model choice, and cancel in-flight work.
- Never logged or stored elsewhere: tokens, callback URLs, subtitle text. No credentials in `BuildConfig`, cache keys,
  screenshots, analytics.

### Translation-provider architecture

- `ChatGptSubscriptionAuthRepository` (state machine + refresh + disconnect), `ChatGptOAuth` (pure helpers: PKCE, URLs,
  callback parse, ID-token validation), `ChatGptLoopbackServer`, `ChatGptResponsesClient` (models + SSE), and
  `ChatGptSubscriptionTranslationModel : AiTranslationModel` in `translation/` or `assistant/` next to `AiTranslationAccess`.
- `ask(system, user, onText)` → `POST /responses` with `instructions = system`, `input = [{role:user, content:user}]`,
  `store:false`, `stream:true`, chosen `model`. `response.output_text.delta` → `onText`; return the text only on
  `response.completed`; `response.failed|incomplete`, HTTP errors, broken streams → `IOException` subclass carrying a kind
  (ineligible, usage limit, sign-in required, unsupported model, transient) so callers choose the fallback.
- Retry policy: a transient failure (503/network) is retried with bounded backoff 1 s, 3 s, only before any text arrived. A 401 renews
  the access token once and repeats the request. No retry for ineligible, limit, 400. `AiKeyPacer`'s free-Gemini constants are not used.
  A usage limit rests ChatGPT requests for 5 minutes, a refused request for 1 minute, ineligible and ended sign-ins until Refresh or reconnect;
  the blocking reason is shown in Settings.
- `scope = "chatgpt/<model>/<account hash>"`: the account hash is a truncated SHA-256 of `sub:issued client_id`
  (non-reversible), so cache entries, `attemptKey`s and files never contain the plaintext identity and an account switch
  cannot read another account's cache. Language pair and prompt version already come from `pair()`.
- `aiTranslationModel(...)` gains the access choice; `ai_access_method` preference (default `keys`). Selecting ChatGPT with
  no valid session returns null → existing fallback; it **never** falls back to a keyed provider.

### Subtitle 1 and Subtitle 2

- Subtitle 1 keeps `followGoogleTranslation` + `AiWindowTranslation`; only the model source changes.
- Subtitle 2 (`startThirdLine`/`translateThird`): when ChatGPT Subscription is the AI access and the model exists, a second
  `AiWindowTranslation` (own `languages = source → third`) feeds `runThirdLanguage` with the same Google-first/AI-upgrade and
  AI-only rules as Subtitle 1, reusing `AiSubtitleTranslator` (its cache is keyed by target, so slots cannot mix). The source is
  always the Original (`sourceLanguage`), never Subtitle 1. **With the API-key access method Subtitle 2 stays Google, then
  on-device, exactly as before** (decided after review: sending a second copy of the subtitles to a keyed, paid provider would
  change existing users' behavior). Making Subtitle 2 use keyed AI too is a one-line change in `runStoredTranslation` if the owner wants it.
- **Efficiency decision**: two independent requests per window (one per target). One combined "two targets" prompt would change
  `AiSubtitleTranslator`'s JSON row mapping and cache identity and risk row misalignment; accuracy and the existing mapping win.
  Both slots share one model instance (`selectedAiModel()` in `AppViewModel`). `AiSubtitleTranslator.translate` already runs one
  request at a time (`requests.withLock`) and shares one rest-after-failure timer, so the two lines take turns: Subtitle 1 can wait
  behind a Subtitle 2 request, and one failure rests both lines. This is accepted; no extra pacing exists in the ChatGPT model.
  A slot that is hidden or equal to another slot's language runs no request (`thirdLanguageFor` returns null for repeats).
- Slot identity, order, size, visibility, RTL stay keyed by slot (`stableId`); nothing here changes them.

### UI states (Settings → Translation, below "Translate subtitles with")

Visible when an AI choice is selected: **AI access** (two radio rows). With ChatGPT Subscription: status line, account
(validated email or "Connected account"), model picker (catalog `visibility == "list"`), usage note ("Your ChatGPT plan's
allowance and limits apply; availability differs by plan"), Refresh status, Disconnect, Test translation.
States: Not connected · Connecting · Connected, plan usage authorised · Connected without plan usage (offers re-consent) ·
Account not eligible · Sign-in required · Usage limit reached (link to ChatGPT settings → Usage) · Temporary failure (retry).
Button label "Continue with ChatGPT". Colors from `MaterialTheme.colorScheme`. All strings in `strings_ai_chatgpt.xml` in the default
and the 7 translated `values-*` folders.

### Failure and fallback behavior

| Situation | Behavior |
| --- | --- |
| Not connected / sign-in required | model null; Google (Google mode) or on-device; Settings shows the state |
| Ineligible, plan usage not granted | stop; no retry/OAuth loop; state shown; Google/on-device as selected |
| Usage limit | pause ChatGPT requests until the user retries or the next load; Google/on-device meanwhile; never a keyed provider |
| 503/network | bounded retry, then the existing fallback for that window; credentials kept |
| Interrupted stream | rows already verified stay (existing partial-row rule); no completed claim; remainder falls back |
| AI only | shows the accurate non-destructive failure (existing `showTranslationUnavailable`), not a paid substitute |

## Acceptance criteria

1. Default install behaves exactly as before (`ai_access_method` absent = keys). Existing AI tests unchanged and green.
2. Pure OAuth helpers pass unit tests: PKCE S256 vector, state/nonce, callback parse/reject (wrong path, state, extra params,
   non-GET, other host), ID-token validation (good, bad signature, issuer, audience, expiry, nonce), scope check.
3. Credentials persist encrypted, survive restart, are excluded from backup, and are deleted by Disconnect.
4. Concurrent refresh performs one network refresh and rotates atomically; revoked refresh → sign-in required.
5. Models request lists only `visibility == "list"`; Responses body contains no unsupported field and no system-role item.
6. SSE: deltas stream; completion only on `response.completed`; failed/incomplete/broken/cancelled surface as errors.
7. German→English and German→Persian: IDs ordered, none missing/duplicated, each text in its own slot, timestamps untouched, RTL for fa.
8. Account switch / model switch / restart do not reuse another scope's cache.
9. Fallbacks in the table above, with no keyed-provider substitution.
10. **Live gate (device-only)**: browser sign-in, callback, verified identity, plan usage authorised, model list, one real
    "Ich lerne jeden Tag Deutsch." → Persian translation, refresh, disconnect. Until then: UNVERIFIED.

## Milestones

1. Spec + owner-exception note (this file, mission/tech-stack, ROADMAP line).
2. PoC: OAuth, storage, models, one translation, test UI in Settings → Translation. Live gate.
3. Subtitle 1 integration.
4. Subtitle 2 integration.
5. Regression tests, strings (8 languages), privacy text, lint/format/complexity, UI evidence, debug APK, review.

## Test strategy

Plain JUnit4 with `internal` top-level functions and hand-written fakes (no mocking library): `ChatGptOAuthTest`,
`ChatGptCallbackTest`, `ChatGptIdTokenTest` (test RSA key, no network), `ChatGptCredentialStoreTest`, `ChatGptRefreshTest`
(fake token endpoint, concurrency), `ChatGptResponsesTest` (request body, SSE streams), `ChatGptSubscriptionTranslationModelTest`,
extended `AiSubtitleTranslatorTest` for the two-slot de→en / de→fa cases, `BackupRulesTest`, `AppLanguageTest`. Compose tests with
`saveUiEvidence` for each UI state on dark and Light. The loopback server is exercised against a real local socket in a unit test.
No real credentials in CI; offline fixtures only.

## Release intent

Changes `app/src/main/**`, so it reaches the APK: **patch** by default; **minor** is a reasonable owner choice for a new
sign-in feature. Not decided by this agent; no label or `Release-Version` is applied. Baseline: latest stable tag `v2.8.5`
(estimate only until reserved).

## Known limitations

Preview API that may change; plan allowance and eligibility differ by account and are not unlimited or free for everyone;
`earliest_refresh_at` unused; Android loopback may fail on some browsers/devices; the listener dies if Android kills the app
during sign-in (the user retries); one connected account at a time; with ChatGPT Subscription Subtitle 2 costs a second request per window and the two lines take turns.

## Implementation record (2026-10-29)

### What changed

- `chatgpt/`: `ChatGptOAuth.kt`, `ChatGptLoopback.kt`, `ChatGptSignIn.kt`, `ChatGptSession.kt` (credential store, refresh, disconnect),
  `ChatGptHttpApi.kt`, `ChatGptResponses.kt`, `ChatGptTranslationModel.kt` (with `ChatGptGate`), `ChatGptSubscription.kt` (UI state holder).
- `ui/AppViewModel.kt`: one `selectedAiModel()` for both lines; `followGoogleTranslation` takes the model; `startThirdLine` now runs an
  `AiWindowTranslation` for Subtitle 2; `runThirdLanguage` (`ui/TripleSubtitles.kt`) accepts optional `prefetch` and `upgrades`.
- `ui/ChatGptSubscriptionSettings.kt` plus a `chatGpt` action in `TranslationEngineActions`; the AI access choice and panel show in
  Settings → Translation while an AI translator is chosen. `strings_ai_chatgpt.xml` in all 8 languages.
- Backup rules exclude `chatgpt_subscription.xml`; `ai_access_method` and `chatgpt_translation_model` are in `RESETTABLE_SETTING_KEYS`
  (Reset all settings turns the choice off but does not sign the account out; Disconnect does).
- Docs: this spec, mission exception, tech-stack, AGENTS.md invariant, PRIVACY.md.

### Deviations from the plan above

- The state holder is `ChatGptSubscription`, not a separate "repository"; `ChatGptSession` plays the repository role.
- No JWKS caching; `earliest_refresh_at` stored but unused (as planned).
- After a refresh-token death the account record stays with empty tokens so that signing in again reuses the issued client id.
- Re-consent after declined plan usage sends `prompt=consent` (documented in the preview's errors page).
- The test button sends the one German sentence and shows the result; nothing is stored.

### Checks (run on macOS, Android Studio JDK 25, not the documented JDK 17)

| Check | Result |
| --- | --- |
| `testDebugUnitTest` | passed, 1163 tests (50 new in `chatgpt/`) |
| `formatCheck` | passed (needed a `python` shim on PATH: macOS has only `python3`) |
| `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` | passed |
| `complexityCheck` | **fails on clean HEAD too** (`DualSubApp.kt` `DualSubExperience` 25/25); not caused by this change |
| `ChatGptSubscriptionUiTest` (instrumented) | compiled and packaged only; **not executed** (no emulator or device) |
| Loopback listener | executed on a real local socket in `ChatGptSignInTest` |
| Live sign-in, plan-usage grant, model list, real translation, real refresh, Disconnect/revoke | **UNVERIFIED** (no device, no ChatGPT account here) |

### Review (superpowers code review, read-only subagent)

Verdict was "not ready" with no critical findings. Fixed before commit: crash paths (Keystore, listener bind, `SecurityException`) now end in a
failed sign-in or a Google fallback; the refresh request and its save survive caller cancellation; a refresh reply without `scope` keeps the
grant; Subtitle 2 uses the AI only with ChatGPT; a 401 renews once; a refused request no longer blocks every window; the panel shows the real
account after a restart and shows blocking reasons; the sign-in save takes the refresh lock; the loopback reads have a total deadline and a
larger backlog; the unused semaphore was removed. Not changed: a request line over 8 KiB is treated as a stray request (the real callback is
short); `loadVideo` blanks the rows on an account or model change, as `setSubtitleTranslator` does.

### Device follow-up (owner)

1. Install the debug APK, open Settings → Translation, choose an AI translator, pick ChatGPT Subscription, tap Continue with ChatGPT.
2. Finish the sign-in in the browser and return to the app: the status should read connected with plan usage allowed, and models should list.
3. Tap Test translation: the result for "Ich lerne jeden Tag Deutsch." should be Persian.
4. With Original German, Subtitle 1 English and Subtitle 2 Persian on a video, both lines should arrive from the AI.
5. Report the exact browser behavior if the loopback page does not load, and any registration refusal as an eligibility result.
