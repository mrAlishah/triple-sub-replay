package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

private const val GOOGLE_AUTO = "auto"

/** A source and target language for Google Translate; [source] is "auto" when it is not known. */
internal data class GoogleTranslatePair(
    val source: String,
    val target: String,
)

/**
 * Which languages Google Translate gets for a tapped line. The slot decides, never the display
 * order: the original line goes to Subtitle 1's language; Subtitle 1 and Subtitle 2 go back to
 * the original language. [original] is null when unresolved, then Google detects it.
 */
internal fun googleTranslatePair(
    original: String?,
    subtitle1: String,
    translated: Boolean,
    lineLanguage: String?,
): GoogleTranslatePair {
    val originalCode = original?.takeUnless { it.isBlank() || it == GOOGLE_AUTO }
    val pair =
        when {
            lineLanguage != null -> GoogleTranslatePair(lineLanguage, originalCode ?: subtitle1)
            translated -> GoogleTranslatePair(subtitle1, originalCode ?: subtitle1)
            else -> GoogleTranslatePair(originalCode ?: GOOGLE_AUTO, subtitle1)
        }
    // Same language on both sides would translate nothing: let Google detect the source instead.
    return if (pair.source == pair.target) pair.copy(source = GOOGLE_AUTO) else pair
}

/** The official web address; the Google Translate app opens it when installed, else a browser does. */
internal fun googleTranslateUrl(
    text: String,
    pair: GoogleTranslatePair,
): String {
    fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return "https://translate.google.com/?sl=${enc(pair.source)}&tl=${enc(pair.target)}&text=${enc(text)}&op=translate"
}

/** Opens [url]; false when no app can show it, so the caller can tell the user. */
internal fun openGoogleTranslate(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
