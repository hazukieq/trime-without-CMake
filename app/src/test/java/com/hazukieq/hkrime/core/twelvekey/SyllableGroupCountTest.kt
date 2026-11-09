package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「面板左列每個待選段該列幾個音節組」的數據測試。
 *
 * 為什麼用單元測試而不是點 UI：這個數字是**資料決定**的（`syllablesForSegment` 的頻次排序 +
 * 字表權重），跑一次 JVM 測試就能把 12 個首碼的覆蓋率算清楚，比在手機上點快幾個數量級。
 *
 * 這裡刻意用純檔案讀取 + 復刻 `TwelveKeyPreedit` 的解析/xlit 規則（不 new Android Context），
 * 規則若有變動，兩邊都要改（見 `TwelveKeyPreedit.load()` / `codeOf()` / `syllablesForSegment()`）。
 */
class SyllableGroupCountTest {

    private val assetDir = File("src/main/assets/rime")

    /** 字 -> 讀音（多讀音只取第一個，與生產代碼 `charTable.putIfAbsent` 一致）。 */
    private val charTable = HashMap<String, String>()

    /** 讀音 -> 該讀音的字（與生產代碼 `syllableToChars` 一致）。 */
    private val syllableToChars = LinkedHashMap<String, MutableList<String>>()

    /** 讀音 -> 詞典裡出現次數（= 該讀音的字數；生產代碼 `Entry.freq` 同義）。 */
    private val syllableFreq = HashMap<String, Int>()

    /** 綜合字頻分（0..10000）：詞表統計與權威字頻各取百分位、取較大值，與生產代碼一致。 */
    private var generalScore = HashMap<Int, Int>()

    private fun load() {
        File(assetDir, "hakka.dict.yaml").forEachLine { raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@forEachLine
            val tab = raw.indexOf('\t')
            if (tab < 0) return@forEachLine
            val ch = raw.substring(0, tab).trim()
            val syllable = raw.substring(tab + 1).trim()
            if (ch.length == 1) {
                charTable.putIfAbsent(ch, syllable)
                syllableToChars.getOrPut(syllable) { ArrayList() }.let { if (!it.contains(ch)) it.add(ch) }
            }
            if (syllable.isNotEmpty() && syllable.all { it in 'a'..'z' }) {
                syllableFreq[syllable] = (syllableFreq[syllable] ?: 0) + 1
            }
        }
        generalScore = buildGeneralScores()
    }

    private fun readWeights(name: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        File(assetDir, name).forEachLine { raw ->
            if (raw.isEmpty() || raw.startsWith("#")) return@forEachLine
            val tab = raw.indexOf('\t')
            if (tab <= 0) return@forEachLine
            raw.substring(tab + 1).trim().toIntOrNull()?.let { out[raw.substring(0, tab)] = it }
        }
        return out
    }

    private fun percentile(values: Map<String, Int>): Map<Int, Int> {
        val ordered = values.entries.sortedByDescending { it.value }
        val n = ordered.size
        val out = HashMap<Int, Int>(n * 2)
        ordered.forEachIndexed { index, entry ->
            out[entry.key.codePointAt(0)] = (n - index) * 10000 / n
        }
        return out
    }

    private fun buildGeneralScores(): HashMap<Int, Int> {
        val byFreq = percentile(readWeights("hakka_char_freq.txt"))
        val byRank = percentile(readWeights("hakka_char_rank.txt"))
        val out = HashMap<Int, Int>(byFreq.size + byRank.size)
        byFreq.forEach { (cp, score) -> out[cp] = score }
        byRank.forEach { (cp, score) -> out[cp] = maxOf(out[cp] ?: 0, score) }
        return out
    }

    private fun scoreOf(ch: String): Int =
        if (ch.isEmpty()) 0 else generalScore[ch.codePointAt(0)] ?: 0

    /** 復刻 speller/algebra：nz→y、th→w、ng→1、(\w)o→$1M→4、(\w)u→$1V→3 + xlit。 */
    private fun codeOf(syllable: String): String {
        var s = syllable.replace("nz", "y").replace("th", "w").replace("ng", "1")
        s = Regex("(\\w)o").replace(s) { it.groupValues[1] + "M" }
        s = Regex("(\\w)u").replace(s) { it.groupValues[1] + "V" }
        s = s.replace("M", "4").replace("V", "3")
        val xlit = "abcdefghiklmnpsvrzjqt" to "aaaeeeiii554477338899"
        val sb = StringBuilder(s.length)
        for (c in s) {
            val idx = xlit.first.indexOf(c)
            sb.append(if (idx >= 0) xlit.second[idx] else c)
        }
        return sb.toString()
    }

    /** code -> 音節列表（生產代碼的 `codeToSyllables` 等價物）。 */
    private fun codeToSyllables(): Map<String, MutableList<String>> {
        val out = HashMap<String, MutableList<String>>()
        syllableToChars.keys.forEach { syllable ->
            out.getOrPut(codeOf(syllable)) { ArrayList() }.add(syllable)
        }
        return out
    }

    /**
     * 與 `TwelveKeyPreedit.syllablesForSegment` 同規則（用戶 2026-11-10 定案）：
     * - 精確碼的音節排最前；
     * - 前綴命中的音節**按首字母分桶**，桶內按詞頻降序，桶間輪轉；
     * - [limit] 是**每個首字母**的名額，總數上限 [MAX_TOTAL]（=16）。
     */
    private fun syllablesForSegment(
        code: String,
        limit: Int,
        totalLimit: Int = MAX_TOTAL,
    ): List<String> {
        val byCode = codeToSyllables()
        val exact = ArrayList<String>()
        val buckets = LinkedHashMap<Char, MutableList<String>>()
        byCode.forEach { (syllableCode, syllables) ->
            when {
                syllableCode == code -> exact.addAll(syllables)
                syllableCode.startsWith(code) -> syllables.forEach { s ->
                    buckets.getOrPut(s.first()) { ArrayList() }.add(s)
                }
            }
        }
        val byFreq = compareByDescending<String> { syllableFreq[it] ?: 0 }
        val exactSorted = exact.sortedWith(byFreq).distinct()
        buckets.values.forEach { it.sortWith(byFreq) }
        val order = buckets.values.sortedByDescending { it.firstOrNull()?.let { s -> syllableFreq[s] ?: 0 } ?: 0 }
        val cap = maxOf(limit, minOf(limit * order.size, totalLimit))
        val diversified = ArrayList<String>()
        val seen = IntArray(order.size)
        var round = 0
        while (diversified.size + exactSorted.size < cap &&
            order.indices.any { i -> round < order[i].size && seen[i] < limit }
        ) {
            order.forEachIndexed { i, list ->
                if (round < list.size && seen[i] < limit && diversified.size + exactSorted.size < cap) {
                    diversified.add(list[round])
                    seen[i]++
                }
            }
            round++
        }
        return (exactSorted + diversified).distinct().take(maxOf(limit, cap))
    }

    private companion object {
        /** 與 `TwelveKeyPreedit.MAX_SYLLABLES_TOTAL` 保持一致。 */
        const val MAX_TOTAL = 16
    }

    private fun syllableMass(syllable: String): Int =
        syllableToChars[syllable]?.sumOf { scoreOf(it) } ?: 0

    /** 第一個待選段可能落到的音節總量（按字頻加權）。 */
    private fun totalMass(head: String): Int {
        val syllables = syllablesForSegment(head, Int.MAX_VALUE)
        return syllables.sumOf { syllableMass(it) }.coerceAtLeast(1)
    }

    /** 前 n 個音節組覆蓋的「字頻質量」比例（0..1）。 */
    private fun coverage(head: String, n: Int): Double {
        val syllables = syllablesForSegment(head, n)
        return syllables.sumOf { syllableMass(it) }.toDouble() / totalMass(head)
    }

    private val heads = "y9ae1i54w738".map { it.toString() }

    @Test
    fun `analyse coverage for candidate group counts`() {
        load()
        println("head   #syl   N4     N6     N8     N10    N12")
        for (head in heads) {
            val total = syllablesForSegment(head, Int.MAX_VALUE).size
            val line = buildString {
                append("%-5s %5d  ".format(head, total))
                for (n in listOf(4, 6, 8, 10, 12)) {
                    append("%6.1f%%".format(coverage(head, n) * 100))
                }
            }
            println(line)
        }
        for (n in listOf(4, 5, 6, 7, 8, 10, 12)) {
            val covs = heads.map { coverage(it, n) }
            println(
                "N=%2d  min=%5.1f%%  avg=%5.1f%%  max=%5.1f%%".format(
                    n,
                    covs.minOf { it } * 100,
                    covs.average() * 100,
                    covs.maxOf { it } * 100,
                ),
            )
        }
    }

    /**
     * 選定的常數：面板左列**每個首字母**最多列 8 個高頻音節組（多首字母合計上限 16）。
     *
     * 依據（見上面打印的表，12 個首碼的實測）：
     * - 一屏可見 6 行，8 組 = 一屏 + 兩行滾動；
     * - 「每個首字母 8 個」實測：平均覆蓋 55.3%、最低首碼 44.9%（舊的「總數 8」只有 44.2% / 28.8%）；
     *   往上再給到 10/12 只多 2–4 個百分點，收益遞減；
     * - 剩下的音節基本是生僻讀音，且用戶可以「繼續打碼」精確化，不必全羅列
     *   （用戶 2026-11-10：「不可能每個首字母的所有組合都羅列」）。
     * 若日後字表/詞典變化很大，跑這個測試看錶再改常數即可。
     */
    @Test
    fun `segment syllable limit is 16 or 24 for rich codes`() {
        load()
        // 用戶 2026-11-10：有段組時，一個編碼給出的音節上限 16；音節構成很豐富的（可用 > 16）給 24。
        // 2026-11-10 起這些配額集中在 TwelveKeyTuning（用戶要求常數好找好改）
        val tuningSource = File("src/main/java/com/hazukieq/hkrime/core/twelvekey/TwelveKeyTuning.kt")
            .readText()
        assertTrue(
            "應有 SEGMENT_SYLLABLE_LIMIT = 16",
            tuningSource.contains("SEGMENT_SYLLABLE_LIMIT = 16"),
        )
        assertTrue(
            "應有 SEGMENT_SYLLABLE_LIMIT_RICH = 24",
            tuningSource.contains("SEGMENT_SYLLABLE_LIMIT_RICH = 24"),
        )

        heads.forEach { head ->
            val available = syllablesForSegment(head, Int.MAX_VALUE)
            val limit = if (available.size > 16) 24 else 16
            val got = syllablesForSegment(head, limit, totalLimit = limit)
            // 上限就是「min(可用音節數, 16 或 24)」；音節不足的鍵自然拿不滿。
            assertEquals(
                "head=$head available=${available.size} limit=$limit",
                minOf(available.size, limit),
                got.size,
            )
            println("head=$head available=${available.size} -> ${got.size}")
        }
    }

    @Test
    fun `every head code has at least one syllable group`() {
        load()
        heads.forEach { head ->
            assertTrue("head=$head 沒有任何音節", syllablesForSegment(head, 1).isNotEmpty())
        }
    }
}
