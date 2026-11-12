# Quality gates green again (complexityCheck, formatCheck, lint)

Owner request 2026-11-12. Fork tooling and fork-owned code only; no behavior change.

## Findings (verified on `mydevelop` 9e3b0fc8)

| Gate | Result | Class |
| --- | --- | --- |
| `complexityCheck` | FAIL: `LongMethod` 186/100 in `DefaultTabProfileEditor` (`ui/TabSettingsEditor.kt`), not in the baseline | real violation (fork code) |
| `formatCheck` | FAIL to start: `quality.gradle.kts` runs `python`, which macOS often lacks (only `python3`) | tooling |
| `formatReport` + ratchet | 192 findings in 11 files, every one with a zero budget (files absent from `format-budget.json`): paths match, budgets are not stale | real violations in new fork files and in fork edits of upstream files |
| `:app:lintDebug` | see implementation record | |

## Decision

- Split `DefaultTabProfileEditor` into private section composables in the same file, called in the same order inside the same parent, so layout,
  test tags, callbacks, preference keys and Default-profile isolation (it only reads and writes the stored Default profile) are unchanged.
  No threshold, baseline or suppression change.
- `formatCheck` finds a Python interpreter (`python3`, `python`, or `py -3` on Windows) and fails with a clear message when none exists; it never skips.
- Format only the files the ratchet names, with the project's own `formatNewCode` (ktlint). Upstream-owned files have a zero budget, so ktlint can only
  touch fork-added code there. The budget file is not edited.

## Acceptance

`complexityCheck`, `formatCheck`, `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug` pass; `git diff` shows formatting and the editor split only.

## Implementation record

Done on branch `fix_quality_gates` (base 9e3b0fc8):

- `DefaultTabProfileEditor` split into `DefaultLanguageRows`, `DefaultTranslatorRows`, `DefaultTextRows`, `DefaultOrderRows`, `DefaultColorRows` and
  `DefaultLearningAndSpeedRows` (private, same file, same order, same callbacks and keys). `complexityCheck`: PASS, baseline untouched.
- `quality.gradle.kts`: `findPython3()` tries `python3`, `python`, `py -3` and throws a clear `GradleException` when none runs Python 3 (not exercised
  with Python missing; only the macOS `python3` path ran). `formatCheck`: PASS via Gradle.
- `formatNewCode` on the 11 files named by the ratchet (3 upstream-owned files only changed in fork-added lines); `format-budget.json` unchanged.
- `:app:lintDebug` PASS, `:app:testDebugUnitTest` 1666 passed, `:app:assembleDebug` PASS. No device tests. UI of the Default editor is unverified on a device.
