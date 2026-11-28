package com.hazukieq.hkrime.ime.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCandidateOverlayTest {

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

    @Test
    fun detectsHostInterceptedTabOnlyWhenOverlayOpenAndDownMissing() {
        // 正常應用（如 Obsidian）：onKeyDown 已消費 Tab，不應誤報衝突
        assertFalse(
            HardwareKeyboardController.isOrphanHostInterceptedTabUp(
                keyCode = android.view.KeyEvent.KEYCODE_TAB,
                wasConsumedOnDown = true,
                hasActiveToolOverlay = true,
            ),
        )
        // 搶佔應用（如富文本筆記）：浮窗開啟卻只收到孤立 ACTION_UP -> 觸發衝突提示
        assertTrue(
            HardwareKeyboardController.isOrphanHostInterceptedTabUp(
                keyCode = android.view.KeyEvent.KEYCODE_TAB,
                wasConsumedOnDown = false,
                hasActiveToolOverlay = true,
            ),
        )
        // 未開啟工具浮窗時不提示
        assertFalse(
            HardwareKeyboardController.isOrphanHostInterceptedTabUp(
                keyCode = android.view.KeyEvent.KEYCODE_TAB,
                wasConsumedOnDown = false,
                hasActiveToolOverlay = false,
            ),
        )
    }

    @Test
    fun resolveToolShortcutUsesAdjacentCtrlShiftPunctuationAndRetiresLegacyKeys() {
        assertEquals(
            HardwareKeyboardController.TOOL_SYMBOL,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_COMMA,
                isCtrl = true,
                isShift = true,
                isAlt = false,
            ),
        )
        assertEquals(
            HardwareKeyboardController.TOOL_CLIPBOARD,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_PERIOD,
                isCtrl = true,
                isShift = true,
                isAlt = false,
            ),
        )
        assertEquals(
            HardwareKeyboardController.TOOL_MENU,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_SLASH,
                isCtrl = true,
                isShift = true,
                isAlt = false,
            ),
        )
        // 舊快捷鍵全部棄用，歸還宿主應用
        assertEquals(
            null,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_E,
                isCtrl = true,
                isShift = true,
                isAlt = false,
            ),
        )
        assertEquals(
            null,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_V,
                isCtrl = true,
                isShift = true,
                isAlt = false,
            ),
        )
        assertEquals(
            null,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_M,
                isCtrl = true,
                isShift = true,
                isAlt = false,
            ),
        )
        // 單純 Ctrl+. 留給 Rime 中英標點切換，不誤觸剪切板
        assertEquals(
            null,
            HardwareKeyboardController.resolveToolShortcut(
                android.view.KeyEvent.KEYCODE_PERIOD,
                isCtrl = true,
                isShift = false,
                isAlt = false,
            ),
        )
    }
}
