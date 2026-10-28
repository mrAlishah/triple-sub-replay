# Open in Google Translate from the selection popup

Owner request 2026-10-28. Fork feature; no upstream files besides the popup are changed.

## Behavior

- The popup for a selected word, phrase or subtitle line has an "Open in Google Translate" button (`phrase_google_translate`, also in the ▾ list) after Pronounce.
- It opens `https://translate.google.com/?sl=<source>&tl=<target>&text=<text>&op=translate` in a browser only (device finding, commit e4946c1: a plain `ACTION_VIEW` let the Google Translate app claim the link and open without the text). The intent names one browser package: the default browser when it is one, else the first installed browser; the Translate app is never chosen. Browsers are found with a probe on `https://example.com` (needs the `https` `VIEW`/`BROWSABLE` `<queries>` entry). No browser means no launch and a clear toast. No private Activity names or extras.
- Slot rule (`googleTranslatePair`): Original Subtitle goes to Subtitle 1's language; Subtitle 1 and Subtitle 2 go to the Original Subtitle's language. Languages come from the slots' settings, never from display order. An Auto or unresolved original language is sent as `auto`; the same language on both sides also sends `auto` as source.
- Text is percent-encoded (UTF-8, `%20` for spaces). No clipboard use, no API, no background request.
- With no browser, a toast says so; it never falls back to the plain `ACTION_VIEW`.

## Validation

- `GoogleTranslateLinkTest` (pair rules, `auto`, same language, encoding of spaces, punctuation, Unicode and Persian).
- `pickBrowserPackage` unit tests; `GoogleTranslateLaunchTest` (intent is aimed at the browser package); `PhraseSelectionUiTest` (the button sends the selected word).
- Device check still needed: the Google Translate page's input box holds the selected text and languages.
