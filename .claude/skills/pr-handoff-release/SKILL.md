---
name: pr-handoff-release
description: "Use when opening, updating, reviewing or handing off a pull request in this repository: choosing release:patch, minor, major or skip or a Release-Version directive, checking final-head Android CI (verify-build, managed-device-tests), sending the owner the preview release page link, or acting as a coding agent for an outside contributor."
---
<!-- Generated from AGENTS.md by tools/claude_instructions.py. Do not edit; run `python3 tools/claude_instructions.py sync`. -->

# Pull request handoff, release intent and CI

## Preparing PRs for owner merge

- Read `.github/workflows/release-on-main.yml` and `tools/automatic_release.py` before handoff; they define actual automation. Use the [release-intent mapping](docs/specs/README.md#release-intent-is-a-decision-not-automation).

- Follow explicit owner release intent; otherwise preserve **patch** for any change that reaches the APK. Recommend alternatives if useful, but do not silently infer minor/major. **Owner decision: apply `release:skip` without asking when the shipped APK cannot change**, that is, no changed file is in `app/src/main/**`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` or `gradle/**` (version catalog, wrapper). Docs, site, promo media, workflows, release tools, `tools/**`, tests (`app/src/test/**`, `app/src/androidTest/**`), `benchmark/**` and lint/quality config all qualify. If any of those APK files changes or you are unsure, keep patch and ask.

- Apply and verify the actual GitHub label or standalone exact-version PR-body directive. Prose/specs/checkboxes do not apply labels. Keep at most one release label OR one directive; remove superseded overrides without disturbing unrelated labels. If required metadata cannot be applied, report the missing action and do not call the PR ready to merge.

- State intent, reason, and expected version (or why skipped/unavailable). Inspect stable tags, releases including draft reservations, and the Gradle baseline before estimating. Bump-derived numbers are estimates until reserved; exact versions must exceed all existing/reserved stable versions. Do not manually change Gradle versions or create release tags in normal PRs.

- When the final-head CI passes, send the owner the preview release page link, https://github.com/hoangkien1703/dual-sub-replay/releases/tag/preview, so they can test on a phone before merging. **Never send a direct `.apk` download link** (owner decision 2026-10-08): the owner can't download from it and downloads the APK from the release page themselves. `pr-preview-auto.yml` uploads it to that rolling `preview` release as `DualSub-Replay-PR<N>-build<run>-preview.apk`, so name the asset to pick; a newer build of the same PR replaces the older asset. The APK goes up as soon as `verify-build` passes, while `managed-device-tests` still runs (owner pick 2026-11-11, [spec](docs/specs/2026-11-11-earlier-pr-preview.md)), so the owner can start testing sooner; an APK on that page does not mean the PR is green.

- Before calling a PR ready to merge, verify its latest final-head Android CI run: both jobs (`verify-build`, `managed-device-tests`) must succeed. New commits need fresh checks; report pending/failed/skipped/unavailable checks explicitly.

- Handoff: owner reviews the preview and green checks, then manually merges; existing automation publishes unless skipped. Green PR CI is not proof of release publication. Verify the release workflow/assets if asked to confirm publication; use existing reservation/retry recovery below.

## Releases happen automatically after an approved PR merge

- When `hoangkien1703` merges a PR into `main`, `release-on-main.yml` checks the latest Android CI run for the PR's final head and requires both Android CI jobs (`verify-build`, `managed-device-tests`) to succeed. Direct pushes and other mergers do not release. Android CI also runs on every push to `main` so `setup-gradle` saves fresh caches for PR runs; those runs never count as PR CI.

- Default: bump the highest existing/reserved stable patch version and monotonically increase Android `versionCode`. **Do not manually bump Gradle version constants in feature PRs.**

- Before merge, apply at most one GitHub label (`release:patch`, `release:minor`, `release:major`, `release:skip`) OR a standalone `Release-Version: X.Y.Z` in the PR description. Conflicting instructions fail closed. `release:skip` publishes nothing.

- Release versions live in a release-only commit/tag based on the exact merge; only the two Gradle version constants change. `main` keeps development defaults. No bot commits to `main` or branch-protection bypass is needed.

- Draft release metadata reserves version/name/code/source before building. Retry failed runs (or dispatch with a merged PR number) to resume that reservation. Never delete reservations or edit their hidden marker to retry. Published merges are a no-op.

- Production signature, package, and version are verified before upload; the draft is published only with both APK and checksum present. Release notes are generated automatically. Queued runs cannot allocate the same version, and older retries cannot replace a newer Latest release.

- Merges into `main` publish no preview APK (owner decision 2026-10-18); the `preview` release holds only open PRs' `DualSub-Replay-PR<N>-build<run>-preview.apk` builds. The PR CI artifact stays `DualSub-Replay-preview`, the name `pr-preview-auto.yml` downloads, and the build job stays named `verify-build`, the job that workflow waits for. `pr-preview-auto.yml` runs from `main`, so a change to it can only be checked on the first PR after it merges.

- Release builds require the four `ANDROID_RELEASE_*` env vars plus `-PrequireReleaseSigning=true`. Never commit signing material (`*.jks`/`*.keystore` are gitignored).

- Test workflow changes with `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` and validate workflow YAML/shell. These tests also run in Android PR CI.

## AI agents opening or updating pull requests

These rules apply to any coding agent (Claude, Copilot, Codex, Cursor and others), whether it works for an outside contributor or for the owner. The human process is in [CONTRIBUTING.md](CONTRIBUTING.md#how-review-works); agents follow it too.

- One focused change per PR, from its own branch. Outside contributors open it from a fork with **Allow edits by maintainers** ticked. Say in the PR body that an agent wrote or changed the code, and fill in the PR template honestly: not run is not passed.

- Never commit or paste secrets: API keys, tokens, `*.jks`/`*.keystore`, `ANDROID_RELEASE_*` values, `local.properties`, personal YouTube data, or caption/video dumps. Do not print secrets in logs or PR text. Keep test fixtures offline and synthetic.

- Do not bump the Gradle version constants, create tags or releases, or edit `.github/workflows/**`, `tools/automatic_release.py` or signing setup unless that is the agreed change.

- Release labels: an agent working for an outside contributor never adds, changes or removes `release:*` labels or a `Release-Version:` line; it only states the expected release in the template. An agent working for the owner follows "Preparing PRs for owner merge" above.

- Review is a loop the PR's author drives. Read every review comment and failed CI check, then push a new commit that fixes it or reply saying why not; keep going until review is done and both Android CI jobs pass on the latest commit. Push new commits rather than force-pushing after review has started, and leave resolving a comment thread to the reviewer.

- Never merge, enable auto-merge, approve your own PR, close and reopen to re-run CI, push empty commits, or skip, disable or loosen a test to get green.

- The owner's agents reviewing an outside contributor's PR (owner decision 2026-10-28): review the code, security and privacy, and leave specific comments or suggested changes; the contributor changes their own PR. Do not rewrite their branch or open a competing PR unless the owner asks. Use **Allow edits by maintainers** only for small mechanical fixes (merging `main`, a conflict, a typo) and say so in a comment. The owner decides when to merge, once everyone agrees and the app works on a phone.
