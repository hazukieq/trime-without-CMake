package com.hazukieq.hkrime.ime.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCandidatePopupTest {

    @Test
    fun enginePageReflectsRimeMenuState() {
        val page0 = HardwareEnginePage(
            items = listOf(
                HardwareEnginePage.Item("你好", "ni hao"),
                HardwareEnginePage.Item("擬好", "ni hao"),
                HardwareEnginePage.Item("尼好", "ni hao"),
            ),
            highlightedIndex = 1,
            pageNo = 0,
            isLastPage = false,
            selectLabels = listOf("1", "2", "3"),
        )
        assertFalse(page0.isEmpty)
        assertTrue(page0.hasMultiplePages)
        assertEquals("1", page0.pageSummary())
        assertEquals("2", page0.labelForSlot(1))
        assertEquals("4", page0.labelForSlot(3)) // fallback when selectLabels shorter

        val singlePage = HardwareEnginePage(
            items = listOf(HardwareEnginePage.Item("好", "hao")),
            highlightedIndex = 0,
            pageNo = 0,
            isLastPage = true,
        )
        assertFalse(singlePage.hasMultiplePages)
        assertEquals("", singlePage.pageSummary())
        assertEquals("1", singlePage.labelForSlot(0))
    }

    @Test
    fun preeditHoldsCaretAndSelectionBounds() {
        val preedit = HardwarePreedit(
            text = "ni hao",
            selStart = 0,
            selEnd = 6,
            cursorPos = 3,
        )
        assertEquals("ni hao", preedit.text)
        assertEquals(3, preedit.cursorPos)
        assertEquals(0, preedit.selStart)
        assertEquals(6, preedit.selEnd)
    }
}
