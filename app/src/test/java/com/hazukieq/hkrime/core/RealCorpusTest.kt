package com.hazukieq.hkrime.core


import com.hazukieq.hkrime.core.twelvekey.CandidateInputBoundary
import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.CandidateRanking
import com.hazukieq.hkrime.core.twelvekey.CandidateSelection
import com.hazukieq.hkrime.core.twelvekey.CompositionInput
import com.hazukieq.hkrime.core.twelvekey.CompositionSession
import com.hazukieq.hkrime.core.twelvekey.EngineCandidateSpan
import com.hazukieq.hkrime.core.twelvekey.PanelRow
import com.hazukieq.hkrime.core.twelvekey.RowSource
import com.hazukieq.hkrime.core.twelvekey.SyllableGroup
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import com.hazukieq.hkrime.core.twelvekey.mergeRows
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 以實際 librime 生成的詞典/用戶詞庫逐鍵語料驗證 preedit、尾碼與面板不變量。
 * 缺少任一資料檔時跳過；發佈驗收必須確認無跳過。
 */
class RealCorpusTest {

    private data class Cand(val text: String, val comment: String, val native: EngineCandidateSpan?)

    private data class Record(val code: String, val preedit: String, val cands: List<Cand>)

    companion object {
        private val preedit: TwelveKeyPreedit by lazy { TwelveKeyPreedit }

        private val realDataDir: File by lazy {
            File(System.getenv("HKRIME_REAL_DATA") ?: "/tmp/hkrime-real-data")
        }

        private val corpusFile: File by lazy {
            File(System.getenv("HKRIME_CORPUS") ?: "/tmp/hkrime-corpus.jsonl")
        }

        /** 用戶詞庫導出生成的逐鍵語料。 */
        private val userDbCorpusFile: File by lazy {
            File(System.getenv("HKRIME_CORPUS_USERDB") ?: "/tmp/hkrime-corpus-userdb.jsonl")
        }

        private fun parse(file: File): List<Record> = file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val code = field(line, "code") ?: return@mapNotNull null
                val pre = field(line, "preedit") ?: ""
                val cands = Regex("""\{"t":"((?:[^"\\]|\\.)*)","c":"((?:[^"\\]|\\.)*)"(?:,"s":(-?\d+),"e":(-?\d+),"r":"((?:[^"\\]|\\.)*)")?\}""")
                    .findAll(line)
                    .map { Cand(unescape(it.groupValues[1]), unescape(it.groupValues[2]),
                        if (it.groupValues[3].isEmpty()) null else EngineCandidateSpan(
                            it.groupValues[3].toInt(), it.groupValues[4].toInt(), unescape(it.groupValues[5]))) }
                    .toList()
            Record(code, pre, cands)
        }

        private val records: List<Record> by lazy {
            parse(corpusFile) + parse(userDbCorpusFile)
        }

        private fun field(line: String, name: String): String? =
            Regex("\"$name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.let { unescape(it.groupValues[1]) }

        private fun unescape(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }

        @BeforeClass
        @JvmStatic
        fun loadRealDictionary() {
            assumeTrue("真實數據快照不存在：$realDataDir（跑 pull-real-data.sh）", realDataDir.isDirectory)
            assumeTrue("語料不存在：$corpusFile（跑 run-host-test.sh --corpus ...）", corpusFile.isFile)
            assumeTrue(
                "用戶詞庫語料不存在：$userDbCorpusFile（跑 run-host-test.sh --userdb … --corpus-userdb …）",
                userDbCorpusFile.isFile,
            )

            com.hazukieq.hkrime.core.twelvekey.OfflineDictionary.load(realDataDir)
        }
    }

    @Test fun nativeCandidateRangesRemainReversibleAcrossRealDictionaryAndUserdb() {
        var checked = 0
        var partial = 0
        for (record in records) {
            if (!CompositionInput.isCodeString(record.code)) continue
            val binder = CandidateSelection.InputBinder(record.code)
            for ((index, candidate) in record.cands.withIndex()) {
                val native = candidate.native ?: error("需重新生成含原生跨度的真實語料")
                val item = binder.bind(CandidateItem(candidate.text, candidate.comment, index, engineSpan = native))
                org.junit.Assert.assertNotNull("引擎候選必須有有效原碼跨度: ${record.code}/$native", item.inputSpan)
                val span = requireNotNull(item.inputSpan)
                org.junit.Assert.assertEquals(native.end, span.end)
                val session = CompositionSession()
                session.input(record.code)
                assertTrue(session.pick(item.text, span.reading, span.end, CompositionSession.Origin.PANEL))
                if (native.end < record.code.length) {
                    org.junit.Assert.assertNull(session.completed())
                    partial++
                }
                org.junit.Assert.assertEquals(record.code, session.undo())
                checked++
            }
        }
        println("REAL_NATIVE_RANGES=$checked PARTIAL_NATIVE_GUARDS=$partial")
        assertTrue(checked > 100000)
        assertTrue(partial > 10000)
    }

    /** 真實候選可精確對應的前綴逐一走生產狀態機：部分不得提交，撤回必須還原原碼。 */
    @Test
    fun realCandidateSelectionBoundariesAreReversibleAndNeverCommitTails() {
        var checked = 0
        var partial = 0
        var invalidSuffix = 0
        var nonCodeFrames = 0
        val origins = CompositionSession.Origin.values()
        for (record in records) {
            val code = cleanCode(record.code)
            val binder = CandidateSelection.InputBinder(code)
            if (!CompositionInput.isCodeString(code)) {
                // 與生產標點/混合字母路由一致：仍由其它真實語料測試覆蓋，不授予 12 鍵消費跨度。
                nonCodeFrames++
                record.cands.forEach { candidate ->
                    org.junit.Assert.assertNull(binder.bind(
                        CandidateItem(candidate.text, candidate.comment, 0)).inputSpan)
                }
                continue
            }
            for (candidate in record.cands) {
                val tokens = preedit.parseComment(candidate.comment) ?: continue
                if (tokens.any { token -> token.any { it !in 'a'..'z' } } || "r" in tokens.dropLast(1)) continue
                val full = preedit.codeOfReading(candidate.comment)
                if (full.isEmpty() || !code.startsWith(full)) continue
                if (tokens.lastOrNull() == "r" && full.length < code.length) {
                    invalidSuffix++
                    org.junit.Assert.assertNull(CandidateInputBoundary.selectedInputEnd(candidate.comment, code))
                    continue
                }
                val item = binder.bind(CandidateItem(candidate.text, candidate.comment, 0))
                val end = CandidateSelection.consumedEnd(
                    item, code, CompositionSession.Origin.BAR)
                org.junit.Assert.assertEquals("真實候選完整前綴消費範圍", full.length, end)
                val state = CompositionSession()
                state.input(code)
                assertTrue(state.pick(item.text, item.inputSpan!!.reading, end!!, origins[checked % origins.size]))
                if (full.length < code.length) {
                    partial++
                    org.junit.Assert.assertNull("未選尾碼必須阻止提交", state.completed())
                    org.junit.Assert.assertFalse(state.allowsCommit(candidate.text))
                }
                org.junit.Assert.assertEquals("撤回必須還原逐鍵原碼", code, state.undo())
                org.junit.Assert.assertEquals("撤回不能殘留選中文字", "", state.selectedText)
                checked++
            }
        }
        println("REAL_SELECTION_BOUNDARIES=$checked PARTIAL_GUARDS=$partial INVALID_SUFFIX_REJECTED=$invalidSuffix NON_CODE_FRAMES=$nonCodeFrames")
        assertTrue("必須實際覆蓋真實候選，不能空跑", checked > 1000)
        assertTrue("必須實際覆蓋部分選擇", partial > 100)
    }

    private fun consumedCodeLength(comment: List<String>, cleanCode: String): Int =
        TwelveKeyPreedit.coveredCodeLengthForTest(cleanCode, comment.joinToString(" ")) ?: 0

    private fun cleanCode(code: String) = code.replace(Regex("[\\s']"), "")

    /** 顯示內容重新編碼後必須等於全部已輸入碼位：既不漏鍵，也不補輸入。 */
    @Test
    fun preeditConservesEveryTypedCodePosition() {
        val failures = ArrayList<String>()
        var checked = 0
        for (rec in records) {
            val clean = cleanCode(rec.code)
            if (clean.isEmpty()) continue
            val first = rec.cands.firstOrNull() ?: continue
            if (preedit.parseComment(first.comment) == null) continue
            val rendered = preedit.rewrite(rec.preedit, first.text, first.comment) ?: rec.preedit
            val encoded = rendered.split(' ').joinToString("") { TwelveKeyPreedit.codeOf(it) }
            checked++
            if (encoded != clean && failures.size < 25) {
                failures.add("code=$clean reading=${first.comment} shown=$rendered reencoded=$encoded")
            }
        }
        println("REAL_PREEDIT_TYPED_POSITIONS=$checked")
        assertTrue("必須使用實際語料", checked > 1000)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** 不變量 2：有尾碼的 case，尾碼必須是輸入碼的**原樣後綴**（不能改寫、不能少、不能多）。 */
    @Test
    fun rawTailIsVerbatimSuffixOfTypedCode() {
        val failures = ArrayList<String>()
        var checked = 0
        for (rec in records) {
            val clean = cleanCode(rec.code)
            val first = rec.cands.firstOrNull() ?: continue
            if (first.comment.isBlank()) continue
            val comment = preedit.parseComment(first.comment) ?: continue
            val consumed = consumedCodeLength(comment, clean)
            if (consumed !in 0 until clean.length) continue
            checked++
            val rendered = preedit.rewrite(rec.preedit, first.text, first.comment) ?: rec.preedit
            val tail = clean.substring(consumed)
            if (!(if (consumed == 0) rendered == tail else rendered.endsWith(" $tail")) && failures.size < 25) {
                failures.add("code=$clean first=[${first.text}/${first.comment}] rendered=[$rendered] tail=[$tail]")
            }
        }
        assertTrue("檢查 $checked 條帶尾碼用例，${failures.size}+ 條尾碼不對：\n" + failures.joinToString("\n"),
            failures.isEmpty())
    }

    /** 引擎組通過同一合併規則，保留多音節與非漢字候選。 */
    @Test
    fun engineRowsFollowMergeAndDropRules() {
        val failures = ArrayList<String>()
        var checked = 0
        val seen = HashSet<String>()
        for (rec in records) {
            val clean = cleanCode(rec.code)
            if (clean.isEmpty() || !seen.add(clean)) continue
            val items = rec.cands.mapIndexed { i, c -> CandidateItem(c.text, c.comment, i) }
            if (items.isEmpty()) continue
            checked++
            val segments = runCatching { TwelveKeySnapshotBuilder.debugSegmentGroups(clean) }
                .getOrElse { emptyList() }
                .filter { it.segment != 1 }
            val engineGroups = CandidateRanking.groupCandidates(items)
            // 走重構後的唯一流水線（舊 CandidateRanking.panelGroups 已刪）：段組行 + 引擎行，
            // 交給 mergeRows 合併去重（引擎組 >2 音節/含非漢字的保留規則在 engineRows 內）。
            val segmentRows = segments.map { group ->
                PanelRow(
                    reading = group.reading,
                    items = group.items,
                    segment = group.segment,
                    sources = setOf(RowSource.SYNTH_SINGLE),
                )
            }
            val engineRows = TwelveKeySnapshotBuilder.engineRows(
                engineGroups,
                segments.map { it.reading }.toSet(),
            )
            val merged = mergeRows(segmentRows + engineRows)
                .map { row ->
                    SyllableGroup(
                        reading = row.reading,
                        syllables = row.reading.split(' ').filter { it.isNotBlank() },
                        items = row.items,
                        segment = row.segment,
                    )
                }

            val readings = merged.map { it.reading }.filter { it.isNotBlank() }
            if (readings.size != readings.distinct().size && failures.size < 25) {
                failures.add("code=$clean 讀音重複行：$readings")
            }
            val segmentReadings = segments.map { it.reading }.toSet()
            val mergedTexts = merged.flatMap { g -> g.items.map { it.text } }
                .groupingBy { it }.eachCount()
            for (g in engineGroups) {
                val mustKeep = segmentReadings.isEmpty() ||
                    g.reading in segmentReadings ||
                    g.reading.split(' ').count { it.isNotBlank() } >= 2 ||
                    g.items.any { !CandidateRanking.isSingleCjkChar(it.text) }
                if (!mustKeep) continue      // 設計上允許丟掉
                // 面板同一 reading 只留一行、同行按精确文字去重（AGENTS 5.2），引擎重复给出的同名项
                // 只需保证面板里仍出现一次，不能按引擎重复次数要求面板同样条数。
                for (text in g.items.map { it.text }.distinct()) {
                    val got = mergedTexts[text] ?: 0
                    if (got < 1 && failures.size < 25) {
                        failures.add(
                            "code=$clean 讀音[${g.reading}] 候選 '$text' 丟了" +
                                "（引擎 ${g.items.count { it.text == text }}，面板 0）"
                        )
                    }
                }
            }
        }
        assertTrue("檢查 $checked 個 code，${failures.size}+ 條分組問題：\n" + failures.joinToString("\n"),
            failures.isEmpty())
    }
}
