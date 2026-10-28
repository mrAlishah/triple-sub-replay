# Open in Google Translate from the selection popup

Owner request 2026-10-28. Fork feature; no upstream files besides the popup are changed.

## Behavior

- The popup for a selected word, phrase or subtitle line has an "Open in Google Translate" button (`phrase_google_translate`, also in the ▾ list) after Pronounce.
- It opens `https://translate.google.com/?sl=<source>&tl=<target>&text=<text>&op=translate` with `ACTION_VIEW`. Android hands it to the Google Translate app when installed and verified for that link, otherwise to a browser. No private Activity names or extras; the app is not claimed to always keep the language pair.
- Slot rule (`googleTranslatePair`): Original Subtitle goes to Subtitle 1's language; Subtitle 1 and Subtitle 2 go to the Original Subtitle's language. Languages come from the slots' settings, never from display order. An Auto or unresolved original language is sent as `auto`; the same language on both sides also sends `auto` as source.
- Text is percent-encoded (UTF-8, `%20` for spaces). No clipboard use, no API, no background request.
- If nothing can open the link, a toast says so.

## Validation

- `GoogleTranslateLinkTest` (pair rules, `auto`, same language, encoding of spaces, punctuation, Unicode and Persian).
- Not verified on a physical device or with the live Google Translate app.
