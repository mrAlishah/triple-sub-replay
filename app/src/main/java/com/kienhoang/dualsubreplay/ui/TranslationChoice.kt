package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.Translated
import java.io.IOException

/**
 * One text's translation from the first source that has it, with who wrote it: the AI's if it already has it
 * (AI only also waits for the running request), else Google's, and the device's for a line Google leaves blank.
 * When Google is unreachable the AI's running request may still bring the text; AI only lets Google's failure
 * through instead, because it already waited for the AI.
 *
 * [fromAi] gets whether to wait for the running request and returns null when the AI has no translation (or none
 * is selected); [fromGoogle] returns null for a blank answer and throws [IOException] when no address answers.
 */
internal suspend fun chooseTranslation(
    aiOnly: Boolean,
    fromAi: suspend (wait: Boolean) -> Translated?,
    fromGoogle: suspend () -> Translated?,
    onDevice: suspend () -> Translated,
): Translated =
    fromAi(false)
        ?: (if (aiOnly) fromAi(true) else null)
        ?: try {
            fromGoogle()
        } catch (error: IOException) {
            if (aiOnly) throw error
            fromAi(true) ?: throw error
        }
        ?: onDevice()
