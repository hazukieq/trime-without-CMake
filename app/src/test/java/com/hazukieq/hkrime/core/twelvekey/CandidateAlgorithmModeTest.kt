package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class CandidateAlgorithmModeTest {
    companion object { @JvmStatic @BeforeClass fun load() { OfflineDictionary.load() } }
    private val code = "7i4aa4"
    private val local = TwelveKeySnapshotBuilder.Local(code, 0, code, "", "7i4 aa4‸")
    private fun candidate(text: String, reading: String, index: Int, end: Int) =
        CandidateItem(text, reading, index, engineSpan = EngineCandidateSpan(0, end, reading))

    @Test fun disabledModePreservesEngineOrderDuplicatesAndNativeSpans() {
        val native = listOf(candidate("肾", "sin", 4, 3), candidate("平板", "pin ban", 7, 6),
            candidate("平板", "sin ban", 9, 6))
        val off = TwelveKeySnapshotBuilder.build(local, candidatesOverride = native, appRankingEnabled = false)
        assertEquals(native.map { it.index }, off.barItems.map { it.index })
        assertEquals(listOf("肾", "平板", "平板"), off.barItems.map { it.text })
        assertEquals(native.map { it.engineSpan }, off.barItems.map { it.engineSpan })
        assertEquals(listOf(3, 6, 6), off.barItems.map { it.inputSpan?.end })
        assertEquals(off.barItems, off.recommend)
        assertTrue(off.groups.flatMap { it.items }.all { it.index >= 0 })
        assertEquals("肾", off.previewText)
        PreeditPresentation.publish(off, "")
        assertEquals("sin aa4", PreeditPresentation.render(local.rawPreedit, code))
        val on = TwelveKeySnapshotBuilder.build(local, candidatesOverride = native)
        assertEquals("平板", on.barItems.first().text)
    }

    @Test fun disabledModeNeverFillsAnEmptyEngineWithDictionaryOrRawCandidates() {
        val empty = TwelveKeySnapshotBuilder.build(local, candidatesOverride = emptyList(), appRankingEnabled = false)
        assertTrue(empty.barItems.isEmpty())
        assertTrue(empty.groups.isEmpty())
        assertTrue(empty.recommend.isEmpty())
        assertNull(empty.fallback)
        assertFalse(empty.hasCandidates)
        assertEquals(code, empty.remaining)
    }

    @Test fun openingThePanelKeepsTheSameRawTwelveCandidatesAndPublishesEveryEngineItem() {
        val native = List(90) { i -> candidate("候选${i / 2}", "pin ban", 10 + i, 6) }
        val bar = TwelveKeySnapshotBuilder.build(local, 64, wantGroups = false, candidatesOverride = native, appRankingEnabled = false)
        val full = TwelveKeySnapshotBuilder.build(local, candidatesOverride = native, appRankingEnabled = false)
        assertEquals(native.take(12).map { it.index }, bar.barItems.map { it.index })
        assertEquals(bar.barItems, full.barItems)
        assertEquals(bar.previewText, full.previewText)
        assertEquals(90, full.recommend.size)
        assertTrue(bar.groups.isEmpty())
        assertEquals(full.recommend, PanelNavigation().also { it.submit(full) }.groups.first().items)
    }

    @Test fun foldedEngineGroupsRetainGlobalOrderAndDuplicateTexts() {
        val native = listOf(candidate("平板", "pin ban", 3, 6), candidate("平", "pin", 5, 3),
            candidate("平板", "pin ban", 8, 6))
        val snapshot = TwelveKeySnapshotBuilder.build(local, candidatesOverride = native, appRankingEnabled = false)
        val navigation = PanelNavigation().also { it.submit(snapshot) }
        val pin = navigation.groups.first { it.reading == "pin" }
        assertEquals(listOf(3, 5, 8), pin.items.map { it.index })
        assertEquals(listOf("平板", "平", "平板"), pin.items.map { it.text })
    }

    @Test fun rankingModesHaveSeparateCacheKeys() {
        assertNotEquals(TwelveKeySnapshotBuilder.cacheKey(local, false),
            TwelveKeySnapshotBuilder.cacheKey(local, false, appRankingEnabled = false))
        assertNotEquals(TwelveKeySnapshotBuilder.cacheKey(local, true),
            TwelveKeySnapshotBuilder.cacheKey(local, true, appRankingEnabled = false))
    }
}
