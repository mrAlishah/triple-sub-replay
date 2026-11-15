# Smaller always-loaded Claude Code context

## Status

Implemented (2026-11-15), uncommitted, in the `chore_claude_context_optimization` worktree. The owner authorized implementation and local validation only; commit, push, PR and merge are not authorized. Checks that were not run are listed in the Implementation record.

## Context / problem

`CLAUDE.md` is `@AGENTS.md`, so every Claude Code session loads all of `AGENTS.md`: 55,876 bytes, about 20.9k tokens (`/context`, Memory files). 60% of it is "Architecture invariants", needed only when a task touches that area. `AGENTS.md` is upstream-maintained and Codex and other agents read it whole, so it must not change. Personal SoT policy `claude_agents_delegation` (SoT revision 6916573) allows a Claude-specific core for such a fork when the full import costs material context, on conditions this spec follows.

## Goals

- Always-loaded project instructions of 5 to 6k tokens (from 20.9k), with no rule lost: every item of `AGENTS.md` has a verbatim home.
- Area rules load when a task touches the area, including when code is read through Serena or other MCP tools.
- Drift from `AGENTS.md` fails CI automatically, and reconciliation is one command for what can be classified reliably.

## Non-goals

- No change to `AGENTS.md`, app code, `.github/workflows/**`, hooks or settings files.
- No extra skills beyond the PR/release one, and no change to what any rule says.

## User-visible behavior

Infrastructure only. Claude Code sessions in this repository load `CLAUDE.md` (core: bootstrap, commands, workflow, branding, merge priorities, default settings, first-launch flow, testing conventions, hard PR rules, routing table, one-paragraph digests of each invariant area). Eight `.claude/rules/*.md` files (with `paths:` frontmatter) and the skill `.claude/skills/pr-handoff-release/` hold the verbatim text of everything else. Other agents are unaffected.

## Technical constraints / invariants

- `AGENTS.md` stays byte-identical and authoritative; the core says so and says it wins on any conflict.
- Rules without `paths:` frontmatter load at launch (Claude Code memory docs), so every rule has `paths:`; a stray unscoped rule fails the check.
- Path-scoped rules load only when Read, Write or Edit (or `cat`/`head`) touches a matching file; Serena and MCP reads do not, so the core's routing table tells Claude to Read the rule first.
- `CLAUDE.md` stays under 200 lines and 16 KiB and imports nothing (an import loads at launch).
- The SoT locator stays in `CLAUDE.md`; no SoT policy text is copied.
- Release: no APK-affecting path changes (see Release intent).

## Proposed approach / plan

1. `tools/claude_instructions.py` splits `AGENTS.md` into 93 items (bullet, numbered item, paragraph or code block), hashes each after normalizing whitespace, list markers and heading parentheticals, and records the item's destination in `docs/development/claude-instructions-map.json`: `core` (verbatim in `CLAUDE.md`), `rule:<name>` or `skill:<name>` (verbatim in a generated file).
2. `sync` regenerates rule and skill files, pairs edited items with their old versions (same start or end text, or the only change in a section), auto-classifies new items in a section with one destination, and flags a digest whose source item changed for review (`ack` confirms). Anything else stays unclassified and fails.
3. `tools/tests/test_claude_instructions.py` runs the check in the existing CI `unittest discover` step. It fails on: unmapped or removed items, a core item missing from `CLAUDE.md`, a stale or hand-edited generated file, a stray unscoped rule, `CLAUDE.md` over budget or importing a file, a missing SoT locator or routing row, a dead `paths:` glob, a digest awaiting review, an unsupported Markdown construct in `AGENTS.md`, or a missing must-keep identifier.
4. Spec patches: the ChatGPT translation spec's Status block (live sign-in is UNVERIFIED and the code is in `mydevelop`), and a short spec recording the main-frame navigation contract that no document states.

Destinations (item counts and bytes of `AGENTS.md` text): core 41 / 8,272; skill 24 / 7,580; `webview-playback` 3 / 2,028; `captions-translation` 2 / 5,579; `ai-assistant` 2 / 8,611; `learner-help` 6 / 6,220; `study-anki` 1 / 4,802; `updates-ads-remote` 2 / 4,451; `ui-settings` 7 / 4,012; `subtitle-slots` 5 / 3,603.

## Acceptance criteria

- [x] `AGENTS.md` is byte-identical to base commit `939eeb64` (`git diff` empty, same SHA-256).
- [x] Every one of the 93 items has a verbatim home, enforced by the check; 29 must-keep identifiers appear in the core or a rule.
- [x] `CLAUDE.md` is 15,948 bytes and 147 lines (limits 16,384 and 200). Fresh-session `/context all`: project instructions 5.9k tokens, 71.8% below 20.9k.
- [x] With Serena only, the rule is read before the first code call: 7 of 7 trials with the final wording (2 areas), and none read in 2 control trials outside the table. Only 2 of the 8 areas were exercised.
- [x] Five invariant probes are declined with the right rule: 5 runs with built-in tools, each judging all five requests. The Serena-only variant was not run.
- [x] The CI Python command passes (59 tests) and the drift scenarios behave as listed (23 checks over 15 scenarios).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| V1 Unit tests (Python) | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v`: 59 tests pass (54 existing, 5 new) | Python 3.9 and CI Python |
| V2 Drift behavior | Edit `AGENTS.md` in a scratch copy: cosmetic edits pass; content edit fails, then `sync` resolves it; new section, table, stray rule, `@AGENTS.md`, oversize core and hand-edited rule each fail | Scratch copy; 23 checks over 15 scenarios pass in the preview |
| V3 Fresh-session load | `InstructionsLoaded` hook log: a Read of a matching file loads the rule; Serena-only access does not, and the routing table makes Claude Read it first | Claude Code CLI in the branch worktree |
| V4 Invariant probes | The five probes in the acceptance criteria, once with built-in tools and once Serena-only | Same |
| V5 Context measurement | `/context all` in a fresh session, same MCP set, before and after; record Memory files | Same machine and MCP set |
| V6 Codex and Git | `git diff --exit-code 939eeb64 -- AGENTS.md` is empty; a `codex` session lists the full `AGENTS.md` and ignores `.claude/`; `git status` shows only the files named in this spec | Local `codex` CLI |
| Android lint/build, managed device, live YouTube | Not applicable: no Kotlin, resource or Gradle file changes | Existing PR CI still runs |

## Risks / edge cases

- Rules do not autoload through Serena; the routing table is the only mechanism there. A missed load is the main risk. A `PreToolUse` hook on `mcp__serena__*` could inject the rule later; it is deliberately not part of this change.
- Loading the core, every rule and the skill at once is about 25k tokens (*estimate*), about 20% more than today, because digests repeat some text. Typical single-area tasks are about 9k (56% less).
- The core's digests and verbatim core items are hand-maintained; the check fails until a person reconciles them, which makes upstream sync PRs touch `CLAUDE.md` or the map.
- Bulk upstream edits can leave several items needing a person to classify them; this is by design.
- Item pairing and digests rely on the maintainer reviewing the diff the tool prints.
- Codex loads only the first 32,768 bytes of `AGENTS.md` (its default `project_doc_max_bytes`), so it never sees 34 of the 93 items, among them the agent PR rules and the testing conventions. This predates this change and is unaffected by it.

## Release intent

`release:skip`. No changed file is in `app/src/main/**`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` or `gradle/**`; the change touches `CLAUDE.md`, `.claude/**`, `docs/**`, `tools/**` and `myCHANGELOG.md` only. Apply and verify the label on the PR before calling it ready.

## Implementation record

**Changed files.** New: `.claude/rules/*.md` (8), `.claude/skills/pr-handoff-release/SKILL.md`, `docs/development/claude-instructions-map.json`, `tools/claude_instructions.py`, `tools/tests/test_claude_instructions.py`, this spec, `docs/specs/2026-11-15-webview-navigation-invariants.md`. Edited: `CLAUDE.md`, `myCHANGELOG.md`, the Status block of `docs/specs/2026-10-29-chatgpt-subscription-translation.md`. Not changed: `AGENTS.md`, Kotlin, resources, Gradle, workflows, hooks, settings.

**Deviations from the plan.**
- The tool lost its `list` helper and a duplicate computation (no behavior change).
- The core's first paragraph now declares itself a derived core enforced by a CI drift check, so a global exception can name it.
- The routing paragraph was strengthened after one of two Serena-only trials skipped the rule: 7 of 7 followed it afterwards.
- A `./gradlew` note for macOS and Linux was added to Commands, as approved.

**Measured (Claude Code 2.1.325, `claude -p "/context all"` in a fresh session).** Project instructions 20.9k (`AGENTS.md`) plus 16 (`CLAUDE.md`) became 5.9k. Memory files total 21.9k became 6.9k. Whole session at start 37.3k became 22.3k (the skill adds about 120 tokens).

**Passed.** CI Python command (59 tests); drift check; 23 adversarial checks; built-in Read loads exactly the matching rule (`path_glob_match`); Serena-only trials above; five guardrail probes; Codex lists only `AGENTS.md` and does not see `.claude/rules`.

**Not run or unverified.** Gradle and Android tests (no Kotlin, resource or Gradle file changed); rules for the six other areas were not exercised through a live session; Serena-only invariant probes; behavior across a real upstream sync; the effect of the global-file exception on Claude loading. The global Claude file now contains the exception; its effect has not been measured in a fresh session.

## Codex context extension (proposed)

Codex discovers `AGENTS.override.md` before `AGENTS.md` at the repository root. Generate the override from the checked Claude core with Codex-specific routing and explicit Git authorization; `AGENTS.md` remains authoritative and unchanged. Codex must explicitly read the relevant `.claude/rules/` files and the PR skill as a file before dependent work, including Serena or other MCP access. Existing unit mapping and generated rules remain shared. `check` fails for missing or stale Codex output, oversized output, or ambiguous generation anchors; `sync` regenerates it. A changed source digest still needs human review.

Acceptance: the generator check and Python tests pass; every mapped core item appears in the generated core and every scoped destination is routed; ambiguous anchors and modified output fail; `AGENTS.md` remains unchanged; fresh Codex sessions demonstrate discovery and area, cross-area and Serena-first rule reads; fresh Claude sessions show unchanged instruction behavior. Record observed token counts and unverified scenarios separately. No live behavior or token saving is asserted until measured. The change is non-APK and qualifies for `release:skip` if a PR is later authorized.

## Reconciliation with the merged `mydevelop` (2026-11-15)

The upstream sync moved the per-area invariants out of `AGENTS.md` into `docs/project/tech-stack.md#architecture-invariants` and left one digest line per area under "Architecture rules" (`AGENTS.md` fell from 55,876 to 28,841 bytes). Rules:

- The detail now lives in `tech-stack.md`, so each generated rule embeds, verbatim, the `tech-stack.md` sections for its area (`"docs"` in the map's targets) after the `AGENTS.md` digest units. A matching Read still loads the full text; there is one generator and one map.
- `check` fails when an embedded heading is missing or renamed, when an `AGENTS.md` link `tech-stack.md#anchor` points at no heading, or when a `must_keep` literal is in neither `AGENTS.md` nor an embedded section.
- The 16 new "Architecture rules" and "Fork architecture rules" units were assigned by hand (rule and digest tag); `sync` left them as `?` and failed until then. The first-launch and "Before changing an area" items stay in `CLAUDE.md` verbatim; four digests were shortened to stay under the 16,384-byte cap (now 16,378).
- Updated core items: the Python-tests command, `kotlinx-coroutines-test`, the AI request budget; the PR skill gained the release-signing and outside-PR flag text.

Measured (Claude Code, `claude -p "/context all"` in this worktree): memory files 7.2k tokens (6.9k before the merge), whole session at start 22.6k. Bytes: `CLAUDE.md` 15,948 to 16,378, `AGENTS.override.md` 16,565 to 16,995. Not measured: the token cost with `AGENTS.md` loaded whole after the merge, and a Serena-only invariant probe. Passed: Python suite (93 tests), drift check, a live Read probe that loaded `webview-playback.md` with the embedded "Player and YouTube page" section, and two negative probes (renamed heading).

Follow-up fixes (same day):
- Links: the generator rebases every relative link of an `AGENTS.md` unit or embedded `tech-stack.md` section so it resolves from the generated file, and `check` fails on a link to a missing file or heading in any generated file (`rebase_links`, `broken_links`).
- Headroom: Testing conventions moved to the new path-scoped rule `testing` (test sources and `tools/tests`); `CLAUDE.md` keeps a one-line digest, the `formatNewCode` item and a routing row. `CLAUDE.md` is 15,019 of 16,384 bytes; `AGENTS.override.md` 15,636 of 18,432.
- `AiTranslationAccess.kt`: one condition re-wrapped by `formatNewCode` to clear the four `formatCheck` violations inherited from `mydevelop`; no token changes except whitespace and a trailing comma.
