package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDisplayFoldTest {
    private fun char(text: String) = CandidateItem(text = text, comment = "man", index = -1, segment = 0)
    private fun word(text: String, reading: String) =
        CandidateItem(text = text, comment = reading, index = -1, segment = 1)

    @Test
    fun associationWordRowFoldsIntoFirstSyllableGroup() {
        val groups = listOf(
            SyllableGroup("man", listOf("man"), listOf(char("慢"), char("曼")), segment = 0),
            SyllableGroup(
                "man jiu",
                listOf("man", "jiu"),
                listOf(word("慢走", "man jiu"), word("曼久", "man jiu")),
                segment = 1,
            ),
            SyllableGroup("bau cam", listOf("bau", "cam"), listOf(word("报站", "bau cam")), segment = 1),
            // 引擎多音节组 segment=-1 同样要折进首 syllable
            SyllableGroup(
                "man jun",
                listOf("man", "jun"),
                listOf(word("慢進", "man jun")),
                segment = -1,
            ),
        )
        val display = foldGroupsForDisplay(groups)

        assertEquals(listOf("man", "bau"), display.map { it.reading })
        val man = display.first { it.reading == "man" }
        assertTrue(man.items.any { it.text == "慢" })
        assertTrue("联想词应并入 man：${man.items.map { it.text }}", man.items.any { it.text == "慢走" })
        val bau = display.first { it.reading == "bau" }
        assertTrue(bau.items.any { it.text == "报站" })
        assertTrue("引擎词行也应折入：${man.items.map { it.text }}", man.items.any { it.text == "慢進" })
        // 字在詞前（逐段選字先要這一音節的字；詞行折在後面）
        val firstChar = man.items.indexOfFirst { it.segment == 0 }
        val firstWord = man.items.indexOfFirst { it.segment != 0 }
        assertTrue(
            "字應在詞前：${man.items.map { it.text }}",
            firstChar >= 0 && firstWord >= 0 && firstChar < firstWord,
        )
    }

    @Test
    fun isolatedSyllableRowsAreNotFolded() {
        val groups = listOf(
            SyllableGroup("d", emptyList(), listOf(CandidateItem("da", "da", -1, segment = ISOLATED_SEGMENT))),
            SyllableGroup("man", listOf("man"), listOf(char("慢")), segment = 0),
            SyllableGroup("man jiu", listOf("man", "jiu"), listOf(word("慢走", "man jiu")), segment = 1),
        )
        val display = foldGroupsForDisplay(groups)

        assertEquals(listOf("d", "man"), display.map { it.reading })
        assertTrue(display.first { it.reading == "d" }.items.any { it.text == "da" })
    }
}
