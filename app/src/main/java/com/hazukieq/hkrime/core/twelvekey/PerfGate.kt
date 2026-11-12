package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import timber.log.Timber

/**
 * 候選熱路徑只讀微基準，測解碼、段組、聯想、合併與當前組合枚舉；輸出固定格式供工具判定。
 */
object PerfGate {

    /**
     * 固定微基準輸入集，用於可重複的性能比較。
     */
    private val INPUTS = listOf(
        "744ii",        // ps mno mno ghi ghi，涵蓋多種合法首段切法。
        "5e5e",         // 完整拼音（le le）
        "855",          // 首字母簡拼 → j**
        "i44yi",        // 混合碼（song nzi）
        "nz",           // 單鍵
        "7777777777",   // 超長輸入（每鍵都要重建快照）
        "455",          // 觸發首字母桶輪轉（g/h）
    )

    private const val DECODE_ROUNDS = 200
    private const val ASSOC_ROUNDS = 200
    private const val RANK_ROUNDS = 100
    private const val ENGINE_ROUNDS = 5

    @Volatile
    private var running = false

    /** 觸發一次門禁；後台線程執行，立即返回。 */
    fun run() {
        if (running) {
            Timber.i("perfgate: busy, skipped")
            return
        }
        running = true
        Thread({
            val started = System.nanoTime()
            try {
                reportReady()
                measureDecode()
                measureSegment()
                measureAssoc()
                measureRankAndMerge()
                measureEngine()
                Timber.i("perfgate: done total=%.1fms", msSince(started))
                // 立即落盤：門禁的用途是「跑完馬上判定」，不能等收鍵盤才 flush。
                runCatching { com.hazukieq.hkrime.util.LogFileTree.flushNow() }
            } catch (t: Throwable) {
                Timber.w(t, "perfgate: failed")
            } finally {
                running = false
            }
        }, "perfgate").apply { isDaemon = true }.start()
    }

    private fun reportReady() {
        // 這幾行讓腳本能判斷「資源有沒有載入」與「索引是否就緒」
        Timber.i(
            "perfgate: ready syllables=%d codes=%d charFreq=%d charRank=%d assocReady=%b",
            TwelveKeyPreedit.debugSyllableCount(),
            TwelveKeyPreedit.debugCodeCount(),
            TwelveKeyPreedit.debugCharFreqCount(),
            TwelveKeyPreedit.debugCharRankCount(),
            TwelveKeyPreedit.debugAssocReady(),
        )
    }

    /** 碼串 → 音節（每個按鍵都會跑）。 */
    private fun measureDecode() {
        val t0 = System.nanoTime()
        var valid = 0
        repeat(DECODE_ROUNDS) {
            INPUTS.forEach { input ->
                if (TwelveKeyPreedit.decode(input) != null) valid++
            }
        }
        val avg = msSince(t0) / (DECODE_ROUNDS * INPUTS.size)
        Timber.i("perfgate: op=decode avg=%.2fms n=%d valid=%d", avg, DECODE_ROUNDS * INPUTS.size, valid)
    }

    /** 第一次待選段的音節桶（左列分組的主要開銷）。 */
    private fun measureSegment() {
        val t0 = System.nanoTime()
        var groups = 0
        repeat(RANK_ROUNDS) {
            INPUTS.forEach { input ->
                val head = TwelveKeyPreedit.splitHeads(input).firstOrNull()?.first ?: return@forEach
                groups += TwelveKeyPreedit.syllablesForSegment(head, 8).size
            }
        }
        val avg = msSince(t0) / (RANK_ROUNDS * INPUTS.size)
        Timber.i("perfgate: op=segment avg=%.2fms n=%d groups=%d", avg, RANK_ROUNDS * INPUTS.size, groups)
    }

    /** 聯想詞查詢（每次重建面板都要跑 音節數 × 首字母數）。 */
    private fun measureAssoc() {
        val t0 = System.nanoTime()
        var rows = 0
        repeat(ASSOC_ROUNDS) {
            INPUTS.forEach { input ->
                val head = TwelveKeyPreedit.splitHeads(input).firstOrNull()?.first ?: return@forEach
                val rest = TwelveKeyPreedit.splitHeads(input).firstOrNull()?.second.orEmpty()
                val initials = TwelveKeyPreedit.initialsForPrefix(rest, 2)
                TwelveKeyPreedit.syllablesForSegment(head, 8).take(4).forEach { syllable ->
                    rows += TwelveKeyPreedit.associatedByReading(syllable, initials).size
                }
            }
        }
        val avg = msSince(t0) / (ASSOC_ROUNDS * INPUTS.size)
        Timber.i("perfgate: op=assoc avg=%.2fms n=%d rows=%d", avg, ASSOC_ROUNDS * INPUTS.size, rows)
    }

    /** 分組合併/排序（純函數，不含引擎）。 */
    private fun measureRankAndMerge() {
        val items = List(60) { index ->
            CandidateItem(
                text = "字$index",
                comment = if (index % 3 == 0) "ga" else "ga ga",
                index = index,
            )
        }
        val segmentGroups = List(8) { i ->
            SyllableGroup(
                reading = "ga$i",
                syllables = listOf("ga"),
                items = items.take(8).map { it.copy(comment = "ga$i") },
                segment = 0,
            )
        }
        val assocGroups = List(8) { i ->
            SyllableGroup(
                reading = "ga go$i",
                syllables = listOf("ga", "go"),
                items = items.take(6).map { it.copy(comment = "ga go$i", segment = 1) },
                segment = 1,
            )
        }
        val engineGroups = CandidateRanking.groupCandidates(items)
        // 走**重構後的唯一流水線**（mergeRows → collapsePrefixRows → tierAndSort → capRows）；
        // 舊的 panelGroups/mergeAssociationGroups 已刪，perf gate 不能再量一條死路徑。
        val segmentRows = segmentGroups.map { group ->
            PanelRow(group.reading, group.items, group.segment, sources = setOf(RowSource.SYNTH_SINGLE))
        }
        val assocRows = assocGroups.map { group ->
            PanelRow(group.reading, group.items, group.segment, sources = setOf(RowSource.ASSOC))
        }
        val engineRows = engineGroups.map { group ->
            PanelRow(group.reading, group.items, group.segment, sources = setOf(RowSource.ENGINE))
        }
        val t0 = System.nanoTime()
        var merged = 0
        repeat(RANK_ROUNDS) {
            val rows = mergeRows(segmentRows + engineRows + assocRows)
            val collapsed = collapsePrefixRows(rows)
            merged += capRows(tierAndSort(collapsed)).size
        }
        val avg = msSince(t0) / RANK_ROUNDS
        Timber.i("perfgate: op=merge avg=%.2fms n=%d merged=%d", avg, RANK_ROUNDS, merged)
    }

    /** 引擎枚舉成本（只讀；沒有組字時跳過）。 */
    private fun measureEngine() {
        val composing = runCatching { Rime.isComposing() }.getOrDefault(false)
        if (!composing) {
            Timber.i("perfgate: op=engine skipped=not-composing")
            return
        }
        val t0 = System.nanoTime()
        var count = 0
        repeat(ENGINE_ROUNDS) {
            count += synchronized(Rime.ENGINE_LOCK) {
                runCatching { Rime.candidatesSnapshot(TwelveKeySnapshotBuilder.MAX_CANDIDATES) }
                    .getOrNull()?.size ?: 0
            }
        }
        val avg = msSince(t0) / ENGINE_ROUNDS
        Timber.i("perfgate: op=engine avg=%.2fms n=%d cand=%d", avg, ENGINE_ROUNDS, count / ENGINE_ROUNDS)
    }

    private fun msSince(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0
}
