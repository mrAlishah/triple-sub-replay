package com.kienhoang.dualsubreplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlin.math.roundToInt

// Settings → Android TV (beta) (owner request 2026-10-29/30, spec 2026-10-30-android-tv-layout.md):
// on a TV the TV layout is on unless turned off; on phones and tablets it can never turn on.

internal const val TV_LAYOUT_PREFERENCE = "tv_layout"

/** Settings → Android TV (beta) → Show Search Settings on TV (owner request 2026-11-13); on unless turned off. */
internal const val TV_SETTINGS_SEARCH_PREFERENCE = "tv_settings_search_visible"

/** Reset all settings brings the TV layout, and its Search Settings, back to their defaults (on). */
internal val TV_LAYOUT_SETTING_KEYS = listOf(TV_LAYOUT_PREFERENCE, TV_SETTINGS_SEARCH_PREFERENCE)

/** Panel text on a TV is read from across the room. */
internal const val TV_TEXT_SCALE = 1.25f

/** Fire TV says so with its own feature instead of Android TV's. */
private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

/** A TV by any of the ways Android reports one: television UI mode, Android TV's leanback, or Fire TV. */
internal fun isTelevision(
    televisionUiMode: Boolean,
    leanback: Boolean,
    fireTv: Boolean,
): Boolean = televisionUiMode || leanback || fireTv

internal fun isTelevisionDevice(context: Context): Boolean {
    val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
    val packages = context.packageManager
    return isTelevision(
        televisionUiMode = uiMode == Configuration.UI_MODE_TYPE_TELEVISION,
        leanback = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
        fireTv = packages.hasSystemFeature(FIRE_TV_FEATURE),
    )
}

/** The TV layout is on only on a TV, and there unless the user turned it off. */
internal fun tvLayoutOn(
    television: Boolean,
    switchedOn: Boolean,
): Boolean = television && switchedOn

internal fun readTvLayout(
    preferences: SharedPreferences,
    television: Boolean,
): Boolean = tvLayoutOn(television, preferences.getBoolean(TV_LAYOUT_PREFERENCE, true))

/** The TV layout always keeps the subtitle panel beside the video; elsewhere Landscape split view decides. */
internal fun landscapeSplitWanted(
    setting: Boolean,
    tvLayout: Boolean,
): Boolean = setting || tvLayout

/** Whether the TV layout is on, following every change (from the settings page or the assistant). */
@Composable
internal fun rememberTvLayout(): State<Boolean> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val television = remember(context) { isTelevisionDevice(context) }
    val on = remember(preferences) { mutableStateOf(readTvLayout(preferences, television)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == TV_LAYOUT_PREFERENCE) on.value = readTvLayout(preferences, television)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        on.value = readTvLayout(preferences, television)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return on
}

/** Whether the TV settings page shows Search Settings, following every change; only read in the TV layout. */
@Composable
internal fun rememberTvSearchVisible(): State<Boolean> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val visible = remember(preferences) { mutableStateOf(preferences.getBoolean(TV_SETTINGS_SEARCH_PREFERENCE, true)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == TV_SETTINGS_SEARCH_PREFERENCE) {
                    visible.value = preferences.getBoolean(TV_SETTINGS_SEARCH_PREFERENCE, true)
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        visible.value = preferences.getBoolean(TV_SETTINGS_SEARCH_PREFERENCE, true)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}

/** True while the app shows its TV layout; false everywhere else, including isolated UI tests. */
internal val LocalTvLayout = compositionLocalOf { false }

/** Settings → Android TV (beta). On anything but a TV the switch stays off and greyed out. */
@Composable
internal fun TvLayoutSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val television = remember(context) { isTelevisionDevice(context) }
    val on by rememberTvLayout()
    SettingsSwitchRow(
        title = stringResource(R.string.tv_layout_title),
        description = stringResource(R.string.tv_layout_description),
        checked = on,
        onCheckedChange = { preferences.edit().putBoolean(TV_LAYOUT_PREFERENCE, it).apply() },
        testTag = "tv_layout_switch",
        enabled = television,
    )
    SettingsHint(stringResource(if (television) R.string.tv_layout_note else R.string.tv_layout_needs_tv))
    val searchVisible by rememberTvSearchVisible()
    SettingsSwitchRow(
        title = stringResource(R.string.tv_search_visible_title),
        description = stringResource(R.string.tv_search_visible_description),
        checked = searchVisible,
        onCheckedChange = { preferences.edit().putBoolean(TV_SETTINGS_SEARCH_PREFERENCE, it).apply() },
        testTag = "tv_search_visible_switch",
        enabled = television,
    )
}

/**
 * A clear ring around the focused item, so a remote's focus can be followed from the sofa. Put it
 * before the item's `clickable` or `focusable`; it draws nothing outside the TV layout.
 */
internal fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier =
    composed {
        if (!LocalTvLayout.current) return@composed Modifier
        var focused by remember { mutableStateOf(false) }
        // Not the accent: the playing subtitle line already has an accent border.
        val color = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent
        Modifier.onFocusChanged { focused = it.hasFocus }.border(3.dp, color, shape)
    }

/** A filled button's focus ring in the TV layout: its own shading barely changes on focus. */
internal fun Modifier.tvButtonFocusRing(): Modifier = tvFocusRing(RoundedCornerShape(percent = 50))

/** In the TV layout the remote starts on this item, so the screen opens with something focused. */
internal fun Modifier.tvStartFocus(): Modifier =
    composed {
        if (!LocalTvLayout.current) return@composed Modifier
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Modifier.focusRequester(focus)
    }

/** Text size moves 5% a press on a TV remote, so it can land on 100% again; 0 (smooth) elsewhere. */
internal fun fontScaleSteps(tvLayout: Boolean): Int =
    if (tvLayout) ((MAX_FONT_SCALE - MIN_FONT_SCALE) / TV_FONT_SCALE_STEP).roundToInt() - 1 else 0

private const val TV_FONT_SCALE_STEP = 0.05f

/**
 * ▲ and ▼ leave a slider or a text field in the TV layout, instead of changing the value or moving
 * the cursor, so the remote can reach what is above and below it. Every text field takes ▲/▼ for its
 * cursor (Compose maps them in single-line fields too) and a Material slider takes them as value
 * steps, so each needs this. ◀ and ▶ stay with the slider and the text. The on-screen keyboard, when
 * open, gets the keys before this and keeps its own navigation; Back closes it.
 */

internal fun Modifier.tvArrowsLeave(sequentialFallback: Boolean = true): Modifier =
    composed {
        if (!LocalTvLayout.current) return@composed Modifier
        val focusManager = LocalFocusManager.current
        // The press that moved focus is the press whose release is taken too; nothing else is held back.
        var tookDown by remember { mutableStateOf(false) }
        Modifier.onPreviewKeyEvent { event ->
            val direction = tvArrowDirection(event.key) ?: return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    tookDown = tvLeaveAttempts(direction, sequentialFallback).any { focusManager.moveFocus(it) }
                    tookDown
                }
                KeyEventType.KeyUp -> tookDown.also { tookDown = false }
                else -> false
            }
        }
    }

/**
 * The moves tried, in order, to take ▲ / ▼ out of a field or slider: the spatial move first, then, when
 * that finds no candidate, the next or previous control in the page's focus order (which wraps at the
 * page's ends, so the first control of a page asks for [sequentialFallback] = false). When none moves, the
 * press is not taken: that is the page's top or bottom, and the field or slider keeps its own arrow.
 */
internal fun tvLeaveAttempts(
    direction: FocusDirection,
    sequentialFallback: Boolean = true,
): List<FocusDirection> =
    when {
        !sequentialFallback -> listOf(direction)
        direction == FocusDirection.Up -> listOf(FocusDirection.Up, FocusDirection.Previous)
        else -> listOf(FocusDirection.Down, FocusDirection.Next)
    }

/** The focus direction of ▲ / ▼, the only arrows [tvArrowsLeave] hands to focus; null for any other key. */
internal fun tvArrowDirection(key: Key): FocusDirection? =
    when (key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> null
    }

/**
 * Material's focus shading, strong enough to see across a room in the TV layout. It is always
 * provided, so turning the layout on or off keeps the screen's state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun tvFocusShading(tvLayout: Boolean): ProvidedValue<RippleConfiguration?> =
    LocalRippleConfiguration provides if (tvLayout) tvRippleConfiguration() else LocalRippleConfiguration.current

@OptIn(ExperimentalMaterial3Api::class)
private fun tvRippleConfiguration(): RippleConfiguration =
    RippleConfiguration(
        rippleAlpha =
            RippleAlpha(
                draggedAlpha = TV_STATE_ALPHA,
                focusedAlpha = TV_STATE_ALPHA,
                hoveredAlpha = TV_STATE_ALPHA,
                pressedAlpha = TV_STATE_ALPHA,
            ),
    )

private const val TV_STATE_ALPHA = 0.32f
