package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 快照層不變量（用戶 2026-11-11：「編碼/候選怎麼能讓展開面板給影響到呢？你這是解耦嗎？」）。
 *
 * 用戶報的現象：打字時 preedit 顯示原始字母 `dek`、候選條也是兜底；**展開面板再關閉**後變成
 * `de 5` / `邸`。根因是 `fallback` 的判據用了面板分組數（面板關閉時 `wantGroups=false`，
 * `groups` 恆空 → 每一幀都掛 fallback）。
 *
 * 這裡用**注入候選**（`candidatesOverride`，不碰 librime）釘住三條：
 * 1. 同一份引擎候選，`bar` 與 `full` 兩條構建路徑的**候選條/預覽/嵌入式預覽/兜底**必須完全一致；
 * 2. 有候選時**永遠不兜底**（哪怕面板分組是空的）；
 * 3. preedit 只用候選條第一條的 comment，跟 wantGroups 無關。
 */
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

    /**
     * 用戶 2026-11-11：`ee5` 引擎裝不出候選時，候選條**不能退成字母**（`eek`），
     * 應該用面板同源的字典段組（`de` 讀音下的 邸/的/地…），preedit 也就是 `de 5`。
     */
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
