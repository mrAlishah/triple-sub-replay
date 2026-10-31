package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationLanguages

/**
 * The AI's better lines for rows already showing another source's, with who wrote them, for one subtitle line ([target]).
 * Given the line's [window] (not AI only) the window keeps asking the AI for what it still owes; every outcome is recorded in
 * [diagnostics] without text. Shared by Subtitle 1 and Subtitle 2 so both upgrade the same way.
 */
internal fun aiTranslationUpgrades(
    translator: AiSubtitleTranslator,
    diagnostics: TranslationDiagnostics,
    model: AiTranslationModel,
    source: String,
    target: String,
    window: AiWindowTranslation?,
): TranslationUpgrades {
    val language = TranslationLanguages.normalize(target)
    return TranslationUpgrades(
        translator.updates,
        originOf = { text -> translator.originOf(model, source, target, text) },
        onIdentical = { origin -> diagnostics.record(TranslationEventKind.AI_IDENTICAL, language, origin) },
        onUpgraded = { count, origin -> diagnostics.record(TranslationEventKind.AI_UPGRADED, language, origin, count) },
        ensure = window?.let { it::ensure },
    ) { text -> translator.shownTranslation(model, source, target, text) }
}
