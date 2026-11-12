package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.CandidateRanking
import com.hazukieq.hkrime.core.twelvekey.PanelRow
import com.hazukieq.hkrime.core.twelvekey.RowSource
import com.hazukieq.hkrime.core.twelvekey.SyllableGroup
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import com.hazukieq.hkrime.core.twelvekey.mergeRows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 以實際 librime 生成的詞典/用戶詞庫逐鍵語料驗證 preedit、尾碼與面板不變量。
 * 缺少任一資料檔時跳過；發佈驗收必須確認無跳過。
 */
class RealCorpusTest {

    private data class Cand(val text: String, val comment: String)

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
                val cands = Regex("\\{\"t\":\"((?:[^\"\\\\]|\\\\.)*)\",\"c\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}")
                    .findAll(line)
                    .map { Cand(unescape(it.groupValues[1]), unescape(it.groupValues[2])) }
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

            // 與 RimeDictSource 同樣的展開規則：主詞典 + import_tables（block / inline）。
            val main = File(realDataDir, "hakka.dict.yaml")
            val names = LinkedHashSet<String>()
            names.add("hakka")
            if (main.isFile) {
                var inImports = false
                main.readLines().take(200).forEach { line ->
                    if (line.startsWith("...")) return@forEach
                    if (line.startsWith("import_tables")) {
                        inImports = true
                        Regex("\\[([^]]+)]").find(line)?.groupValues?.get(1)
                            ?.split(',')?.forEach { names.add(it.trim().trim('"', '\'')) }
                        return@forEach
                    }
                    if (!inImports) return@forEach
                    val dash = line.indexOf('-')
                    if (dash < 0) {
                        if (line.isNotEmpty() && !line[0].isWhitespace()) inImports = false
                        return@forEach
                    }
                    val name = line.substring(dash + 1).substringBefore('#').trim()
                    if (name.isNotEmpty()) names.add(name)
                }
            }
            val dictFile = if (main.isFile) main else File("src/main/assets/rime/hakka.dict.yaml")
            TwelveKeyPreedit.finishLoading(
                TwelveKeyPreedit.loadDictLines(dictFile.readLines().asSequence())
            )
            names.drop(1).forEach { name ->
                val f = File(realDataDir, "$name.dict.yaml")
                val src = if (f.isFile) f else File("src/main/assets/rime/$name.dict.yaml")
                if (src.isFile) TwelveKeyPreedit.loadWordLines(src.readLines().asSequence())
            }
            TwelveKeyPreedit.finishWordIndex()
        }
    }

    /** comment 的音節碼長之和（`ki`→`5i` 兩位、`mak`→`4a5` 三位）。 */
    private fun commentCodeLength(comment: List<String>): Int =
        comment.sumOf { runCatching { TwelveKeyPreedit.codeOf(it).length }.getOrDefault(0) }

    /** 簡拼按實際碼位消耗，保留未覆蓋的末鍵。 */
    private fun coveredCodeLength(comment: List<String>, cleanCode: String): Int? {
        var pos = 0
        for (syllable in comment) {
            val full = runCatching { TwelveKeyPreedit.codeOf(syllable) }.getOrNull().orEmpty()
            if (full.isEmpty()) return null
            pos = when {
                cleanCode.startsWith(full, pos) -> pos + full.length
                pos < cleanCode.length && cleanCode[pos] == full[0] -> pos + 1
                else -> return if (pos == 0) null else pos
            }
            if (pos > cleanCode.length) return cleanCode.length
        }
        return pos
    }

    /** 本用例裡 comment 實際吃掉的碼位數（前綴匹配優先，對不上退回碼長之和）。 */
    private fun consumedCodeLength(comment: List<String>, cleanCode: String): Int {
        // 走 App 真正的覆蓋規則（含 derive 別名：cw 之於 cu，a3 要整節吃掉，不能只吃首字母）。
        val covered = TwelveKeyPreedit.coveredCodeLengthForTest(
            cleanCode,
            comment.joinToString(" "),
        )
        if (covered != null) return covered.coerceIn(0, cleanCode.length)
        val sum = commentCodeLength(comment)
        return if (sum in 1..cleanCode.length) sum else cleanCode.length
    }

    /** 以候選讀音和實際輸入計算預期 preedit。 */
    private fun expectedPreedit(comment: List<String>, cleanCode: String): String {
        val consumed = consumedCodeLength(comment, cleanCode)
        val head = comment.joinToString(" ")
        return if (consumed in 1 until cleanCode.length) "$head ${cleanCode.substring(consumed)}"
        else head
    }

    private fun cleanCode(code: String) = code.replace(Regex("[\\s']"), "")

    /** 不變量 1：preedit 與「comment + 未覆蓋的原始碼」完全一致（覆蓋全部真實語料）。 */
    @Test
    fun preeditMatchesCommentPlusRemainingCode() {
        val failures = ArrayList<String>()
        var checked = 0
        for (rec in records) {
            val clean = cleanCode(rec.code)
            if (clean.isEmpty()) continue
            val first = rec.cands.firstOrNull() ?: continue
            if (first.comment.isBlank()) continue
            val comment = preedit.parseComment(first.comment) ?: continue
            checked++
            val rendered = preedit.rewrite(rec.preedit, first.text, first.comment) ?: rec.preedit
            val expected = expectedPreedit(comment, clean)
            if (rendered != expected && failures.size < 25) {
                failures.add(
                    "code=$clean preedit=[${rec.preedit}] first=[${first.text}/${first.comment}] " +
                        "rendered=[$rendered] expected=[$expected]"
                )
            }
        }
        assertTrue(
            "檢查 $checked 條真實語料，${failures.size}+ 條 preedit 不一致：\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
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
            if (consumed !in 1 until clean.length) continue
            checked++
            val rendered = preedit.rewrite(rec.preedit, first.text, first.comment) ?: rec.preedit
            val tail = clean.substring(consumed)
            if (!rendered.endsWith(" $tail") && failures.size < 25) {
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
                for ((text, n) in g.items.map { it.text }.groupingBy { it }.eachCount()) {
                    val got = mergedTexts[text] ?: 0
                    if (got < n && failures.size < 25) {
                        failures.add("code=$clean 讀音[${g.reading}] 候選 '$text' 丟了（引擎 $n，面板 $got）")
                    }
                }
            }
        }
        assertTrue("檢查 $checked 個 code，${failures.size}+ 條分組問題：\n" + failures.joinToString("\n"),
            failures.isEmpty())
    }
}
