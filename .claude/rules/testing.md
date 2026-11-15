---
paths:
  - "app/src/test/**/*.kt"
  - "app/src/androidTest/**/*.kt"
  - "benchmark/src/**/*.kt"
  - "tools/tests/*.py"
---
<!-- Generated from AGENTS.md by tools/claude_instructions.py. Do not edit; run `python3 tools/claude_instructions.py sync`. -->

# Testing conventions

## Testing conventions

- Unit tests are plain JUnit4 — no Robolectric or mocking library. They call `internal` top-level functions directly (same package), so keep new logic in small testable `internal` functions. `org.json` is a unit-test-only dependency, and so is `kotlinx-coroutines-test` (virtual time, for tests that wait minutes).

- AI request budget ([spec](../../docs/specs/2026-11-15-ai-request-and-size-checks.md)): `AiRequestBudgetTest` counts every request a fake AI service receives on virtual time and fails when one key is asked for one model more than 10 times in any minute. A new automatic AI request path (not a message the person sends) goes through `AiKeyPacer` and gets a scenario there.

- `unitTests.isReturnDefaultValues = true`: Android framework calls return defaults instead of throwing in unit tests.

- Instrumented tests build Compose UI in isolation (`createComposeRule`) and WebView fixtures run offline — tests never hit the live YouTube site.

- The CI emulator sometimes shows "System UI isn't responding", which holds window focus. Call `closeSystemDialogs()` (`ui/SystemDialogs.kt` in `androidTest`) just before Espresso's `pressBack` or `closeSoftKeyboard` in a new test.

- Compose merges a `Text` inside a clickable `Row` into the row's node, so a test that looks for that text needs `useUnmergedTree = true`.

- When a check fails, the CI job summary lists the failed tests (`tools/report_failed_tests.py`). For device tests, the full JUnit XML is in the `pixel2-api36-test-reports` artifact; the managed-device log does not print failing test names.

- The transcript the player holds is a window of lines around the playing time, not the whole video. Code that keeps a line across seeks (loops, repeats) must handle that line leaving the window. Check long seeks and ads on an emulator with a real video; unit tests alone miss both cases.
