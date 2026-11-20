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

    @Test fun leftNavigationAndPreeditRenderingCannotConsumeOrCommitTheRemainingCode() {
        val original = "9aii48i5i73"
        val session = CompositionSession()
        session.input(original)
        val tai = CandidateSelection.bind(CandidateItem("太", "tai", 0), original)
        assertTrue(session.pick(tai.text, tai.inputSpan!!.reading,
            CandidateSelection.consumedEnd(tai, original, CompositionSession.Origin.BAR)!!,
            CompositionSession.Origin.BAR))
        assertEquals("i48i5i73", session.remaining)
        // 任意顯示字串都不能作為左欄切分來源。
        val next = snapshot(session.remaining, "太 ［wrong display］")
        val navigation = PanelNavigation()
        navigation.submit(next)
        val before = session.picks
        PreeditPresentation.publish(next, session.selectedText)
        val go = navigation.groups.indexOfFirst { it.reading == "go" }
        val ho = navigation.groups.indexOfFirst { it.reading == "ho" }
        assertTrue("必須存在 go/ho 原碼分組", go >= 0 && ho >= 0)
        repeat(30) {
            for (index in listOf(go, ho)) {
                navigation.select(index, navigation.groups[index].reading, next.contextKey)
                PreeditPresentation.render("garbled display $it", session.remaining)
                PreeditPresentation.preview("garbled display $it", session.remaining)
                assertEquals("i48i5i73", session.remaining)
                assertEquals("太", session.selectedText)
                assertEquals(before, session.picks)
                assertNull(session.completed())
            }
        }
        val item = navigation.groups[go].items.first { it.segment == 0 && it.comment == "go" }
        val end = CandidateSelection.consumedEnd(item, session.remaining, CompositionSession.Origin.PANEL)!!
        assertEquals(2, end)
        assertTrue(session.pick(item.text, item.inputSpan!!.reading, end, CompositionSession.Origin.PANEL))
        assertEquals("8i5i73", session.remaining)
        assertEquals("i48i5i73", session.undo())
        assertEquals(original, session.undo())
    }

    @Test fun staleLeftClicksAreRejectedAndNavigationTracksRemainingCode() {
        val navigation = PanelNavigation()
        val first = snapshot("i4")
        navigation.submit(first)
        val ho = navigation.groups.indexOfFirst { it.reading == "ho" }
        assertTrue(ho >= 0)
        navigation.select(ho, "ho", first.contextKey)
        // 已選前綴改變而剩餘碼相同，不應重設左欄。
        navigation.submit(first.copy(input = "太" + first.input))
        assertEquals("ho", navigation.groups[navigation.activeIndex].reading)
        navigation.submit(snapshot("9ai"))
        val before = navigation.activeIndex
        assertFalse(navigation.select(ho, "ho", first.contextKey))
        assertFalse(navigation.select(999, "ho", first.contextKey))
        assertEquals(before, navigation.activeIndex)
    }
}
