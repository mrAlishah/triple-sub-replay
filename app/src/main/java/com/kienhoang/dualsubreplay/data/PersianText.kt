package com.kienhoang.dualsubreplay.data

/**
 * Spelling variants of one Persian word as one key: Arabic yeh and kaf become Persian ones, and
 * vowel marks and tatweel go. The zero-width non-joiner stays, because it is part of the word.
 * Rule-based only; there is no Persian lemma or part-of-speech analysis.
 */
internal fun normalizePersian(text: String): String =
    buildString(text.length) {
        for (char in text) {
            when {
                char == 'ي' || char == 'ى' -> append('ی')
                char == 'ك' -> append('ک')
                char in 'ً'..'ْ' || char == 'ـ' -> Unit
                else -> append(char)
            }
        }
    }
