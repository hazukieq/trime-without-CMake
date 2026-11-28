package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class PanelNavigationTest {
    companion object { @JvmStatic @BeforeClass fun load() { OfflineDictionary.load() } }

    private fun snapshot(code: String, raw: String = code): TwelveKeySnapshot =
        TwelveKeySnapshotBuilder.build(
            TwelveKeySnapshotBuilder.Local(code, 0, code, "", raw),
            applyLearning = false, candidatesOverride = emptyList(),
        )

    /** 12 鍵：左列是確認行、右列固定推薦組；導航/預覽都不消費原碼。 */
    @Test fun leftConfirmRowsAreDecoupledFromTheRightGrid() {
        val original = "9aii48i5i73"
        val snapshot = snapshot(original)
        val navigation = PanelNavigation()
        navigation.submit(snapshot)
        assertEquals("右列固定推薦組", RECOMMEND_SEGMENT, navigation.groups.first().segment)
        assertTrue("右列應有候選", navigation.groups.first().items.isNotEmpty())
        // 左列只有確認行：碼前綴命中的完整音節 + 下一鍵的字母；不再有詞行/單字組。
        assertTrue(navigation.groups.any { it.segment == INITIAL_LETTER_SEGMENT })
        assertTrue(navigation.groups.all { it.segment == RECOMMEND_SEGMENT || it.segment <= 0 })
        val session = CompositionSession()
        session.input(original)
        val before = session.picks
        repeat(30) {
            navigation.select(0, "", snapshot.contextKey)
            PreeditPresentation.publish(snapshot, session.selectedText)
            PreeditPresentation.render("garbled display $it", session.remaining)
            PreeditPresentation.preview("garbled display $it", session.remaining)
            assertEquals(original, session.remaining)
            assertEquals(before, session.picks)
            assertNull(session.completed())
        }
    }

    @Test fun staleLeftClicksAreRejectedAndSubmitKeepsRecommendActive() {
        val navigation = PanelNavigation()
        val first = snapshot("i4")
        navigation.submit(first)
        val ho = navigation.groups.indexOfFirst { it.reading == "ho" }
        assertTrue("i4 的確認行應含 ho 音節", ho >= 0)
        assertTrue(navigation.select(ho, "ho", first.contextKey))
        assertEquals("ho", navigation.groups[navigation.activeIndex].reading)
        assertFalse("舊 contextKey 的左列點擊必須拒絕", navigation.select(ho, "ho", "stale"))
        assertFalse(navigation.select(999, "ho", first.contextKey))
        // 新快照回推薦組；過去的索引不能跨快照生效。
        navigation.submit(snapshot("9ai"))
        assertEquals(RECOMMEND_SEGMENT, navigation.groups[navigation.activeIndex].segment)
        assertFalse(navigation.select(ho, "ho", first.contextKey))
    }

    @Test fun multiSyllableConfirmAdvancesPastNzinAndRetainsKoPaPhrase() {
        val bindings = mapOf(
            'A' to listOf("z", "j"),
            'B' to listOf("v", "u", "r"),
            'C' to listOf("m", "n", "o"),
            'D' to listOf("g", "h", "i"),
            'E' to listOf("d", "e", "f"),
            'F' to listOf("a", "b", "c"),
            'G' to listOf("nz"),
            'H' to listOf("k", "l"),
            'I' to listOf("q", "t"),
            'J' to listOf("p", "s"),
        )
        com.hazukieq.hkrime.core.TwelveKeyPreedit.updateTokenBindings(bindings)
        try {
            // zung(ABCD) fa(EF) nzin(GDC) min(CDC) kiung(HDBCD) fo(EC) get(DEI)
            val fullCode = "ABCDEFGDCCDCHDBCDECDEI"
            val session = CompositionSession()
            session.input(fullCode)
            for (syll in listOf("zung", "fa", "nzin", "min", "kiung", "fo", "get")) {
                assertTrue("確認音節 $syll 應成功推進", session.confirmPhonetic(syll, initialOnly = false))
            }
            assertEquals(listOf("zung", "fa", "nzin", "min", "kiung", "fo", "get"), session.phonetics.map { it.reading })
            assertEquals("", session.pending)

            // ko(HC) pa(JF): 兩段確認後仍應保留「可怕」(ko pa) 完整詞組推薦
            val kopaCode = "HCJF"
            val snap = TwelveKeySnapshotBuilder.build(
                TwelveKeySnapshotBuilder.Local(kopaCode, 0, kopaCode, "", kopaCode),
                wantGroups = true,
                candidatesOverride = listOf(
                    CandidateItem("可怕", "ko pa", 0, engineSpan = EngineCandidateSpan(0, 4, "ko pa")),
                ),
                phonetics = listOf(
                    PhoneticSelection("ko", "HC", initialOnly = false),
                    PhoneticSelection("pa", "JF", initialOnly = false),
                ),
            )
            assertTrue(snap.recommend.any { it.text == "可怕" })
            assertTrue(snap.barEntries.any { it.text == "可怕" })
        } finally {
            com.hazukieq.hkrime.core.TwelveKeyPreedit.updateTokenBindings(emptyMap())
        }
    }
}
