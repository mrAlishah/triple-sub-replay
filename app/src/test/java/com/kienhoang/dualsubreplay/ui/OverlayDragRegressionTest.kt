package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragRegressionTest {
    @Test
    fun horizontalOverlayDragTracksFingerOneToOne() {
        val parentWidthPx = 1_000
        val overlayWidthPx = 400
        val fingerDeltaPx = 60f
        val travelPx = (parentWidthPx - overlayWidthPx).toFloat()
        val startPosition = 0.5f

        val newPosition =
            normalizeOverlayHorizontalPosition(
                startPosition + fingerDeltaPx / travelPx,
            )
        val startShiftPx =
            overlayHorizontalShiftPx(
                overlayHorizontalShiftFraction(startPosition),
                parentWidthPx,
                overlayWidthPx,
            )
        val endShiftPx =
            overlayHorizontalShiftPx(
                overlayHorizontalShiftFraction(newPosition),
                parentWidthPx,
                overlayWidthPx,
            )

        assertEquals(fingerDeltaPx.toInt(), endShiftPx - startShiftPx)
    }

    @Test
    fun fullscreenLinesFitTheTextUpToMostOfTheScreen() {
        // A 900dp-wide landscape phone: 80% is 720dp, minus the box's side padding.
        assertEquals(692, fullscreenOverlayTextMaxWidthDp(900, actionsVisible = false))
        // The settings and hide buttons take their room when shown.
        assertEquals(608, fullscreenOverlayTextMaxWidthDp(900, actionsVisible = true))
        // Re-translate is a third 48dp button, so the text gives up one more button's room.
        assertEquals(560, fullscreenOverlayTextMaxWidthDp(900, actionsVisible = true, buttons = 3))
        // Very wide screens keep the regular 720dp cap.
        assertEquals(692, fullscreenOverlayTextMaxWidthDp(2000, actionsVisible = false))
    }
}
