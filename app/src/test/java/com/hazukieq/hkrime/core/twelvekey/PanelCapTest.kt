package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCapTest {
    private fun engineRow(index: Int): PanelRow = PanelRow(
        reading = "eng$index",
        items = listOf(CandidateItem(text = "e$index", comment = "eng$index", index = index)),
        segment = 1,
        sources = setOf(RowSource.ENGINE),
    )

    private fun comboRow(index: Int): PanelRow = PanelRow(
        reading = "jen cin kung ze",
        items = listOf(CandidateItem(text = "远程控制", comment = "jen cin kung ze", index = -1)),
        segment = 1,
        sources = setOf(RowSource.INITIAL_COMBO),
    )

    @Test
    fun capKeepsAppSideRowsWhenEngineFillsThePanel() {
        val rows = (0 until 200).map(::engineRow) + (0 until 8).map(::comboRow)
        val capped = capRows(rows)

        assertEquals(TwelveKeyTuning.MAX_GROUPS, capped.size)
        assertEquals(8, capped.count { RowSource.INITIAL_COMBO in it.sources })
        assertTrue(capped.any { row -> row.items.any { it.text == "远程控制" } })
    }
}
