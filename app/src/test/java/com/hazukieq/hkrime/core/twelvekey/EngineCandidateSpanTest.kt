package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.PackedCandidates
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class EngineCandidateSpanTest {
    companion object { @JvmStatic @BeforeClass fun load() { OfflineDictionary.load() } }

    @Test fun packedRecordsPreserveEmojiMetadataAndLegacyOrder() {
        val rows = PackedCandidates.parse("加油\u0001ga jiu\u0002💪\u0001〔加油〕\u00010\u00015\u0001ga jiu\u0002空说明\u0001\u00010\u00011\u0001\u0002")
        assertEquals(3, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals("💪", rows[1][0])
        assertEquals(EngineCandidateSpan(0, 5, "ga jiu"), EngineCandidateSpan.fromRow(rows[1]))
        assertEquals("", rows[2][4])
        assertTrue(PackedCandidates.parse(null).isEmpty())
        assertTrue(PackedCandidates.parse("truncated").isEmpty())
    }

    @Test fun nativeRangeOutranksDisplayCommentAndKeepsTailAndUndo() {
        val code = "ia8i39ai"
        for (label in listOf("〔加油〕", "[加油]", "", "unknown translator hint")) {
            val item = CandidateSelection.bind(CandidateItem("💪", label, 3,
                engineSpan = EngineCandidateSpan(0, 5, "ga jiu")), code)
            assertEquals(InputCodeSpan(code, 5, "ga jiu"), item.inputSpan)
            val session = CompositionSession()
            session.input(code)
            assertTrue(session.pick(item.text, item.inputSpan!!.reading,
                CandidateSelection.consumedEnd(item, code, CompositionSession.Origin.PANEL)!!,
                CompositionSession.Origin.PANEL))
            assertEquals("9ai", session.remaining)
            assertNull(session.completed())
            assertFalse(session.allowsCommit("💪"))
            assertEquals(code, session.undo())
        }
    }

    @Test fun nativeRangeNeedsExactStartValidEndAndAnEngineIndex() {
        val binder = CandidateSelection.InputBinder("ia8i3", 3)
        val item = CandidateItem("💪", "〔加油〕", 0, engineSpan = EngineCandidateSpan(3, 8, "ga jiu"))
        assertEquals(InputCodeSpan("ia8i3", 5, "ga jiu"), binder.bind(item).inputSpan)
        for (span in listOf(EngineCandidateSpan(0, 5, "ga jiu"), EngineCandidateSpan(3, 3, "ga jiu"),
            EngineCandidateSpan(3, 9, "ga jiu"), EngineCandidateSpan(-1, -1, "ga jiu"))) {
            assertNull(binder.bind(item.copy(engineSpan = span)).inputSpan)
        }
        assertNull(binder.bind(item.copy(index = -1)).inputSpan)
    }

    @Test fun unknownTranslatorReadingCanSelectButCannotBecomePhoneticLearning() {
        val item = CandidateSelection.bind(CandidateItem("💪", "〔加油〕", 0,
            engineSpan = EngineCandidateSpan(0, 5, "〔加油〕")), "ia8i3")
        assertEquals(InputCodeSpan("ia8i3", 5, ""), item.inputSpan)
        val legacy = item.copy(engineSpan = null, inputSpan = null)
        assertNull(CandidateSelection.bind(legacy, "ia8i3").inputSpan)
    }
}
