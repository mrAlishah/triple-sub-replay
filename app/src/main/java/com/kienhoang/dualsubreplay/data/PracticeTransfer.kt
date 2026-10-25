package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

internal const val MAX_TRANSFER_BYTES = 8 * 1024 * 1024
internal const val MAX_TRANSFER_RECORDS = 20_000
internal val transferColumns =
    listOf(
        "word",
        "meaning",
        "reading",
        "wordLanguage",
        "meaningLanguage",
        "sentence",
        "translatedSentence",
        "source",
        "startMs",
        "endMs",
    )

/** Not in [transferColumns] (those drive the import mapping form); read by header name, so old files lack it. */
internal const val TRANSLATIONS_COLUMN = "sentenceTranslations"
internal const val MEANINGS_COLUMN = "meanings"

/** One "language: text" line per translation; line breaks inside a text become spaces. */
internal fun encodeSentenceTranslations(translations: Map<String, String>): String =
    translations.entries.joinToString("\n") { (language, text) -> "$language: ${text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ")}" }

internal fun decodeSentenceTranslationsText(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    text.lineSequence().forEach { line ->
        val language = line.substringBefore(':', "").trim()
        val translation = line.substringAfter(':', "").trim()
        if (language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) && translation.isNotEmpty()) result[language] = translation
    }
    return result
}

private fun translationsCell(
    document: TransferDocument,
    row: List<String>,
    column: String = TRANSLATIONS_COLUMN,
): String {
    val index = document.columns.indexOf(column)
    return if (index >= 0) row.getOrNull(index).orEmpty() else ""
}

internal data class TransferDocument(
    val backup: List<SavedWord>? = null,
    val rows: List<List<String>> = emptyList(),
    val columns: List<String> = emptyList(),
    val invalid: Int = 0,
)

internal data class ImportPreview(
    val words: List<SavedWord>,
    val invalid: Int,
    val duplicates: Int,
)

internal fun readTransferText(input: InputStream): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= MAX_TRANSFER_BYTES) {
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_TRANSFER_BYTES + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    val bytes = output.toByteArray()
    require(bytes.size <= MAX_TRANSFER_BYTES) { "Import is larger than 8 MiB." }
    return Charsets.UTF_8
        .newDecoder()
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()
        .removePrefix("\uFEFF")
}

internal fun exportBackup(words: List<SavedWord>): String =
    JSONObject()
        .apply {
            require(words.size <= MAX_TRANSFER_RECORDS) { "Export exceeds 20,000 records." }
            put("format", "DualSubReplay")
            put("version", 1)
            put("words", JSONArray().apply { words.forEach { put(encodeWord(it)) } })
        }.toString(2)

internal fun parseTransfer(text: String): TransferDocument {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_TRANSFER_BYTES) { "Import is larger than 8 MiB." }
    val clean = text.removePrefix("\uFEFF")
    if (clean.trimStart().startsWith("{")) {
        val json = JSONObject(clean)
        require(json.getString("format") == "DualSubReplay" && json.getInt("version") == 1) {
            "Unsupported backup format or version."
        }
        val rows = json.getJSONArray("words")
        require(rows.length() <= MAX_TRANSFER_RECORDS) { "Import exceeds 20,000 records." }
        var invalid = 0
        val words =
            (0 until rows.length()).mapNotNull { index ->
                try {
                    decodeWord(rows.getJSONObject(index)).also(::validateTransferWord)
                } catch (_: Exception) {
                    invalid++
                    null
                }
            }
        return TransferDocument(backup = words, invalid = invalid)
    }
    val headers = mutableListOf<List<String>>()
    val parsed = parseTsv(clean, headers::add)
    val columns =
        headers
            .firstOrNull { it.firstOrNull()?.startsWith("#columns:") == true }
            ?.let { listOf(it.first().substringAfter(':')) + it.drop(1) }
            .orEmpty()
    val rows = parsed.filterNot { it.all(String::isBlank) }
    require(rows.size <= MAX_TRANSFER_RECORDS) { "Import exceeds 20,000 records." }
    return TransferDocument(rows = rows, columns = columns)
}

/** Quoted TSV supports embedded tabs, CR/LF, and doubled quotes, as Anki does. */
internal fun parseTsv(
    text: String,
    onComment: ((List<String>) -> Unit)? = null,
): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var closedQuote = false
    var index = 0
    var comment = false
    var rowCount = 0

    fun endCell() {
        row.add(cell.toString())
        cell.setLength(0)
        closedQuote = false
    }

    fun endRow() {
        rowCount++
        require(rowCount <= MAX_TRANSFER_RECORDS + 20) { "Too many rows." }
        endCell()
        if (comment && onComment != null) onComment(row) else rows.add(row)
        row = mutableListOf()
        comment = false
    }
    while (index < text.length) {
        val char = text[index++]
        if (isTsvCommentStart(quoted, closedQuote, row, cell, char)) comment = true
        if (quoted) {
            if (char == '"') {
                if (index < text.length && text[index] == '"') {
                    cell.append('"')
                    index++
                } else {
                    quoted = false
                    closedQuote = true
                }
            } else {
                cell.append(char)
            }
        } else {
            when (char) {
                '"' -> {
                    require(cell.isEmpty() && !closedQuote) { "Unexpected quote in TSV." }
                    quoted = true
                }
                '\t' -> endCell()
                '\r', '\n' -> {
                    endRow()
                    if (char == '\r' && index < text.length && text[index] == '\n') index++
                }
                else -> {
                    require(!closedQuote) { "Unexpected text after quoted field." }
                    cell.append(char)
                }
            }
        }
        require(rows.size <= MAX_TRANSFER_RECORDS + 20 && row.size <= 100) { "Too many rows or columns." }
    }
    require(!quoted) { "Unclosed quote in TSV." }
    if (cell.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
    return rows
}

private fun isTsvCommentStart(
    quoted: Boolean,
    closedQuote: Boolean,
    row: List<String>,
    cell: StringBuilder,
    char: Char,
): Boolean = !quoted && !closedQuote && row.isEmpty() && cell.isEmpty() && char == '#'

internal fun exportAnkiTsv(words: List<SavedWord>): String {
    require(words.size <= MAX_TRANSFER_RECORDS) { "Export exceeds 20,000 records." }

    fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    return "#separator:Tab\n#html:false\n#columns:${(transferColumns + TRANSLATIONS_COLUMN + MEANINGS_COLUMN).joinToString("\t")}\n" +
        words.joinToString("\n") { word ->
            listOf(
                word.word,
                word.meaning,
                word.reading.orEmpty(),
                word.wordLanguage,
                word.meaningLanguage,
                word.sentence,
                word.translatedSentence.orEmpty(),
                word.videoId?.let { "https://www.youtube.com/watch?v=$it" }.orEmpty(),
                word.startMs.toString(),
                word.endMs.toString(),
                encodeSentenceTranslations(word.sentenceTranslations),
                encodeSentenceTranslations(word.meanings),
            ).joinToString("\t", transform = ::quote)
        }
}

internal fun defaultTransferMapping(document: TransferDocument): Map<String, Int> =
    transferColumns.associateWith { name ->
        document.columns.indexOf(name).takeIf { it >= 0 } ?: when (name) {
            "word" -> 0
            "meaning" -> 1
            else -> -1
        }
    }

internal fun transferIdentity(word: SavedWord): String =
    listOf(
        word.word.trim().lowercase(Locale.ROOT),
        word.wordLanguage.lowercase(Locale.ROOT),
        word.meaningLanguage.lowercase(Locale.ROOT),
        word.videoId.orEmpty(),
        word.startMs.toString(),
        word.endMs.toString(),
        word.sentence.trim(),
    ).joinToString("\u0000")

internal fun previewImport(
    document: TransferDocument,
    mapping: Map<String, Int>,
    wordLanguage: String,
    meaningLanguage: String,
    existing: List<SavedWord>,
): ImportPreview {
    var invalid = document.invalid
    val candidates =
        document.backup ?: document.rows.mapNotNull { row ->
            try {
                fun field(name: String) = row.getOrNull(mapping[name] ?: -1).orEmpty()
                val source = field("source").trim()
                val videoId =
                    source.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                        ?: YouTubeUrlParser.extractVideoId(source)
                require(source.isBlank() || videoId != null) { "Invalid YouTube source." }
                val start = field("startMs").ifBlank { "0" }.toLong()
                val end = field("endMs").ifBlank { "0" }.toLong()
                val word =
                    SavedWord(
                        "pending",
                        field("word").trim(),
                        field("reading").ifBlank { null },
                        field("wordLanguage").ifBlank { wordLanguage }.trim(),
                        field("meaningLanguage").ifBlank { meaningLanguage }.trim(),
                        field("meaning").trim(),
                        field("sentence"),
                        field("translatedSentence").ifBlank { null },
                        videoId,
                        start,
                        end,
                        false,
                        validClipRange(videoId, start, end),
                        sentenceTranslations = decodeSentenceTranslationsText(translationsCell(document, row)),
                        meanings = decodeSentenceTranslationsText(translationsCell(document, row, MEANINGS_COLUMN)),
                    )
                validateTransferWord(word)
                val id =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(transferIdentity(word).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                word.copy(id = id)
            } catch (_: Exception) {
                invalid++
                null
            }
        }
    val existingByIdentity = existing.associateBy(::transferIdentity)
    val seen = existing.mapTo(mutableSetOf()) { it.id }
    var duplicates = 0
    val words =
        candidates.map { word ->
            val mapped =
                if (document.backup == null) {
                    existingByIdentity[transferIdentity(word)]?.let { word.copy(id = it.id) } ?: word
                } else {
                    word
                }
            if (!seen.add(mapped.id)) duplicates++
            mapped
        }
    return ImportPreview(words, invalid, duplicates)
}

internal fun validateTransferWord(word: SavedWord) {
    require(word.id.isNotBlank() && word.word.isNotBlank() && word.meaning.isNotBlank())
    require(word.wordLanguage.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")))
    require(word.meaningLanguage.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")))
    require(word.startMs >= 0 && word.endMs >= word.startMs && word.dueAt >= 0 && word.intervalMs >= 0)
    require(word.videoId == null || word.videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
    require(!word.online || validClipRange(word.videoId, word.startMs, word.endMs))
}
