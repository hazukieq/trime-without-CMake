package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.PhraseMemory
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 驗證 bar/full 候選條、preedit 與兜底一致，不受面板開合影響。 */
class SnapshotDecouplingTest {

    companion object {
        private val preedit: TwelveKeyPreedit by lazy { TwelveKeyPreedit }

        @BeforeClass
        @JvmStatic
        fun loadDictionary() {
            TwelveKeyPreedit.finishLoading(
                TwelveKeyPreedit.loadDictLines(
                    File("src/main/assets/rime/hakka.dict.yaml").readLines().asSequence()
                )
            )
            TwelveKeyPreedit.finishWordIndex()
        }
    }

    private fun local(code: String) = TwelveKeySnapshotBuilder.Local(
        input = code,
        consumedEnd = 0,
        remaining = code,
        segmentsKey = "",
    )

    private val ee5Candidates = listOf(
        CandidateItem("邸", "de", 0),
        CandidateItem("的", "dit", 1),
        CandidateItem("地", "di", 2),
    )

    @Test
    fun barAndFullSnapshotsCarrySameCandidateContent() {
        val bar = TwelveKeySnapshotBuilder.build(
            local("ee5"), applyLearning = false, wantGroups = false,
            candidatesOverride = ee5Candidates,
        )
        val full = TwelveKeySnapshotBuilder.build(
            local("ee5"), applyLearning = false, wantGroups = true,
            candidatesOverride = ee5Candidates,
        )
        assertEquals(
            "候選條內容不能因 wantGroups 而變：bar=${bar.barEntries.map { it.text }} full=${full.barEntries.map { it.text }}",
            bar.barEntries.map { it.text },
            full.barEntries.map { it.text },
        )
        assertEquals("previewText 不能受面板影響", bar.previewText, full.previewText)
        assertEquals("inlineText 不能受面板影響", bar.inlineText, full.inlineText)
        assertNull("有候選就不該兜底（bar）", bar.fallback)
        assertNull("有候選就不該兜底（full）", full.fallback)
        assertTrue("full 模式必須建出分組", full.groups.isNotEmpty())
    }

    /** 面板全量枚舉不得改變固定條的學習混排與去重結果。 */
    @Test
    fun barIsUnaffectedByFullPanelEnumeration() {
        PhraseMemory.clear()
        val code = "5i34i"
        val learned = "学习词"
        repeat(3) { PhraseMemory.learnPick(code, learned, "li voi") }
        val candidates = (0 until 80).map { i ->
            if (i == 70) CandidateItem(learned, "li voi", i)
            else CandidateItem("候选$i", "li voi", i)
        }
        val bar = TwelveKeySnapshotBuilder.build(
            local(code),
            maxCandidates = TwelveKeyTuning.BAR_CANDIDATES,
            applyLearning = true,
            wantGroups = false,
            candidatesOverride = candidates,
        )
        val full = TwelveKeySnapshotBuilder.build(
            local(code),
            maxCandidates = TwelveKeyTuning.MAX_CANDIDATES,
            applyLearning = true,
            wantGroups = true,
            candidatesOverride = candidates,
        )
        assertEquals(
            "候選條不能因展開面板（枚舉 600）而變：bar=${bar.barEntries.map { it.text }} " +
                "full=${full.barEntries.map { it.text }}",
            bar.barEntries.map { it.text },
            full.barEntries.map { it.text },
        )
        assertEquals("previewText 也要一致", bar.previewText, full.previewText)
        PhraseMemory.clear()
    }

    /** 引擎空時先用字典段組兜底，不直接顯示原字母。 */
    @Test
    fun emptyEngineFallsBackToDictionaryNotLetters() {
        val snap = TwelveKeySnapshotBuilder.build(
            local("ee5"), applyLearning = false, wantGroups = false,
            candidatesOverride = emptyList(),
        )
        assertTrue("引擎沒候選時候選條要用字典段組：${snap.barEntries.map { it.text }}",
            snap.barEntries.isNotEmpty())
        assertNull("有字典候選就不該顯示字母兜底", snap.fallback)
        assertTrue("hasCandidates 要為真（bar 模式 groups 本來是空的）", snap.hasCandidates)
        val first = snap.barEntries.first()
        assertEquals("de 5", preedit.rewrite("ee5\u2038", first.text, first.comment))
    }

    @Test
    fun fallbackOnlyWhenEngineHasNoCandidates() {
        // 用字典裡也沒有音節的碼串（沒有段組可兜底）→ 才是真的只能顯示字母。
        val empty = TwelveKeySnapshotBuilder.build(
            local("qqqq"), applyLearning = false, wantGroups = false,
            candidatesOverride = emptyList(),
        )
        assertTrue("引擎沒候選、字典也沒段組時才兜底", empty.fallback != null)
        assertFalse("兜底快照不該自稱有候選", empty.hasCandidates)
    }

    @Test
    fun preeditUsesBarFirstCandidateRegardlessOfPanelState() {
        val bar = TwelveKeySnapshotBuilder.build(
            local("ee5"), applyLearning = false, wantGroups = false,
            candidatesOverride = ee5Candidates,
        )
        val full = TwelveKeySnapshotBuilder.build(
            local("ee5"), applyLearning = false, wantGroups = true,
            candidatesOverride = ee5Candidates,
        )
        val first = bar.barItems.first()
        val rendered = preedit.rewrite("ee5\u2038", first.text, first.comment)
        assertEquals("de 5", rendered)
        // 面板開關不改變首條 → 渲染結果必須一樣
        val fullFirst = full.barItems.first()
        assertEquals(rendered, preedit.rewrite("ee5\u2038", fullFirst.text, fullFirst.comment))
    }
}
