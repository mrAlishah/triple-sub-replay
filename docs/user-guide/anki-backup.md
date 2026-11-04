# Anki, import and backup

[← User guide](README.md) · [Practice](practice.md)

## Back up your study data first

1. Open **Practice**.
2. Choose **Export**.
3. Select **JSON backup** for a full TripleSub Replay vocabulary backup when offered.
4. Use Android's file picker to save the file somewhere you control.
5. Keep a second copy before changing the app installation or switching package/signing variants.

The backup contains learning data, **not YouTube videos or a complete browser session**. Treat it as private.

## Import existing data

1. In **Practice**, choose **Import**.
2. Select a supported JSON or TSV file.
3. Review the validation/duplicate preview.
4. Keep existing entries unless you intentionally want replacement.
5. Check a few imported cards and their review state.

For formats, compatibility and limits, read [Practice import and export](../practice-transfer.md).

## Send to AnkiDroid

Where supported, open a saved word or its learning card and choose **Send to AnkiDroid**. AnkiDroid must be installed and the required integration permission/collection available.

The field mapping and note-type version have evolved across fork releases. For example, [v2.8.0](../releases/v2.8.0.md) documents the **TripleSub Replay v3** note type. **Do not rename an existing Anki note type or modify its fields just to match newer screenshots.** Older notes and review histories should remain intact.

AnkiConnect and AnkiDroid are different integration paths; their available features and requirements may differ by build.

## Avoid data loss

- Back up before uninstalling or changing signing/package variants.
- Do not assume a newly downloaded upstream APK can update the fork.
- Do not import into a production collection until you have verified mappings with a small test record.
- A JSON/TSV export is not identical to an Anki collection backup.

[Next: Troubleshooting →](troubleshooting.md)
