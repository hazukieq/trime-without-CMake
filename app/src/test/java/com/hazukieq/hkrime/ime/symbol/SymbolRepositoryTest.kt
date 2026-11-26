package com.hazukieq.hkrime.ime.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymbolRepositoryTest {

    @Test
    fun parseWhitespaceSeparatedSymbolsAndAutoBadge() {
        val cat = SymbolRepository.parseTxt(
            "01_常用.txt",
            """
            # 常用符号
            ， 。 ？ ！
            , : .com :)
            """.trimIndent(),
        )
        assertEquals("常用", cat.title)
        assertEquals("01_常用", cat.sortKey)
        assertEquals(listOf("，", "。", "？", "！", ",", ":", ".com", ":)"), cat.items.map { it.text })
        assertNull(cat.items[0].badge)
        assertEquals("英", cat.items[4].badge) // ","
        assertEquals("英", cat.items[5].badge) // ":"
        assertNull(cat.items[6].badge) // ".com"
    }

    @Test
    fun parseLineSplitKaomojiWithSpaces() {
        val cat = SymbolRepository.parseTxt(
            "08_颜文.txt",
            """
            # split: line
            # 注释行
            ٩( 'ω' )و
            ╰(*´︶`*)╯
            """.trimIndent(),
        )
        assertEquals("颜文", cat.title)
        assertEquals(listOf("٩( 'ω' )و", "╰(*´︶`*)╯"), cat.items.map { it.text })
    }

    @Test
    fun parseCustomUserCategoryWithoutNumberPrefix() {
        val cat = SymbolRepository.parseTxt("网络热词.txt", "666 +1 yyds")
        assertEquals("网络热词", cat.title)
        assertEquals(listOf("666", "+1", "yyds"), cat.items.map { it.text })
    }

    @Test
    fun mapSelectToInitialTabRoutesLegacyTargets() {
        assertEquals("常用", SymbolPanelController.mapSelectToInitialTab("symbols"))
        assertEquals("常用", SymbolPanelController.mapSelectToInitialTab("bqfh1"))
        assertEquals("英文", SymbolPanelController.mapSelectToInitialTab("bqfh3"))
        assertEquals("表情", SymbolPanelController.mapSelectToInitialTab("emoji"))
        assertEquals("表情", SymbolPanelController.mapSelectToInitialTab("bqrw1"))
        assertEquals("颜文", SymbolPanelController.mapSelectToInitialTab("kaomoji"))
        assertEquals("颜文", SymbolPanelController.mapSelectToInitialTab("kao_happy"))
        assertNull(SymbolPanelController.mapSelectToInitialTab("number"))
        assertNull(SymbolPanelController.mapSelectToInitialTab("edit"))
        assertNull(SymbolPanelController.mapSelectToInitialTab("jiugong_simp"))
    }

    @Test
    fun applyOrderAndFilterReordersHidesAndNeverReturnsEmpty() {
        val cats = listOf(
            SymbolRepository.parseTxt("01_常用.txt", "， 。"),
            SymbolRepository.parseTxt("02_中文.txt", "？ ！"),
            SymbolRepository.parseTxt("03_表情.txt", "😀 😁"),
            SymbolRepository.parseTxt("04_网络.txt", ".com .cn"),
        )

        val reordered = SymbolRepository.applyOrderAndFilter(
            categories = cats,
            order = listOf("表情", "网络", "常用"),
            hidden = setOf("中文"),
            includeHidden = false,
        )
        assertEquals(listOf("表情", "网络", "常用"), reordered.map { it.title })

        val allOrdered = SymbolRepository.applyOrderAndFilter(
            categories = cats,
            order = listOf("表情", "网络"),
            hidden = setOf("中文"),
            includeHidden = true,
        )
        assertEquals(listOf("表情", "网络", "常用", "中文"), allOrdered.map { it.title })

        // Even if everything is hidden, fallback keeps the first ordered tab
        val fallback = SymbolRepository.applyOrderAndFilter(
            categories = cats,
            order = listOf("表情"),
            hidden = setOf("常用", "中文", "表情", "网络"),
            includeHidden = false,
        )
        assertEquals(listOf("表情"), fallback.map { it.title })
    }
}
