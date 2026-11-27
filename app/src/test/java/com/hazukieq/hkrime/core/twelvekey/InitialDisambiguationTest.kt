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
        assertEquals(listOf("k", "l"), InitialDisambiguation.choices(session.remaining))
        assertTrue(session.onInitialLetter("k"))
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals("ai8i", session.pending)
        assertFalse(session.allowsCommit("开"))
        assertEquals("", session.selectedText)
        assertTrue(session.pick("开", "kai", 3, CompositionSession.Origin.PANEL))
        assertEquals("8i", session.remaining)
        assertEquals(emptyList<PhoneticSelection>(), session.phonetics)
        assertTrue(session.onInitialLetter("j"))
        assertEquals(listOf(PhoneticSelection("j", "8", true)), session.phonetics)
        assertEquals("i", session.pending)
        assertEquals("8i", session.undo())
        assertEquals(emptyList<PhoneticSelection>(), session.phonetics)
        assertEquals("开", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals("k", session.initial)
        assertEquals("", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertNull(session.initial)
        assertFalse(session.canUndo)
        assertFalse(session.allowsCommit("开"))
    }

    @Test fun lastInitialCanBeRefinedWithoutLosingKeys() {
        val session = CompositionSession()
        session.input("5")
        assertTrue(session.onInitialLetter("k"))
        assertEquals("", session.pending)
        assertTrue(session.refinable)
        assertFalse(session.onInitialLetter("k"))
        assertTrue(session.onInitialLetter("l"))
        assertTrue(session.onInitialLetter("k"))
        assertTrue(session.onInitialLetter("l"))
        assertEquals("l", session.initial)
        assertEquals(listOf(PhoneticSelection("l", "5", true)), session.phonetics)
        // 反覆切換末段首拼不堆積撤銷棧，退格一次直接撤回該段
        assertEquals("5", session.undo())
        assertNull(session.initial)
        assertFalse(session.canUndo)
        // 追加按鍵：已確認段（原碼前綴）保留，待確認尾碼增長。
        assertTrue(session.onInitialLetter("k"))
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
        assertTrue(session.onInitialLetter("k"))
        assertTrue(session.pick("开", "kai", 3, CompositionSession.Origin.BAR))
        assertTrue(session.onInitialLetter("j"))
        session.input("i4")
        assertEquals(emptyList<PhoneticSelection>(), session.phonetics)
        assertEquals("开", session.selectedText)
        assertEquals("5ai8i", session.undo())
        assertEquals(listOf(PhoneticSelection("k", "5", true)), session.phonetics)
        assertEquals("k", session.initial)
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
        assertEquals(listOf("b", "c"), snap.initialChoices)
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
        assertEquals("b", snap.initial)
        assertEquals(listOf("b", "c"), snap.initialChoices)
        assertEquals(listOf("去吧"), snap.recommend.filter { it.index >= 0 }.map { it.text })
        // 首段仍是 k：跨两段的整音节字（ka）不能冒充已确认序列。
        assertTrue(snap.recommend.none { it.text == "卡" && it.index >= 0 })
    }

    @Test fun initialLetterChoicesFollowPendingAndRefinableSegments() {
        assertEquals(listOf("k", "l"), TwelveKeySnapshotBuilder.letterChoices("5a", emptyList()))
        val k = PhoneticSelection("k", "5", true)
        assertEquals(listOf("b", "c"), TwelveKeySnapshotBuilder.letterChoices("5a", listOf(k)))
        assertNull(TwelveKeySnapshotBuilder.selectedLetter("5a", listOf(k)))
        val kb = PhoneticSelection("b", "a", true)
        assertEquals(listOf("b", "c"), TwelveKeySnapshotBuilder.letterChoices("5a", listOf(k, kb)))
        assertEquals("b", TwelveKeySnapshotBuilder.selectedLetter("5a", listOf(k, kb)))
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
        val constrained = TwelveKeyPreedit.initialCombos("5555555", "l")
        assertEquals(64, constrained.size)
        assertTrue(constrained.all { it.first() == "l" && it.size == 7 })
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
        // 窗口外的「平板」不能把條首改成它；窗口內沒有整串詞 → 兩條路徑都先給「造的組詞」。
        assertEquals(SYNTHETIC_SEGMENT, bar.barItems.first().segment)
        assertEquals(bar.barItems, full.barItems)
    }

    @Test fun fullEnumerationCannotPromoteACharacterOutsideTheFixedBarWindow() {
        val input = "5i5i"
        val all = List(64) { index -> CandidateItem("词$index", "ki ki", index,
            engineSpan = EngineCandidateSpan(0, 4, "ki ki")) } +
            CandidateItem("起", "ki", 64, engineSpan = EngineCandidateSpan(0, 2, "ki"))
        val local = TwelveKeySnapshotBuilder.Local(input, 0, input, "")
        val bar = TwelveKeySnapshotBuilder.build(local, 64, wantGroups = false, candidatesOverride = all, initial = "k")
        val full = TwelveKeySnapshotBuilder.build(local, wantGroups = true, candidatesOverride = all, initial = "k")
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
        val bar = TwelveKeySnapshotBuilder.build(local, 64, wantGroups = false, candidatesOverride = all, initial = "k")
        val full = TwelveKeySnapshotBuilder.build(local, wantGroups = true, candidatesOverride = all, initial = "k")
        assertEquals("开启", bar.barItems.first().text)
        assertEquals(700, bar.barItems.first().index)
        assertEquals(3, bar.barItems.first().inputSpan?.end)
        assertEquals(bar.barItems, full.barItems)
        assertEquals(input, full.remaining)
        assertEquals(listOf("k", "l"), full.initialChoices)
    }

    @Test fun delimiterAfterConfirmedPhoneticKeepsConfirmRowsAndMatchesInput() {
        val code = "9ai'i4"
        val taiWithQuote = PhoneticSelection("tai", "9ai'", false)
        val taiNoQuote = PhoneticSelection("tai", "9ai", false)

        // 前導分詞符不阻斷第二段待確認碼與左列確認項
        assertEquals("i4", PhoneticSelection.pending(code, listOf(taiNoQuote)))
        assertEquals(listOf("g", "h"), InitialDisambiguation.choices("'i4"))
        assertTrue(TwelveKeySnapshotBuilder.confirmGroups(code, listOf(taiNoQuote)).any { it.reading == "go" })

        // 候選跨度在引號前（end=3）或引號後（end=4）皆視為合法匹配
        assertTrue(PhoneticSelection.matchesInput("tai", code, 3, listOf(taiWithQuote)))
        assertTrue(PhoneticSelection.matchesInput("tai", code, 4, listOf(taiWithQuote)))
        assertTrue(PhoneticSelection.matchesInput("tai", code, 3, listOf(taiNoQuote)))
        assertTrue(PhoneticSelection.matchesInput("tai", code, 4, listOf(taiNoQuote)))
        assertFalse(PhoneticSelection.matchesInput("tai", code, 2, listOf(taiWithQuote)))

        // 尾端含分詞符的全碼候選仍置頂
        val codeWithTrailingQuote = "7i4aa4'"
        val partial = CandidateItem("身", "sin", 0, inputSpan = InputCodeSpan(codeWithTrailingQuote, 3, "sin"))
        val full = CandidateItem("平板", "pin ban", 1, inputSpan = InputCodeSpan(codeWithTrailingQuote, 6, "pin ban"))
        assertEquals(
            listOf(full, partial),
            CandidateRanking.preferCompleteFirst(listOf(partial, full), codeWithTrailingQuote),
        )
    }

    @Test fun optimisticPhoneticRefusesStaleRemainingAfterPickUndo() {
        val prev = TwelveKeySnapshot(
            input = "i4",
            consumedEnd = 0,
            remaining = "i4",
            groups = emptyList(),
            fallback = null,
            barEntries = listOf(CandidateItem("哥", "go", 0, inputSpan = InputCodeSpan("i4", 2, "go"))),
            recommend = listOf(CandidateItem("哥", "go", 0, inputSpan = InputCodeSpan("i4", 2, "go"))),
            contextKey = "stale-i4",
            rawPreedit = "i4",
        )
        // 撤銷選字後 remaining 從 "i4" 變回 "9aii4"：絕不能復用 "i4" 的舊候選與 contextKey
        assertNull(TwelveKeySnapshotBuilder.optimisticPhonetic(prev, "9aii4", emptyList(), true))
        assertNull(TwelveKeySnapshotBuilder.optimisticPhonetic(TwelveKeySnapshot.EMPTY, "i4", emptyList(), true))

        // 同一 remaining 上的拼音確認/撤銷可安全產生樂觀快照
        val same = TwelveKeySnapshotBuilder.optimisticPhonetic(
            prev, "i4", listOf(PhoneticSelection("g", "i", true)), true,
        )
        assertNotNull(same)
        assertEquals("i4", same!!.input)
        assertEquals("i4", same.remaining)
    }

    @Test fun confirmingSingleSyllableKeepsLeftRailVisibleForHighlightAndSiblingSwitch() {
        val code = "7a3" // pau / sau / pa / sa / p / s
        val pau = PhoneticSelection("pau", code, false)
        val rows = TwelveKeySnapshotBuilder.confirmGroups(code, listOf(pau))
        assertTrue("單音節確認後左列不能為空", rows.isNotEmpty())
        assertTrue("左列必須保留當前已選音節 pau", rows.any { it.reading == "pau" && it.segment != INITIAL_LETTER_SEGMENT })
        assertTrue("左列應保留同段其他可選音節 sau", rows.any { it.reading == "sau" && it.segment != INITIAL_LETTER_SEGMENT })
        assertTrue("左列應保留該段首拼行 p/s", rows.any { it.reading == "p" && it.segment == INITIAL_LETTER_SEGMENT })

        // 多音節末段確認後同理保留末段左列音節
        val multi = "7i45i"
        val sin = PhoneticSelection("sin", "7i4", false)
        val ki = PhoneticSelection("ki", "5i", false)
        val tailRows = TwelveKeySnapshotBuilder.confirmGroups(multi, listOf(sin, ki))
        assertTrue(tailRows.any { it.reading == "ki" && it.segment != INITIAL_LETTER_SEGMENT })
        assertTrue(tailRows.any { it.reading == "li" && it.segment != INITIAL_LETTER_SEGMENT })
    }

    @Test fun wEndingMapsToUKeyAndRemovedApDeriveNoLongerPollutesAtKey() {
        // xform/w$/u/：cw→a3、zw→83 直接歸位到 u 鍵，不再產生 aw/8w 死碼
        assertEquals("a3", TwelveKeyPreedit.codeOf("cw"))
        assertEquals("83", TwelveKeyPreedit.codeOf("zw"))

        val a3Syllables = TwelveKeySnapshotBuilder.confirmGroups("a3", emptyList())
            .filter { it.segment != INITIAL_LETTER_SEGMENT }
            .map { it.reading }
        assertTrue("a3 左列應包含 cu: $a3Syllables", "cu" in a3Syllables)
        assertTrue("a3 左列應包含 cw: $a3Syllables", "cw" in a3Syllables)
        assertTrue("cu 應排在 cw 之前: $a3Syllables", a3Syllables.indexOf("cu") < a3Syllables.indexOf("cw"))

        // 點擊左列 cw 可正確解析為消費 a3 的確認段，並保留高亮與可切換
        val cwSel = PhoneticSelection.resolve("cw", "a3", initialOnly = false)
        assertNotNull("cw 應可作為 a3 的有效確認段", cwSel)
        assertEquals("cw", cwSel!!.reading)
        assertEquals("a3", cwSel.code)
        val afterConfirm = TwelveKeySnapshotBuilder.confirmGroups("a3", listOf(cwSel))
            .filter { it.segment != INITIAL_LETTER_SEGMENT }
            .map { it.reading }
        assertTrue("確認 cw 後左列應保留 cw 高亮: $afterConfirm", "cw" in afterConfirm)
        assertTrue("確認 cw 後應保留 cu 供切換: $afterConfirm", "cu" in afterConfirm)

        // derive/ap$/at/ 已移除：ia9 只列原生 -at 音節，不再混入 -ap
        val ia9Syllables = TwelveKeySnapshotBuilder.confirmGroups("ia9", emptyList())
            .filter { it.segment != INITIAL_LETTER_SEGMENT }
            .map { it.reading }
        assertTrue("ia9 應包含原生音節 gat: $ia9Syllables", "gat" in ia9Syllables)
        assertFalse("移除 derive/ap$/at/ 後 ia9 不應包含 gap: $ia9Syllables", "gap" in ia9Syllables)
    }
}
