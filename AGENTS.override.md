# AGENTS.override.md (TripleSub Replay, Codex core)

This file is generated from the maintained `CLAUDE.md` core by `tools/claude_instructions.py`; do not edit it. `AGENTS.md` remains authoritative and wins on conflict. The existing drift check validates the core and generated rule files against it. Read the applicable full rules below before dependent work. If the applicable rule is unclear, read the relevant part of `AGENTS.md`. After upstream changes, reconcile the map, digests and generated files with `python3 tools/claude_instructions.py sync` and review unresolved changes.

## Routing: Read the rule file before you work in an area

**Before reading or inspecting a relevant source file, including through Serena or any other MCP, and before proposing or making a change, read each applicable rule file below.** Codex does not automatically load Claude path-scoped rules. For cross-area tasks read every applicable rule. These paths are examples, not an exhaustive classifier; use the rule based on the behavior affected. If uncertain, read the relevant section of `AGENTS.md`. The digests below summarize; the rule files contain the full instructions.

| Area (typical paths) | Read first |
| --- | --- |
| WebView, tabs, navigation, screen-off playback (`playback/`, `ui/YouTube*`, `ui/BrowserTabs.kt`) | `.claude/rules/webview-playback.md` |
| Captions and translation (`data/*Caption*`, `translation/`) | `.claude/rules/captions-translation.md` |
| AI assistant, ChatGPT Subscription (`assistant/`, `chatgpt/`, `ui/Ai*`) | `.claude/rules/ai-assistant.md` |
| Learner help, grammar, kanji, hanzi (`learner-data/`, `data/*Grammar*`) | `.claude/rules/learner-help.md` |
| Study tools, Send to, Anki, known words (`data/*Anki*`, `ui/Dictation*`) | `.claude/rules/study-anki.md` |
| App updates, ads and sponsors, remote switches (`update/`, `ui/AdSkipScripts.kt`) | `.claude/rules/updates-ads-remote.md` |
| Strings, theme, dialogs, settings page, backup and sharing (`res/values*`, `ui/theme/`, `ui/Settings*`) | `.claude/rules/ui-settings.md` |
| Writing or changing tests (`app/src/test`, `app/src/androidTest`, `tools/tests`) | `.claude/rules/testing.md` |
| Subtitle slots, order, size, alignment (`ui/SubtitleSlot*`, `ui/TripleSubtitle*`) | `.claude/rules/subtitle-slots.md` |
| Opening, updating, reviewing or handing off a PR; release label or version; CI status; preview link | `.claude/skills/pr-handoff-release/SKILL.md` (read as a file) |

## Personal SoT bootstrap

- Repository: `mrAlishah/myPersonal-SoT`
- Ref: `main`
- Entrypoint: `workspace/adapters/runtime_entrypoint.md`
- Local checkout: `~/Projects/myPersonal-SoT`

Before coding or mutating Git:
1. Resolve the current SoT from local `main` without switching branches.
2. Otherwise use authenticated private GitHub access.
3. Follow the canonical entrypoint and its referenced contracts.
4. Load `workspace/context/personal/policies/development_conventions.md`.
5. Do not use stale or copied policies.

If canonical instructions are unavailable, report and stop dependent mutations.

## Git authorization

Before coding verify the assigned worktree, branch and existing changes. Do not commit, push, merge, rebase, reset, deploy or release without explicit authorization for that action. Do not create or update a pull request, including one targeting upstream, without separate explicit authorization. Approval to implement does not authorize these actions. Preserve unrelated work and fork behavior; resolve upstream conflicts by meaning.

## Project

Android app (`:app`, package `com.kienhoang.dualsubreplay`) with a separate test-only `:benchmark` module: Kotlin, Jetpack Compose / Material 3 and the Android Gradle Plugin (versions in the root `build.gradle.kts`, raised monthly by Dependabot), committed Gradle wrapper (no local Gradle needed), JDK 17, compile SDK 37, target SDK 36, minSdk 26.

## Commands

On macOS or Linux run `./gradlew` where the commands below say `.\gradlew.bat`.

```powershell
.\gradlew.bat formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest   # CI parity
.\gradlew.bat pixel2Api36DebugAndroidTest                                          # instrumented tests on managed device
.\gradlew.bat testDebugUnitTest --tests "com.kienhoang.dualsubreplay.data.SubtitleMergerTest"   # one test class
```

- Managed-device tests require the API 36 AOSP x86_64 system image. On headless hosts add `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` (CI also passes `--no-parallel --max-workers=2`).
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.
- Test workflow and release-tool changes with `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` and validate workflow YAML/shell. These tests also run in Android PR CI.

## Fork maintenance

Before any upstream sync or fork refactor, read [fork-customizations.md](docs/development/fork-customizations.md) (what must be preserved) and [upstream-integration.md](docs/development/upstream-integration.md) (how to decide).

## Development workflow

1. Before significant work, read [mission](docs/project/mission.md), [technical context](docs/project/tech-stack.md), [ROADMAP.md](ROADMAP.md), relevant specs, and relevant source/tests. Stable intent belongs in the repository, not only chat history.
2. Create/update a [feature spec](docs/specs/README.md) with acceptance criteria, a small plan, and validation scenarios **before coding**. New features, significant/risky fixes, architecture, migrations, CI/release changes, and measurable performance work need specs; trivial changes need only a clear PR and acceptance criteria.
3. Record release intent before implementation where possible and resolve it before opening the PR. Existing owner authorization covers the agreed implementation; do not add a redundant approval round or invent approval. Ask about unresolved material scope/product decisions.
4. Implement the smallest coherent solution and validate against the criteria. Record actual implementation, deviations, and passed/failed/unverified checks in the spec. Offline tests do not establish physical-device or live-YouTube success.
5. Update relevant permanent docs in the same PR when an authorized decision changes architecture or conventions. Keep ROADMAP.md directional and each implementation plan inside its feature spec; keep QA evidence in docs/qa/.
6. Open a focused PR against `main` linking the spec (or explaining why none is needed). Open it ready for review, never as a draft: the owner wants to test and merge without extra GitHub steps, and a draft needs an extra click before merging (CI does not re-run on ready for review). The owner remains final merge authority; do not merge, enable auto-merge, or publish releases unless explicitly instructed.

## Branding (fork rule)

- The product users see is **TripleSub Replay**: launcher label, drawer, welcome and help text, notices, share text, and the AI assistant's self-identification (shared `assets/ai/assistant-guide.md`, never a per-provider prompt). Take the name from `R.string.app_name` instead of a literal.
- **DualSub Replay** is the upstream project. Keep it wherever it is true: credits, license and copyright text (`LICENSE`, `THIRD_PARTY_NOTICES.md`, `navigation_license_summary`, `assets/licenses/`), the Original/Forked links, and fork history. The assistant must say truthfully that the app is a fork of DualSub Replay when asked.
- Never rename technical contracts for branding: applicationId, packages, `Theme.DualSubReplay`, the backup format value `"DualSubReplay"`, `ANKI_DECK_NAME`, Anki note types, preference keys, APK/preview asset names, OAuth and deep links. Classify each occurrence; no global search-and-replace.

## Merge conflict priorities (fork rule)

When `main` (or any upstream) is merged into `mydevelop`:

- **`mydevelop` wins** for the Google Translate hand-off (`ui/GoogleTranslateLink.kt`, the Translate/Google Translate actions and bar in `ui/PhraseSelection.kt`, their strings, specs and tests) and for the Settings → Languages UI (the Original / Subtitle 1 / Subtitle 2 accordions and language cards in `ui/SubtitleSettingsDialog.kt`). Keep the `mydevelop` code, drop the other side's version of the same behavior, and remove references the dropped code leaves behind.
- Formatting-only hunks (line wrapping, trailing commas) take the `mydevelop` side so triple-subtitle code keeps its shape.
- Everything else: resolve by meaning, keep non-conflicting upstream changes (CI, release, AI translation, docs), and never accept a whole side blindly.
- Resolve hunk by hunk, never by checking out a whole file, which would drop upstream changes that merged cleanly. Back up or keep the merge reversible until the build passes.
- Before committing the merge: no conflict markers, `testDebugUnitTest` and `assembleDebug` pass, and the discarded side's specs/tests are removed or updated. Note the decision in the relevant spec. Do not commit or push the merge without explicit authorization.

## Default settings

- Defaults are the most stable choice (owner decision 2026-11-11): a new user must be able to use the app without changing any setting and without an AI key. Anything that spends a user's AI key quota is off by default, or runs only on an explicit tap or inside an assistant chat the user started; never turn such a setting on by default, run it in the background, or enable it in a migration. Current examples: AI translation (Google is the default), AI thinking for translation, Meaning in this sentence (AI), web search and Full access are off; AI subtitles for videos without captions, the AI transcript (Gemini) and "Another story with AI" run only on a tap.

## First-launch flow

- **First launch** ([details](docs/project/tech-stack.md#first-launch)): keep the guide migration; never simplify it to `getBoolean("guide_completed", false)`.

## Testing conventions

<!-- digest:TESTING -->
- **Tests** (rule `testing`): plain JUnit4, no Robolectric or mocking library; instrumented tests run offline and never hit live YouTube; check long seeks and ads on an emulator with a real video, because the player holds only a window of transcript lines; a new automatic AI request path goes through `AiKeyPacer` with an `AiRequestBudgetTest` scenario; a failed device run retries its failed tests once (`tools/retry_device_tests.py`), and a "passed on retry" warning is a bug to fix, not noise.

- `formatNewCode -PformatFiles=...` reformats each whole file, including code you did not touch. Revert unrelated hunks so the PR stays focused; `formatCheck` only ratchets per-file counts.

## Hard rules for PRs and agents

<!-- digest:PR -->
- Never merge, enable auto-merge, approve your own PR, close and reopen to re-run CI, push empty commits, or skip, disable or loosen a test to get green. The owner is the final merge authority.
- One focused change per PR, from its own branch, opened ready for review (never a draft).
- Never commit or paste secrets (API keys, tokens, `*.jks`/`*.keystore`, `ANDROID_RELEASE_*` values, `local.properties`, personal YouTube data, caption or video dumps).
- Do not bump the Gradle version constants, create tags or releases, or edit `.github/workflows/**`, `tools/automatic_release.py` or signing setup unless that is the agreed change.
- Release label or `Release-Version:` line, preview-page handoff (never a direct `.apk` link) and final-head CI (`verify-build` and `managed-device-tests` both green) follow `.claude/skills/pr-handoff-release/SKILL.md`: read it before opening, updating or handing off a PR.

## Invariants (digests; the rule file has the verbatim text)

Before changing an area, read its section in the [technical context](docs/project/tech-stack.md#architecture-invariants) and the specs it links. That file holds the current design; specs are dated records and an older one may be superseded. Tests enforce most of it. The rules that must never break:

<!-- digest:WEBVIEW -->
- **WebView and navigation:** exactly one WebView (`SingleYouTubePage`); tabs are saved addresses loaded one at a time; never add a second online player or WebView; a tab of another Google account uses `tabCookieManager`, never `CookieManager.getInstance()`. Main-frame URLs go through `classifyMainFrameUrl`; JS scripts re-verify the executing origin (`https:` + `*.youtube.com`) and `PlaybackArchitectureTest` asserts the literal script text.

<!-- digest:CAPTIONS -->
- **Captions and translation:** caption discovery stays behind `CaptionProvider` (host allowlist, 8 MiB cap); the page token never leaves the page and `webPageTranscriptScript` keeps its one `fetch(`. Translation defaults to Google's unofficial web endpoint or on-device ML Kit; no user- or developer-provided service key is ever required.

<!-- digest:AI -->
- **AI assistant:** acts only through the fixed tool list in `assistant/AiActions.kt`; keys are AES-GCM sealed and, like chats and memory, excluded from backups (`BackupRulesTest`); never add a project server or a shared key.

<!-- digest:CHATGPT -->
- **ChatGPT Subscription:** only OpenAI's official Sign in with ChatGPT plan-usage path; never ChatGPT's website, cookies, `backend-api` or any undocumented endpoint; no project client secret; never fall back from it to a keyed service. Live sign-in is UNVERIFIED on a device: never report it as working (status: `docs/specs/2026-10-29-chatgpt-subscription-translation.md`).

<!-- digest:STRINGS -->
- **Strings:** user-visible text lives in `values/strings_<area>.xml` with the same file in every translated `values-*` folder (`AppLanguageTest`, lint `MissingTranslation`; offered languages are `APP_LANGUAGES`); never a UI literal in Kotlin; take the product name from `R.string.app_name`.

<!-- digest:LEARNER -->
- **Learner help (zh, en, es, de, kanji, hanzi):** bundle data files only, no third-party code; keep the licence credits in `THIRD_PARTY_NOTICES.md` and the licence screen; regenerate data with its `tools/` script, never hand-edit; every grammar point needs its example test and its text in all 9 interface languages; mnemonics are never copied.

<!-- digest:ANKI -->
- **Send to and Anki:** one Send to button (the word card's Anki button, Export's split Send all), never one per app; AnkiConnect is `http://127.0.0.1:8765` and nothing else may be cleartext; known words stay in `files/known-words/`.

<!-- digest:UPDATES -->
- **App updates and remote switches:** downloads only from GitHub hosts, matching GitHub's SHA-256 and newer than the installed version; never add a project server or a store. Remote config carries switches only, never code, scripts, selectors or addresses.

<!-- digest:ADS -->
- **Ads and sponsors:** skip, never block ad requests or edit YouTube's replies; SponsorBlock data keeps its credit in `THIRD_PARTY_NOTICES.md` and the licence screen.

<!-- digest:SETTINGS -->
- **Settings keys:** every key in `dual_sub_preferences` and `ai_assistant` needs a scope and type in `SETTING_SPECS`; anything that uses an API key stays out of SHARE (`BackupSharingTest`).

<!-- digest:UI -->
- **UI:** every screen, dialog and overlay uses `DualSubTheme` and `MaterialTheme.colorScheme` roles, never new hex literals; every dialog gets the X from `DialogTitle` except confirmations the person must answer and TV dialogs; check UI changes on the Light theme and in the saved Compose screenshots.

<!-- digest:SLOTS -->
- **Subtitle slots:** three slots named by role, never by language: Original (`ORIGINAL`), Subtitle 1 (`SECOND`), Subtitle 2 (`THIRD`). Settings stay keyed by the slot (`stableId`); visual order is only `subtitle_order`. Never rename `SavedWord.meaning`, `meanings`, `personalNotes` or the JSON backup keys; changing an Anki field list needs a new note type name (current: `TripleSub Replay v5`).
