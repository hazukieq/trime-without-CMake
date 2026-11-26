package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** 過長編碼：找不到覆蓋全碼的詞時，按「每鍵第一個首拼單元」造一個組詞。 */
class LongCodeSynthTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun load() {
            OfflineDictionary.load()
        }
    }

    @Test
    fun synthTakesFirstInitialUnitPerKey() {
        val synth = TwelveKeySnapshotBuilder.synthesizeLongCode("5we")
        assertNotNull(synth)
        // kl→k、th 整段、d；reading 是單元串，text 是三個漢字。
        assertEquals("k th d", synth!!.comment)
        assertEquals(3, synth.text.length)
        assertTrue(synth.text.all { it.code in 0x3400..0x9FFF })
        assertEquals(SYNTHETIC_SEGMENT, synth.segment)
        // 組合聲母不拆：th 是單個單元。
        assertEquals(listOf("k", "th", "d"), synth.comment.split(" "))
    }

    @Test
    fun synthIsInsertedFirstWhenNoCandidateCoversWholeCode() {
        val code = "5we5we"
        val snap = TwelveKeySnapshotBuilder.build(
            TwelveKeySnapshotBuilder.Local(code, 0, code, "", code),
            applyLearning = false,
            candidatesOverride = emptyList(),
            wantGroups = false,
            mapFallbackLetters = true,
        )
        val first = snap.barItems.firstOrNull()
        assertNotNull("長碼無整串詞時必須有合成的組詞在條首", first)
        assertEquals(6, first!!.text.length)
        assertEquals("k th d k th d", first.comment)
        assertEquals(first.text, snap.previewText)
        // 右列推薦同樣以它開頭，preedit 不裸露原碼。
        assertEquals(first.text, snap.recommend.firstOrNull()?.text)
        assertFalse(snap.barItems.any { it.text == code })
    }

    @Test
    fun synthIsNotInsertedWhenAnEngineCandidateCoversWholeCode() {
        val code = "5we"
        val covered = CandidateItem(text = "看心的", comment = "k th d", index = 0)
        val snap = TwelveKeySnapshotBuilder.build(
            TwelveKeySnapshotBuilder.Local(code, 0, code, "", code),
            applyLearning = false,
            candidatesOverride = listOf(covered),
            wantGroups = false,
            mapFallbackLetters = true,
        )
        assertEquals("看心的", snap.barItems.firstOrNull()?.text)
        assertTrue(snap.barItems.none { it.segment == SYNTHETIC_SEGMENT })
    }
}
