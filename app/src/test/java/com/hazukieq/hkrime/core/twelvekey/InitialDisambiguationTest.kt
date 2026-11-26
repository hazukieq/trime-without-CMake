package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class InitialDisambiguationTest {
    companion object {
        @JvmStatic @BeforeClass fun load() { OfflineDictionary.load() }
    }

    @Test fun typedPrefixDoesNotCompleteFirstCandidateReading() {
        assertEquals("sin k", TwelveKeyPreedit.rewrite("7i4 5‸", "升起", "sin ki"))
        assertEquals("sin ki", TwelveKeyPreedit.rewrite("7i4 5i‸", "升起", "sin ki"))
        assertEquals("s k", TwelveKeyPreedit.rewrite("75‸", "升起", "sin ki"))
        assertEquals("sin", TwelveKeyPreedit.rewrite("7i4‸", "升起", "sin ki"))
        assertEquals("5i", TwelveKeyPreedit.rewrite("5i‸", "升起", "sin ki"))
    }

    @Test fun everyRuntimeSyllablePrefixPreservesExactlyTheTypedKeyCount() {
        var checked = 0
        for (syllable in OfflineDictionary.runtimeSyllables) {
            val full = TwelveKeyPreedit.codeOf(syllable)
            for (length in 1..full.length) {
                val code = full.take(length)
                val rendered = TwelveKeyPreedit.rewrite(code, "", syllable)!!
                assertEquals("$code / $syllable -> $rendered", code, TwelveKeyPreedit.codeOf(rendered))
                assertTrue("must be a typed prefix: $syllable -> $rendered", syllable.startsWith(rendered))
                checked++
            }
        }
        assertTrue(checked > 1800)
        println("PREEDIT_SYLLABLE_PREFIXES=$checked")
    }

    @Test fun letterConfirmIsChronologicalWithPicksAndUndo() {
        val session = CompositionSession()
        session.input("5ai8i")
        assertEquals(listOf('k', 'l'), InitialDisambiguation.choices(session.remaining))
        assertTrue(session.onInitialLetter('k'))
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals("ai8i", session.pending)
        assertFalse(session.allowsCommit("开"))
        assertEquals("", session.selectedText)
        assertTrue(session.pick("开", "kai", 3, CompositionSession.Origin.PANEL))
        assertEquals("8i", session.remaining)
        assertEquals(emptyList<PhoneticSelection>(), session.phonetics)
        assertTrue(session.onInitialLetter('j'))
        assertEquals(listOf(PhoneticSelection("j", "8", true)), session.phonetics)
        assertEquals("i", session.pending)
        assertEquals("8i", session.undo())
        assertEquals(emptyList<PhoneticSelection>(), session.phonetics)
        assertEquals("开", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals('k', session.initial)
        assertEquals("", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertNull(session.initial)
        assertFalse(session.canUndo)
        assertFalse(session.allowsCommit("开"))
    }

    @Test fun lastInitialCanBeRefinedWithoutLosingKeys() {
        val session = CompositionSession()
        session.input("5")
        assertTrue(session.onInitialLetter('k'))
        assertEquals("", session.pending)
        assertTrue(session.refinable)
        assertFalse(session.onInitialLetter('k'))
        assertTrue(session.onInitialLetter('l'))
        assertEquals('l', session.initial)
        assertEquals(listOf(PhoneticSelection("l", "5", true)), session.phonetics)
        assertEquals("5", session.undo())
        assertEquals('k', session.initial)
        assertEquals("5", session.undo())
        assertNull(session.initial)
        assertFalse(session.canUndo)
        // 追加按鍵：已確認段（原碼前綴）保留，待確認尾碼增長。
        assertTrue(session.onInitialLetter('k'))
        session.input("5i")
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals("i", session.pending)
        assertFalse(session.refinable)
        assertEquals(emptyList<CompositionSession.Pick>(), session.picks)
        assertNull(session.completed())
    }

    @Test fun editingTheHeadDropsConfirmedSegmentsButKeepsSelectedPicks() {
        val session = CompositionSession()
        session.input("5ai8i")
        assertTrue(session.onInitialLetter('k'))
        assertTrue(session.pick("开", "kai", 3, CompositionSession.Origin.BAR))
        assertTrue(session.onInitialLetter('j'))
        session.input("i4")
        assertEquals(emptyList<PhoneticSelection>(), session.phonetics)
        assertEquals("开", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals('k', session.initial)
        assertEquals("", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertNull(session.initial)
        assertFalse(session.canUndo)
        assertNull(session.completed())
    }

    @Test fun confirmedInitialUsesEngineWordsAndAppFirstSegmentChars() {
        val code = "5a"
        val local = TwelveKeySnapshotBuilder.Local(code, 0, code, "")
        val engine = listOf(
            CandidateItem("其妻", "ki ci", 0, engineSpan = EngineCandidateSpan(0, 2, "ki ci")),
            CandidateItem("去吧", "ki ba", 1, engineSpan = EngineCandidateSpan(0, 2, "ki ba")),
            CandidateItem("卡", "ka", 2, engineSpan = EngineCandidateSpan(0, 2, "ka")),
        )
        val k = PhoneticSelection("k", "5", true)
        val snap = TwelveKeySnapshotBuilder.build(
            local, 600, wantGroups = true, candidatesOverride = engine, phonetics = listOf(k),
        )
        // 待確認鍵（a = abc）的合法首拼：b/c；a 是元音，不作首字母。
        assertEquals(listOf('b', 'c'), snap.initialChoices)
        assertNull(snap.initial)
        // 引擎詞在前；單音節 卡（跨度 2）與已確認的切分衝突，被跨度校驗排除。
        assertEquals("其妻", snap.barItems.first().text)
        assertEquals(listOf("其妻", "去吧"), snap.recommend.filter { it.index >= 0 }.map { it.text })
        // 首段漢字來自 App 字表，跨度只到首段（1 鍵）。
        val chars = snap.groups.flatMap { it.items }.filter { it.segment == INITIAL_CHAR_SEGMENT }
        assertTrue(chars.isNotEmpty())
        assertTrue(chars.all { it.inputSpan?.end == 1 })
        assertTrue(chars.any { it.text == "卡" && it.comment == "ka" })
        // 左列字母行與音節組在同一份 groups，排在最後。
        assertTrue(snap.groups.takeLast(2).all { it.segment == INITIAL_LETTER_SEGMENT })
        assertEquals(listOf("b", "c"), snap.groups.takeLast(2).map { it.reading })
    }

    @Test fun refinedLetterFiltersWordsWithoutChangingTheCode() {
        val code = "5a"
        val local = TwelveKeySnapshotBuilder.Local(code, 0, code, "")
        val engine = listOf(
            CandidateItem("其妻", "ki ci", 0, engineSpan = EngineCandidateSpan(0, 2, "ki ci")),
            CandidateItem("去吧", "ki ba", 1, engineSpan = EngineCandidateSpan(0, 2, "ki ba")),
        )
        val k = PhoneticSelection("k", "5", true)
        val kb = PhoneticSelection("b", "a", true)
        val snap = TwelveKeySnapshotBuilder.build(
            local, 600, wantGroups = true, candidatesOverride = engine, phonetics = listOf(k, kb),
        )
        assertEquals(code, snap.remaining)
        assertEquals('b', snap.initial)
        assertEquals(listOf('b', 'c'), snap.initialChoices)
        assertEquals(listOf("去吧"), snap.recommend.filter { it.index >= 0 }.map { it.text })
        // 首段仍是 k：跨两段的整音节字（ka）不能冒充已确认序列。
        assertTrue(snap.recommend.none { it.text == "卡" && it.index >= 0 })
    }

    @Test fun initialLetterChoicesFollowPendingAndRefinableSegments() {
        assertEquals(listOf('k', 'l'), TwelveKeySnapshotBuilder.letterChoices("5a", emptyList()))
        val k = PhoneticSelection("k", "5", true)
        assertEquals(listOf('b', 'c'), TwelveKeySnapshotBuilder.letterChoices("5a", listOf(k)))
        assertNull(TwelveKeySnapshotBuilder.selectedLetter("5a", listOf(k)))
        val kb = PhoneticSelection("b", "a", true)
        assertEquals(listOf('b', 'c'), TwelveKeySnapshotBuilder.letterChoices("5a", listOf(k, kb)))
        assertEquals('b', TwelveKeySnapshotBuilder.selectedLetter("5a", listOf(k, kb)))
        assertTrue(TwelveKeySnapshotBuilder.letterChoices("5a", listOf(k, PhoneticSelection("ka", "5a", false))).isEmpty())
    }

    @Test fun firstSegmentCharSpanConsumesOnlyThatSegment() {
        val item = CandidateItem("康", "kang", -1, segment = INITIAL_CHAR_SEGMENT, restCode = "a")
        val bound = CandidateSelection.bind(item, "5a")
        assertEquals(1, bound.inputSpan?.end)
        assertEquals("a", "5a".substring(bound.inputSpan!!.end))
    }

    @Test fun eachSourceIsConstrainedBeforeItsQuota() {
        var rows = 0
        for (code in listOf("5", "55", "5i", "5i5i", "i", "ia8", "aa4", "5i4a57")) {
            for (initial in InitialDisambiguation.choices(code)) {
                val panel = TwelveKeySnapshotBuilder.buildPanelRows(code, null, emptyList(), true, initial)
                assertTrue("$code/$initial", panel.rows.all { it.reading.startsWith(initial) })
                assertTrue(panel.rows.none { RowSource.ISOLATED in it.sources })
                val unrestricted = TwelveKeySnapshotBuilder.splitParts(code)
                val constrained = TwelveKeySnapshotBuilder.splitParts(code, initial)
                for (part in constrained) {
                    val expected = unrestricted.firstOrNull { it.head == part.head }?.syllables.orEmpty()
                        .filter { it.startsWith(initial) }
                    assertTrue(part.syllables.containsAll(expected))
                    assertTrue(part.syllables.all { it.startsWith(initial) })
                }
                rows += panel.rows.size
            }
        }
        assertTrue(rows > 20)
    }

    @Test fun constraintIsAppliedBeforeCartesianProductBudget() {
        assertTrue(TwelveKeyPreedit.initialCombos("5555555").isEmpty())
        val constrained = TwelveKeyPreedit.initialCombos("5555555", 'l')
        assertEquals(64, constrained.size)
        assertTrue(constrained.all { it.first() == 'l' && it.size == 7 })
        assertEquals(64, constrained.distinct().size)
    }

    @Test fun segmentCharacterSupplementCannotReplaceTheFirstMatchingWord() {
        val code = "7i4aa4"
        val words = List(12) { i -> CandidateItem(if (i == 0) "平板" else "候选$i", "pin ban", i,
            engineSpan = EngineCandidateSpan(0, 6, "pin ban")) }
        val all = words + CandidateItem("身", "sin", 12, engineSpan = EngineCandidateSpan(0, 3, "sin"))
        val local = TwelveKeySnapshotBuilder.Local(code, 0, code, "", "7i4 aa4‸")
        val bar = TwelveKeySnapshotBuilder.build(local, 64, applyLearning = false, wantGroups = false, candidatesOverride = all)
        val full = TwelveKeySnapshotBuilder.build(local, applyLearning = false, candidatesOverride = all)
        assertEquals("平板", bar.barItems.first().text)
        assertEquals("身", bar.barItems.last().text)
        assertEquals(12, bar.barItems.size)
        assertEquals(bar.barItems, full.barItems)
        PreeditPresentation.publish(bar, "")
        assertEquals("pin ban", PreeditPresentation.render(local.rawPreedit, code))
    }

    @Test fun completeFirstUsesRawSpanAndKeepsOtherCandidatesInOrder() {
        val code = "7i4aa4"
        val partial = CandidateItem("肾", "pin ban", 0, inputSpan = InputCodeSpan(code, 3, "sin"))
        val stale = CandidateItem("旧", "pin ban", 1, inputSpan = InputCodeSpan("123456", 6, "pin ban"))
        val full = CandidateItem("平板", "[说明]", 2, inputSpan = InputCodeSpan(code, 6, "pin ban"))
        val rest = CandidateItem("身", "sin", 3, inputSpan = InputCodeSpan(code, 3, "sin"))
        assertEquals(listOf(full, partial, stale, rest), CandidateRanking.preferCompleteFirst(listOf(partial, stale, full, rest), code))
        assertEquals(listOf(partial, stale, rest), CandidateRanking.preferCompleteFirst(listOf(partial, stale, rest), code))
    }

    @Test fun completeMatchOutsideTheFixedWindowCannotChangeTheFirstCandidate() {
        val code = "7i4aa4"
        val all = List(64) { i -> CandidateItem("候$i", "sin", i, engineSpan = EngineCandidateSpan(0, 3, "sin")) } +
            CandidateItem("平板", "pin ban", 64, engineSpan = EngineCandidateSpan(0, 6, "pin ban"))
        val local = TwelveKeySnapshotBuilder.Local(code, 0, code, "")
        val bar = TwelveKeySnapshotBuilder.build(local, 64, applyLearning = false, wantGroups = false, candidatesOverride = all)
        val full = TwelveKeySnapshotBuilder.build(local, applyLearning = false, candidatesOverride = all)
        assertEquals("候0", bar.barItems.first().text)
        assertEquals(bar.barItems, full.barItems)
    }

    @Test fun fullEnumerationCannotPromoteACharacterOutsideTheFixedBarWindow() {
        val input = "5i5i"
        val all = List(64) { index -> CandidateItem("词$index", "ki ki", index,
            engineSpan = EngineCandidateSpan(0, 4, "ki ki")) } +
            CandidateItem("起", "ki", 64, engineSpan = EngineCandidateSpan(0, 2, "ki"))
        val local = TwelveKeySnapshotBuilder.Local(input, 0, input, "")
        val bar = TwelveKeySnapshotBuilder.build(local, 64, wantGroups = false, candidatesOverride = all, initial = 'k')
        val full = TwelveKeySnapshotBuilder.build(local, wantGroups = true, candidatesOverride = all, initial = 'k')
        assertEquals(bar.barItems, full.barItems)
        assertEquals(bar.previewText, full.previewText)
        assertTrue(full.groups.flatMap { it.items }.any { it.index == 64 })
        assertTrue(full.barItems.none { it.index == 64 })
    }

    @Test fun filteredWindowRetainsGlobalIndexAndRawSpanAndDoesNotDrift() {
        val input = "55i"
        val all = List(700) { i -> CandidateItem("候$i", "li ki", i,
            engineSpan = EngineCandidateSpan(0, 3, "li ki")) } +
            CandidateItem("开启", "kai ki", 700, engineSpan = EngineCandidateSpan(0, 3, "kai ki"))
        val local = TwelveKeySnapshotBuilder.Local(input, 0, input, "")
        val bar = TwelveKeySnapshotBuilder.build(local, 64, wantGroups = false, candidatesOverride = all, initial = 'k')
        val full = TwelveKeySnapshotBuilder.build(local, wantGroups = true, candidatesOverride = all, initial = 'k')
        assertEquals("开启", bar.barItems.first().text)
        assertEquals(700, bar.barItems.first().index)
        assertEquals(3, bar.barItems.first().inputSpan?.end)
        assertEquals(bar.barItems, full.barItems)
        assertEquals(input, full.remaining)
        assertEquals(listOf('k', 'l'), full.initialChoices)
    }
}
