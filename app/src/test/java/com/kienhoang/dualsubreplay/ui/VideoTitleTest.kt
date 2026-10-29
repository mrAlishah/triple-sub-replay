package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTitleTest {
    private val state = DualSubUiState(activeVideoId = "bbbbbbbbbbb", videoTitle = VideoTitle("aaaaaaaaaaa", "Previous video"))

    @Test
    fun aTitleIsOnlyEverGivenToItsOwnVideo() {
        assertNull(state.titleOf("bbbbbbbbbbb"))
        assertNull(state.titleOf(null))
        assertEquals("Previous video", state.titleOf("aaaaaaaaaaa"))
        assertNull(DualSubUiState().titleOf("aaaaaaaaaaa"))
    }
}
