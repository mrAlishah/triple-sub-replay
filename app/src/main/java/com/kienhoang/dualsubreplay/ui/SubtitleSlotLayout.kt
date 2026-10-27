package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlin.math.roundToInt

/**
 * Display order and per-slot size of the three subtitle slots. A slot's identity ([SubtitleSlot]) never
 * changes with its position: language, visibility, RTL, size and meaning mappings stay keyed by the slot.
 */
internal const val SUBTITLE_ORDER_PREFERENCE = "subtitle_order"

internal const val SLOT_SCALE_MIN = 0.7f
internal const val SLOT_SCALE_MAX = 1.6f

/** Stable storage id of a slot; never shown to the user and never changed. */
internal val SubtitleSlot.stableId: String
    get() =
        when (this) {
            SubtitleSlot.ORIGINAL -> "original"
            SubtitleSlot.SECOND -> "second"
            SubtitleSlot.THIRD -> "third"
        }

internal val SubtitleSlot.scalePreference: String get() = "font_scale_$stableId"

/** The short role name: Original Subtitle, Subtitle 1, Subtitle 2. */
@get:StringRes
internal val SubtitleSlot.roleLabelRes: Int
    get() =
        when (this) {
            SubtitleSlot.ORIGINAL -> R.string.subtitle_role_original
            SubtitleSlot.SECOND -> R.string.subtitle_role_first
            SubtitleSlot.THIRD -> R.string.subtitle_role_second
        }

/** The Above/Below chip's value for [this] order: whether the original sits above or below Subtitle 1. */
internal fun List<SubtitleSlot>.translationPosition(): TranslationPosition =
    if (indexOf(SubtitleSlot.SECOND) < indexOf(SubtitleSlot.ORIGINAL)) TranslationPosition.ABOVE else TranslationPosition.BELOW

internal val DEFAULT_SUBTITLE_ORDER: List<SubtitleSlot> = listOf(SubtitleSlot.ORIGINAL, SubtitleSlot.SECOND, SubtitleSlot.THIRD)

/** A permutation of all three slots from [raw] ("original,second,third"), or null when it is anything else. */
internal fun parseSubtitleOrder(raw: String?): List<SubtitleSlot>? {
    val parsed = raw?.split(',')?.map { id -> SubtitleSlot.entries.firstOrNull { it.stableId == id.trim() } ?: return null }
    return parsed?.takeIf { it.size == SubtitleSlot.entries.size && it.toSet().size == it.size }
}

internal fun List<SubtitleSlot>.serializedOrder(): String = joinToString(",") { it.stableId }

/**
 * The saved order; without one, the older Above/Below choice decides, so upgrading changes nothing
 * (Above put the second subtitle first and the third stayed last).
 */
internal fun storedSubtitleOrder(
    raw: String?,
    position: TranslationPosition,
): List<SubtitleSlot> =
    parseSubtitleOrder(raw)
        ?: if (position == TranslationPosition.ABOVE) {
            listOf(SubtitleSlot.SECOND, SubtitleSlot.ORIGINAL, SubtitleSlot.THIRD)
        } else {
            DEFAULT_SUBTITLE_ORDER
        }

/** Moves [slot] one place up (negative) or down (positive), swapping with its neighbour; at an end nothing changes. */
internal fun List<SubtitleSlot>.moved(
    slot: SubtitleSlot,
    delta: Int,
): List<SubtitleSlot> {
    val from = indexOf(slot)
    val to = from + delta
    if (from < 0 || to !in indices) return this
    return toMutableList().also {
        it[from] = it[to]
        it[to] = slot
    }
}

/** The lines to draw, top first; a hidden slot yields null and leaves no gap, while keeping its place. */
internal fun <T> List<SubtitleSlot>.arranged(line: (SubtitleSlot) -> T?): List<T> = mapNotNull(line)

/** Size multiplier per slot on top of the global text size; 1 keeps every existing user's current size. */
data class SlotFontScale(
    val original: Float = 1f,
    val second: Float = 1f,
    val third: Float = 1f,
) {
    operator fun get(slot: SubtitleSlot): Float =
        when (slot) {
            SubtitleSlot.ORIGINAL -> original
            SubtitleSlot.SECOND -> second
            SubtitleSlot.THIRD -> third
        }

    fun with(
        slot: SubtitleSlot,
        scale: Float,
    ): SlotFontScale {
        val safe = normalizeSlotScale(scale)
        return when (slot) {
            SubtitleSlot.ORIGINAL -> copy(original = safe)
            SubtitleSlot.SECOND -> copy(second = safe)
            SubtitleSlot.THIRD -> copy(third = safe)
        }
    }
}

internal fun normalizeSlotScale(value: Float): Float = if (value.isNaN()) 1f else value.coerceIn(SLOT_SCALE_MIN, SLOT_SCALE_MAX)

internal fun storedSlotFontScale(preferences: SharedPreferences): SlotFontScale =
    SubtitleSlot.entries.fold(SlotFontScale()) { scales, slot ->
        scales.with(slot, preferences.getFloat(slot.scalePreference, 1f))
    }

/** Preference keys that belong to this feature, so "Reset all settings" and settings backup carry them. */
internal val SLOT_LAYOUT_SETTING_KEYS: List<String> =
    listOf(SUBTITLE_ORDER_PREFERENCE, LEGACY_SUBTITLE_ALIGNMENT_PREFERENCE) +
        SubtitleSlot.entries.map { it.scalePreference } + SubtitleSlot.entries.map { it.alignmentPreference }

/** The role name, followed by the slot's language when one is known; Auto or unresolved shows the role alone. */
@Composable
internal fun slotRoleLabel(
    slot: SubtitleSlot,
    language: String?,
): String =
    if (language.isNullOrBlank()) {
        stringResource(slot.roleLabelRes)
    } else {
        stringResource(R.string.subtitle_role_with_language, stringResource(slot.roleLabelRes), language)
    }

/** Position (Move up / Move down) and size of one slot in the Subtitle languages dialog. */
@Composable
internal fun SlotLayoutControls(
    slot: SubtitleSlot,
    tag: String,
) {
    val controls = LocalTripleSubtitleControls.current
    val role = stringResource(slot.roleLabelRes)
    val index = controls.order.indexOf(slot)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_slot_position, index + 1),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).testTag("${tag}_position"),
        )
        IconButton(onClick = { controls.onMove(slot, -1) }, enabled = index > 0, modifier = Modifier.testTag("${tag}_up")) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.settings_slot_move_up, role))
        }
        IconButton(
            onClick = { controls.onMove(slot, 1) },
            enabled = index in 0 until controls.order.lastIndex,
            modifier = Modifier.testTag("${tag}_down"),
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_slot_move_down, role))
        }
    }
    val scale = controls.fontScales[slot]
    val sizeLabel = "${stringResource(R.string.settings_slot_size)} $role"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_slot_size), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = scale,
            onValueChange = { controls.onFontScaleChange(slot, (it * 20).roundToInt() / 20f) },
            valueRange = SLOT_SCALE_MIN..SLOT_SCALE_MAX,
            modifier = Modifier.weight(1f).testTag("${tag}_size").semantics { contentDescription = sizeLabel },
        )
        Text(stringResource(R.string.settings_slot_size_value, (scale * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
    }
    SlotAlignmentChips(slot, tag)
}

/** The one global key of the first version of this setting; read as the default of every slot, never written. */
internal const val LEGACY_SUBTITLE_ALIGNMENT_PREFERENCE = "subtitle_alignment"

internal val SubtitleSlot.alignmentPreference: String get() = "subtitle_alignment_$stableId"

/** How subtitle lines sit on the video. AUTO keeps each line on the side its own text direction asks for. */
enum class SubtitleAlignment(
    val storageValue: String,
    @StringRes val labelRes: Int,
) {
    AUTO("auto", R.string.settings_subtitle_alignment_auto),
    LEFT("left", R.string.settings_subtitle_alignment_left),
    CENTER("center", R.string.settings_subtitle_alignment_center),
    RIGHT("right", R.string.settings_subtitle_alignment_right),
}

internal fun storedSubtitleAlignment(raw: String?): SubtitleAlignment =
    SubtitleAlignment.entries.firstOrNull { it.storageValue == raw } ?: SubtitleAlignment.AUTO

/** The text alignment inside a line; AUTO leaves it to the text direction. */
internal fun SubtitleAlignment.textAlign(): TextAlign =
    when (this) {
        SubtitleAlignment.AUTO -> TextAlign.Unspecified
        SubtitleAlignment.LEFT -> TextAlign.Left
        SubtitleAlignment.CENTER -> TextAlign.Center
        SubtitleAlignment.RIGHT -> TextAlign.Right
    }

/** Where the lines sit inside the subtitle box; left and right are absolute, whatever the interface language. */
internal fun SubtitleAlignment.horizontal(): Alignment.Horizontal =
    when (this) {
        SubtitleAlignment.AUTO -> Alignment.Start
        SubtitleAlignment.LEFT -> AbsoluteAlignment.Left
        SubtitleAlignment.CENTER -> Alignment.CenterHorizontally
        SubtitleAlignment.RIGHT -> AbsoluteAlignment.Right
    }

/** Alignment per slot, so each subtitle line can sit on its own side. */
data class SlotAlignment(
    val original: SubtitleAlignment = SubtitleAlignment.AUTO,
    val second: SubtitleAlignment = SubtitleAlignment.AUTO,
    val third: SubtitleAlignment = SubtitleAlignment.AUTO,
) {
    operator fun get(slot: SubtitleSlot): SubtitleAlignment =
        when (slot) {
            SubtitleSlot.ORIGINAL -> original
            SubtitleSlot.SECOND -> second
            SubtitleSlot.THIRD -> third
        }

    fun with(
        slot: SubtitleSlot,
        alignment: SubtitleAlignment,
    ): SlotAlignment =
        when (slot) {
            SubtitleSlot.ORIGINAL -> copy(original = alignment)
            SubtitleSlot.SECOND -> copy(second = alignment)
            SubtitleSlot.THIRD -> copy(third = alignment)
        }
}

internal fun storedSlotAlignment(preferences: SharedPreferences): SlotAlignment {
    val legacy = storedSubtitleAlignment(preferences.getString(LEGACY_SUBTITLE_ALIGNMENT_PREFERENCE, null))
    return SubtitleSlot.entries.fold(SlotAlignment()) { alignment, slot ->
        val saved = preferences.getString(slot.alignmentPreference, null)
        alignment.with(slot, if (saved == null) legacy else storedSubtitleAlignment(saved))
    }
}

/** Auto, Left, Center and Right chips for one slot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlotAlignmentChips(
    slot: SubtitleSlot,
    tag: String,
) {
    val controls = LocalTripleSubtitleControls.current
    Text(stringResource(R.string.settings_subtitle_alignment_title), style = MaterialTheme.typography.bodyMedium)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubtitleAlignment.entries.forEach { option ->
            FilterChip(
                selected = controls.alignment[slot] == option,
                onClick = { controls.onAlignmentChange(slot, option) },
                label = { Text(stringResource(option.labelRes)) },
                modifier = Modifier.testTag("${tag}_alignment_${option.storageValue}"),
            )
        }
    }
}
