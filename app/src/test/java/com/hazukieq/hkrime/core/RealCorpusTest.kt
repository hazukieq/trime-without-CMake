package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.CandidateRanking
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * **真實語料**驅動的不變量測試（用戶 2026-11-11："app 端的測試用例數據我嚴重懷疑了，
 * 必須改用全部真實數據（可以從真機拿），每個用例都要盡可能考慮所有 cases"）。
 *
 * 語料不是手寫的：
 * 1. `rime-jni/host-test/pull-real-data.sh` 把真機 `/sdcard/hkrime` 的詞庫/詞表拉到
 *    `/tmp/hkrime-real-data`（就是用戶在用的那份，含自加詞條）；
 * 2. `run-host-test.sh --corpus /tmp/hkrime-corpus.jsonl` 用**真的 librime**（PC 版）把
 *    詞庫裡每個詞的碼串、簡拼、以及逐鍵前綴（打字過程的每一幀）餵進去，記錄 librime 給的
 *    原始 `preedit` 與前 40 條候選（含 comment）；
 * 3. 這裡逐條斷言 App 側 `TwelveKeyPreedit.rewrite()` / 面板分組的不變量。
 *
 * 沒有語料時自動跳過（普通 CI 不阻塞），出補丁/發佈前必須跑。
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

        /** 用戶詞庫（hakka.userdb）導出的語料：用戶真打出來的音節組合（2026-11-11 要求納入）。 */
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

    /** comment 的音節碼長之和 = 它承諾吃掉的碼位數（ki→5i 兩位、mak→4a5 三位）。 */
    private fun commentCodeLength(comment: List<String>): Int =
        comment.sumOf { runCatching { TwelveKeyPreedit.codeOf(it).length }.getOrDefault(0) }

    /**
     * 期望的 preedit（用戶 2026-11-11 定的規則）：
     * - comment 的碼長 < 輸入長 → comment + 剩下的原始碼（一個字都不能少）；
     * - 否則（簡拼/半截/剛好覆蓋）→ 只顯示 comment，不補尾巴。
     */
    private fun expectedPreedit(comment: List<String>, cleanCode: String): String {
        val sum = commentCodeLength(comment)
        val head = comment.joinToString(" ")
        return if (sum in 1 until cleanCode.length) "$head ${cleanCode.substring(sum)}" else head
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
            val sum = commentCodeLength(comment)
            if (sum !in 1 until clean.length) continue
            checked++
            val rendered = preedit.rewrite(rec.preedit, first.text, first.comment) ?: rec.preedit
            val tail = clean.substring(sum)
            if (!rendered.endsWith(" $tail") && failures.size < 25) {
                failures.add("code=$clean first=[${first.text}/${first.comment}] rendered=[$rendered] tail=[$tail]")
            }
        }
        assertTrue("檢查 $checked 條帶尾碼用例，${failures.size}+ 條尾碼不對：\n" + failures.joinToString("\n"),
            failures.isEmpty())
    }

    /**
     * 不變量 3（面板分組，按 docs/10 §15.62 的規則）：
     * - 段組存在時，引擎裡**同讀音**的組必須並進段組（候選條上能看到的字/字母，面板同一行裡必須有）；
     * - 引擎的詞組組（≥2 音節）與含非漢字的組必須保留；
     * - 純漢字單音節、且與段組讀音不同的引擎組，按設計丟掉（避免左列被幾十行灌滿）——丟掉的必須
     *   確實不屬於任何段組讀音；
     * - 讀音不重複（一個讀音只有一行）。
     */
    @Test
    fun panelGroupsFollowMergeAndDropRules() {
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
            val merged = CandidateRanking.panelGroups(segments, engineGroups)

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
                    g.syllables.size >= 2 ||
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
