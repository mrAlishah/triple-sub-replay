package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSlotLayoutTest {
    private val o = SubtitleSlot.ORIGINAL
    private val s = SubtitleSlot.SECOND
    private val t = SubtitleSlot.THIRD

    @Test
    fun defaultOrderIsOriginalThenSubtitleOneThenSubtitleTwo() {
        assertEquals(listOf(o, s, t), storedSubtitleOrder(null, TranslationPosition.BELOW))
    }

    @Test
    fun legacyAboveChoiceBecomesTheEquivalentOrderWithoutASavedOrder() {
        assertEquals(listOf(s, o, t), storedSubtitleOrder(null, TranslationPosition.ABOVE))
    }

    @Test
    fun aSavedOrderWinsOverTheLegacyChoice() {
        assertEquals(listOf(t, o, s), storedSubtitleOrder("third,original,second", TranslationPosition.ABOVE))
    }

    @Test
    fun everyPermutationRoundTripsThroughStorage() {
        val all =
            listOf(o, s, t).let { l ->
                l.flatMap { a -> l.filter { it != a }.flatMap { b -> l.filter { it != a && it != b }.map { c -> listOf(a, b, c) } } }
            }
        assertEquals(6, all.toSet().size)
        all.forEach { order -> assertEquals(order, parseSubtitleOrder(order.serializedOrder())) }
    }

    @Test
    fun invalidOrdersAreRejected() {
        listOf("", "original", "original,original,second", "original,second,fourth", "original,second,third,third", "x")
            .forEach { assertNull(it, parseSubtitleOrder(it)) }
        assertNull(parseSubtitleOrder(null))
    }

    @Test
    fun movingSwapsNeighboursAndStopsAtTheEnds() {
        val order = listOf(o, s, t)
        assertEquals(listOf(s, o, t), order.moved(o, 1))
        assertEquals(listOf(o, t, s), order.moved(t, -1))
        assertEquals(order, order.moved(o, -1))
        assertEquals(order, order.moved(t, 1))
        assertEquals(setOf(o, s, t), order.moved(s, 1).toSet())
    }

    @Test
    fun hiddenSlotsLeaveNoGapAndKeepTheirPlace() {
        val order = listOf(t, o, s)
        val visible = setOf(o, t)
        assertEquals(listOf(t, o), order.arranged { slot -> slot.takeIf { it in visible } })
        // Showing the hidden slot again restores its configured place; the order itself never changed.
        assertEquals(listOf(t, o, s), order.arranged { it })
    }

    @Test
    fun slotIdentityDoesNotDependOnPosition() {
        val moved = listOf(o, s, t).moved(t, -1).moved(t, -1)
        assertEquals(listOf(t, o, s), moved)
        assertEquals("third", t.stableId)
        assertEquals("font_scale_third", t.scalePreference)
        assertEquals("rtl_third", t.rtlPreference)
    }

    @Test
    fun sizesAreIndependentPerSlotAndClamped() {
        var scales = SlotFontScale()
        assertEquals(1f, scales[s], 0f)
        scales = scales.with(s, 1.3f).with(t, 0.1f).with(o, 9f)
        assertEquals(1.3f, scales[s], 0f)
        assertEquals(SLOT_SCALE_MIN, scales[t], 0f)
        assertEquals(SLOT_SCALE_MAX, scales[o], 0f)
        assertEquals(1f, normalizeSlotScale(Float.NaN), 0f)
    }

    @Test
    fun theNewPreferencesTravelWithSettingsResetAndBackup() {
        assertTrue(TRIPLE_SETTING_KEYS.containsAll(SLOT_LAYOUT_SETTING_KEYS))
        assertTrue(SUBTITLE_ORDER_PREFERENCE in TRIPLE_SETTING_KEYS)
    }

    @Test
    fun alignmentDefaultsToAutoAndIgnoresUnknownValues() {
        assertEquals(SubtitleAlignment.AUTO, storedSubtitleAlignment(null))
        assertEquals(SubtitleAlignment.AUTO, storedSubtitleAlignment("diagonal"))
        SubtitleAlignment.entries.forEach { assertEquals(it, storedSubtitleAlignment(it.storageValue)) }
        assertEquals(androidx.compose.ui.text.style.TextAlign.Unspecified, SubtitleAlignment.AUTO.textAlign())
        assertEquals(androidx.compose.ui.text.style.TextAlign.Left, SubtitleAlignment.LEFT.textAlign())
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, SubtitleAlignment.CENTER.textAlign())
        assertEquals(androidx.compose.ui.text.style.TextAlign.Right, SubtitleAlignment.RIGHT.textAlign())
        assertTrue(SubtitleSlot.entries.all { it.alignmentPreference in TRIPLE_SETTING_KEYS })
        assertEquals("subtitle_alignment_second", SubtitleSlot.SECOND.alignmentPreference)
        val perSlot = SlotAlignment().with(SubtitleSlot.THIRD, SubtitleAlignment.RIGHT)
        assertEquals(SubtitleAlignment.AUTO, perSlot[SubtitleSlot.ORIGINAL])
        assertEquals(SubtitleAlignment.RIGHT, perSlot[SubtitleSlot.THIRD])
    }
}
