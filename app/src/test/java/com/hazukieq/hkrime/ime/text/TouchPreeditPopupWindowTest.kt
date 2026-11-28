package com.hazukieq.hkrime.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPreeditPopupWindowTest {

    @Test
    fun emptyOrNullCompositionProducesEmptyState() {
        assertTrue(TouchPreeditPopupWindow.resolveDisplayState(null, 0, 0, false, null).isEmpty)
        assertTrue(TouchPreeditPopupWindow.resolveDisplayState("", 0, 0, true, "ni hao").isEmpty)
    }

    @Test
    fun twelveKeyAndQwertyPreservePreeditWithoutRematerializingFromComment() {
        val state = TouchPreeditPopupWindow.resolveDisplayState(
            rawText = "已選go loi",
            rawStart = 2,
            rawEnd = 8,
            materializeFromCandidate = false,
            topCandidateComment = "ignored",
        )
        assertEquals("已選go loi", state.text)
        assertEquals(2, state.highlightStart)
        assertEquals(8, state.highlightEnd)
    }

    @Test
    fun nonTwelveKeyMultiKeyMaterializesActiveSegmentFromCandidateComment() {
        val state = TouchPreeditPopupWindow.resolveDisplayState(
            rawText = "你646 5",
            rawStart = 1,
            rawEnd = 6,
            materializeFromCandidate = true,
            topCandidateComment = "min jian",
        )
        assertEquals("你min j", state.text)
        assertEquals(1, state.highlightStart)
        assertEquals(6, state.highlightEnd)
    }

    @Test
    fun ignoresPlaceholderCommentsAndClampsOutOfRangeBounds() {
        val state = TouchPreeditPopupWindow.resolveDisplayState(
            rawText = "abc",
            rawStart = -4,
            rawEnd = 99,
            materializeFromCandidate = true,
            topCandidateComment = "~abc",
        )
        assertEquals("abc", state.text)
        assertEquals(0, state.highlightStart)
        assertEquals(3, state.highlightEnd)
    }
}
