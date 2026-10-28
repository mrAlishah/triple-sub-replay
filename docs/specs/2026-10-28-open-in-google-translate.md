# Send the selected text to the Google Translate app

Owner request 2026-10-28 (revised twice the same day). Fork feature; only the selection popup is touched.

## History and root cause

- `e4946c1`: `ACTION_VIEW` on `translate.google.com/?sl&tl&text`. On a physical device the Google Translate app claimed the link and opened with an empty input: it ignores those query parameters.
- `a166b72`: the same URL aimed at a browser. Rejected by the owner: the requirement is the **native app**, never a web page.

## Requirement

Tapping the popup's "Open in Google Translate" button launches the installed Google Translate Android app with the exact selected text (word, phrase or line) in its input. No browser, Custom Tab, WebView, chooser, clipboard copy or private extras.

## Behavior

- Intent: `ACTION_SEND`, MIME `text/plain`, `EXTRA_TEXT` = the selected substring, `setPackage("com.google.android.apps.translate")`, `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`. Blank text sends nothing.
- Before launching, `PackageManager` must show the package installed and at least one **exported** activity matching that intent (manifest `<queries><package>` entry for package visibility). Results: `SENT`, `NOT_INSTALLED`, `CANNOT_RECEIVE` (no receiver, or `startActivity` threw). Each failure shows its own localized toast in all 8 languages; success shows nothing (a returned `startActivity` does not prove the input was filled).
- Languages: the standard share contract carries only text. Google documents no public entry point for source/target, and private extras are not used. So the app picks its own languages and **language preselection is not supported**. `googleTranslatePair` (Original → Subtitle 1; Subtitle 1 and 2 → Original; `auto` when unknown) is kept, tested, and not wired, ready if a verified entry point appears.

## Acceptance criteria

1. The intent is `ACTION_SEND` / `text/plain` / exact `EXTRA_TEXT` / package set to the Translate app.
2. Words, phrases, sentences, umlauts, ß, Persian with ZWNJ, punctuation and inner whitespace arrive unchanged.
3. Not installed, no exported receiver and launch failure each give a distinct message; the browser is never used.
4. Copy, Pronounce and the other popup actions behave as before.

## Plan

1. Replace the browser launcher in `ui/GoogleTranslateLink.kt` with `googleTranslateSendIntent` and `sendToGoogleTranslate`.
2. Change `PhraseActions.openGoogleTranslate` to `(text) -> Unit`; `BindPhraseActions` maps the result to a toast.
3. Manifest: replace the `https` browser query with `<package>` for the Translate app.
4. Strings: reword the existing "unavailable" string and add "cannot accept" in 8 languages.

## Validation

- Unit: result classification, pair rules.
- Instrumented: the real `Intent` contract (action, type, extra, package, no data URI, German and Persian/ZWNJ text); the popup button sends the selected word only.
- Manual checklist on a physical device below.

## Device checklist (not yet run; the phone has not been accessed)

Needs the Google Translate app installed.

- A. Select `Badeanzug`, tap the button: the app opens with `Badeanzug` in the input.
- B. Select a whole German sentence: the whole sentence is in the input.
- C. Select a Persian word containing a ZWNJ (e.g. `می‌خواهم`): the input holds it exactly.
- D. Note which source and target languages the app picked (German → English, Persian → German). Record this separately: it is expected **not** to follow the subtitle languages.
- E. Uninstall or disable the Translate app: the toast says it is not installed and no browser opens.

## Implementation record

Implemented as planned. Deviations: `googleTranslateUrl` and all browser code were deleted; `googleTranslatePair` stays unwired. The popup test and the Intent-contract instrumented tests compile but were **not run** (no device or emulator was used). 1064 unit tests, `formatCheck`, `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` pass; `complexityCheck` fails only on `DualSubExperience` in `DualSubApp.kt` (25/25), a file this feature does not touch. Whether the installed Translate app really exports a `text/plain` `ACTION_SEND` receiver, and fills its input, is unverified until the device checklist runs; `GoogleTranslateLaunchTest.reportsTheInstalledTranslateAppReceiver` prints what the device has.

## Device finding after `9084f42`

On the owner's phone the text arrived only sometimes: when the Translate app was already open, the selection was not added. Likely cause (not confirmed): with only `NEW_TASK` the running task is brought forward and the new intent is not read again. Fix: add `CLEAR_TASK` so the app starts fresh and reads the text on launch; its previous screen is lost. Re-test checklist item A–C with the Translate app already open, then closed.
