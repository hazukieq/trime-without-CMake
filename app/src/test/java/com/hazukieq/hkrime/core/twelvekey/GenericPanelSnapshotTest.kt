package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPanelSnapshotTest {

    @Test
    fun mandarinRimeIceGroupsByPreeditSpanAndKeepsLeftRailVisible() {
        val local = TwelveKeySnapshotBuilder.Local(
            input = "nihao",
            consumedEnd = 0,
            remaining = "nihao",
            segmentsKey = "",
            rawPreedit = "ni hao",
        )
        val engineCandidates = listOf(
            CandidateItem("你好", "", 0, engineSpan = EngineCandidateSpan(0, 5, "")),
            CandidateItem("拟好", "", 1, engineSpan = EngineCandidateSpan(0, 5, "")),
            CandidateItem("你", "", 2, engineSpan = EngineCandidateSpan(0, 2, "")),
            CandidateItem("泥", "", 3, engineSpan = EngineCandidateSpan(0, 2, "")),
        )
        val snap = GenericPanelSnapshotBuilder.build(
            local = local,
            candidatesOverride = engineCandidates,
        )

        assertFalse(snap.phoneticNavigation)
        assertTrue(snap.engineOrderOnly)
        assertEquals(listOf("全部", "ni hao", "ni"), snap.groups.map { it.reading })
        assertEquals(listOf("你好", "拟好"), snap.groups[1].items.map { it.text })
        assertEquals(listOf("你", "泥"), snap.groups[2].items.map { it.text })
    }

    @Test
    fun ipaCompletionSuffixCommentsDoNotExplodeLeftRailIntoSingleItemGroups() {
        val local = TwelveKeySnapshotBuilder.Local(
            input = "n",
            consumedEnd = 0,
            remaining = "n",
            segmentsKey = "",
            rawPreedit = "n",
        )
        val engineCandidates = listOf(
            CandidateItem("n", "", 0, engineSpan = EngineCandidateSpan(0, 1, "")),
            CandidateItem("ŋ", "g", 1, engineSpan = EngineCandidateSpan(0, 1, "g")),
            CandidateItem("ȵ", "j", 2, engineSpan = EngineCandidateSpan(0, 1, "j")),
            CandidateItem("ɲ", "n", 3, engineSpan = EngineCandidateSpan(0, 1, "n")),
            CandidateItem("ⁿ", "q", 4, engineSpan = EngineCandidateSpan(0, 1, "q")),
        )
        val snap = GenericPanelSnapshotBuilder.build(
            local = local,
            candidatesOverride = engineCandidates,
        )

        assertEquals(listOf("n"), snap.groups.map { it.reading })
        assertEquals(5, snap.groups[0].items.size)
        assertEquals(
            listOf("n", "ng", "nj", "nn", "nq"),
            snap.groups[0].items.map { it.comment },
        )
    }

    @Test
    fun phoneticCommentSchemaGroupsBySharedReadings() {
        val local = TwelveKeySnapshotBuilder.Local(
            input = "64",
            consumedEnd = 0,
            remaining = "64",
            segmentsKey = "",
            rawPreedit = "64",
        )
        val engineCandidates = listOf(
            CandidateItem("你", "ni", 0, engineSpan = EngineCandidateSpan(0, 2, "ni")),
            CandidateItem("泥", "ni", 1, engineSpan = EngineCandidateSpan(0, 2, "ni")),
            CandidateItem("米", "mi", 2, engineSpan = EngineCandidateSpan(0, 2, "mi")),
            CandidateItem("迷", "mi", 3, engineSpan = EngineCandidateSpan(0, 2, "mi")),
        )
        val snap = GenericPanelSnapshotBuilder.build(
            local = local,
            candidatesOverride = engineCandidates,
        )

        assertEquals(listOf("全部", "ni", "mi"), snap.groups.map { it.reading })
    }

    @Test
    fun tildeCompletionSuffixKeepsRailLabelAndExpandsRightCardToFullCode() {
        val local = TwelveKeySnapshotBuilder.Local(
            input = "pa",
            consumedEnd = 0,
            remaining = "pa",
            segmentsKey = "",
            rawPreedit = "pa",
        )
        val engineCandidates = listOf(
            CandidateItem("𢥣", "~'ji", 0, engineSpan = EngineCandidateSpan(0, 2, "~'ji")),
            CandidateItem("甲", "~i'mu", 1, engineSpan = EngineCandidateSpan(0, 2, "~i'mu")),
            CandidateItem("乙", "~i'mu", 2, engineSpan = EngineCandidateSpan(0, 2, "~i'mu")),
        )
        val snap = GenericPanelSnapshotBuilder.build(
            local = local,
            candidatesOverride = engineCandidates,
        )

        assertEquals(listOf("全部", "~'ji", "~i'mu"), snap.groups.map { it.reading })
        assertEquals("pa'ji", snap.groups[1].items[0].comment)
        assertEquals(listOf("pai'mu", "pai'mu"), snap.groups[2].items.map { it.comment })
    }

    @Test
    fun t9EmojiWithoutCommentStaysInAllGroupWithoutNumericRailPollution() {
        val local = TwelveKeySnapshotBuilder.Local(
            input = "64426",
            consumedEnd = 0,
            remaining = "64426",
            segmentsKey = "",
            rawPreedit = "64 426",
        )
        val engineCandidates = listOf(
            CandidateItem("你好", "ni hao", 0, engineSpan = EngineCandidateSpan(0, 5, "ni hao")),
            CandidateItem("👋", "", 1, engineSpan = EngineCandidateSpan(0, 5, "")),
            CandidateItem("拟好", "ni hao", 2, engineSpan = EngineCandidateSpan(0, 5, "ni hao")),
            CandidateItem("蜜柑", "mi gan", 3, engineSpan = EngineCandidateSpan(0, 5, "mi gan")),
        )
        val snap = GenericPanelSnapshotBuilder.build(
            local = local,
            candidatesOverride = engineCandidates,
        )

        assertEquals(listOf("全部", "ni hao", "mi gan"), snap.groups.map { it.reading })
        assertEquals(listOf("你好", "👋", "拟好", "蜜柑"), snap.groups[0].items.map { it.text })
        assertEquals("表情", snap.groups[0].items[1].comment)
    }

    @Test
    fun nineKeyPreeditMaterializesOnlyTypedCodePositionsLikeTwelveKey() {
        assertEquals("min j", PreeditPresentation.materializeSegmentedPreedit("646 5", "min jian"))
        assertEquals("min ji", PreeditPresentation.materializeSegmentedPreedit("646 54", "min jian"))
        assertEquals("ni hao", PreeditPresentation.materializeSegmentedPreedit("64 426", "ni hao"))
        assertEquals("n h", PreeditPresentation.materializeSegmentedPreedit("6 4", "ni hao"))
        assertEquals("min j 2", PreeditPresentation.materializeSegmentedPreedit("646 5 2", "min jian"))
    }
}
