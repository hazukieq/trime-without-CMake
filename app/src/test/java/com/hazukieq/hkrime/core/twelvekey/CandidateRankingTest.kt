package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 固定候選區 + 展開面板的排序規則測試（純 JVM，不點 UI）。
 *
 * 兩個版塊的規則都是「比較鍵的字典序 + 穩定排序」，最容易在後續改動裡悄悄回歸；
 * 這裡把每一級比較鍵、分區、截斷、分組都覆蓋到，並用真實字表/詞表做一次數據校驗。
 */
class CandidateRankingTest {

    private fun item(text: String, comment: String = "", index: Int = 0, segment: Int = -1) =
        CandidateItem(text = text, comment = comment, index = index, segment = segment)

    // ------------------------------------------------------------------ 固定候選區

    @Test
    fun `isCjkWord only accepts 2 plus CJK ideographs`() {
        assertTrue(CandidateRanking.isCjkWord("你好"))
        assertTrue(CandidateRanking.isCjkWord("㐀㐁")) // 擴展 A（0x3400..0x4DBF）
        assertFalse(CandidateRanking.isCjkWord("")) // 空
        assertFalse(CandidateRanking.isCjkWord("你")) // 單字
        assertFalse(CandidateRanking.isCjkWord("abc")) // 拉丁
        assertFalse(CandidateRanking.isCjkWord("(＞﹏＜)")) // 顏文字（含非漢字）
        assertFalse(CandidateRanking.isCjkWord("你a")) // 混合
    }

    @Test
    fun `wordsFirst is a stable partition - words keep their order, rest keeps theirs`() {
        val input = listOf(
            item("以", index = 0),
            item("你好", index = 1),
            item("abc", index = 2),
            item("爾雅", index = 3),
            item("吧", index = 4),
            item("(＞﹏＜)", index = 5),
        )
        val out = CandidateRanking.wordsFirst(input)
        assertEquals(listOf("你好", "爾雅", "以", "abc", "吧", "(＞﹏＜)"), out.map { it.text })
        // 穩定：兩個詞組之間、其餘項目之間各自保持原順序（用 index 驗證）
        assertEquals(listOf(1, 3, 0, 2, 4, 5), out.map { it.index })
    }

    @Test
    fun `wordsFirst leaves list untouched when all or none are words`() {
        val allWords = listOf(item("你好", index = 1), item("再見", index = 0))
        assertEquals(allWords, CandidateRanking.wordsFirst(allWords))
        val allSingles = listOf(item("你", index = 1), item("a", index = 0))
        assertEquals(allSingles, CandidateRanking.wordsFirst(allSingles))
        assertEquals(emptyList<CandidateItem>(), CandidateRanking.wordsFirst(emptyList()))
    }

    @Test
    fun `rankBar puts words first, truncates to bar size, drops empty entries`() {
        val ranked = listOf(
            item("以", index = 0),
            item("而", index = 1),
            item("", index = 2), // 空文字不顯示
            item("你好", index = 3),
            item("abc", index = 4),
            item("爾雅", index = 5),
        )
        val bar = CandidateRanking.rankBar(ranked, barSize = 4)
        assertEquals(listOf("你好", "爾雅", "以", "而"), bar.map { it.text })
        // 原始下標必須保留（點擊要回傳引擎全局下標）
        assertEquals(listOf(3, 5, 0, 1), bar.map { it.index })
    }

    @Test
    fun `rankBar keeps engine order among single chars`() {
        val ranked = (1..20).map { item("字$it", index = it) }
        val bar = CandidateRanking.rankBar(ranked, barSize = 12)
        assertEquals(12, bar.size)
        assertEquals((1..12).toList(), bar.map { it.index })
    }

    // ------------------------------------------------------------------ 展開面板

    @Test
    fun `rankPanelItems prefers user char score then general score then engine index`() {
        val items = listOf(
            item("甲", index = 5), // 用户 0 / 通用 9000
            item("乙", index = 4), // 用户 0 / 通用 9000（同分 → index 小者前）
            item("丙", index = 3), // 用户 7 / 通用 0
            item("丁", index = 2), // 用户 0 / 通用 100
        )
        val charScore = mapOf("丙" to 7)
        val general = mapOf("甲" to 9000, "乙" to 9000, "丁" to 100)
        val out = CandidateRanking.rankPanelItems(
            items,
            charScore = { charScore[it] ?: 0 },
            generalScore = { general[it] ?: 0 },
        )
        assertEquals(listOf("丙", "乙", "甲", "丁"), out.map { it.text })
    }

    @Test
    fun `rankPanelItems keeps single item and stable when all scores equal`() {
        val one = listOf(item("以", index = 9))
        assertEquals(one, CandidateRanking.rankPanelItems(one, { 0 }, { 0 }))
        val many = listOf(item("以", index = 3), item("而", index = 1), item("如", index = 2))
        assertEquals(listOf(1, 2, 3), CandidateRanking.rankPanelItems(many, { 0 }, { 0 }).map { it.index })
    }

    @Test
    fun `rankSegmentChars anchors on previous char then user score then tie break`() {
        val chars = listOf("域", "為", "區", "位")
        val bigram = mapOf("區區" to 10, "區域" to 50, "区位" to 0)
        val userScore = mapOf("為" to 99)
        val out = CandidateRanking.rankSegmentChars(
            chars = chars,
            anchor = '區',
            bigramScore = { anchor, text -> bigram[(anchor ?: ' ').toString() + text] ?: 0 },
            charScore = { userScore[it] ?: 0 },
            tieBreak = { 0 },
        )
        // 「域」與前一字關聯最高（bigram 50）；其次「區」（bigram 10）優先於用戶常用字「為」——
        // 關聯度是第一級比較鍵；「位」兩項都是 0，靠穩定保序留在最後
        assertEquals(listOf("域", "區", "為", "位"), out)
    }

    @Test
    fun `groupCandidates puts single syllable groups first and blank comment last`() {
        val items = listOf(
            item("你好", comment = "ni hau", index = 0),
            item("惹", comment = "nzia", index = 1),
            item("而已", comment = "nzi ji", index = 2),
            item("以", comment = "ji", index = 3),
            item("…字", comment = "", index = 4),
            item("", comment = "x", index = 5), // 空文字被丟棄
        )
        val groups = CandidateRanking.groupCandidates(items, blankReading = "…", maxGroups = 200)
        // 單音節組最前（ji → nzia），再按音節數升序，空讀音（…）永遠最後
        assertEquals(listOf("ji", "nzia", "ni hau", "nzi ji", "…"), groups.map { it.reading })
        assertEquals(listOf("ji"), groups[0].syllables)
        assertTrue(groups.last().syllables.isEmpty())
        assertEquals(listOf("惹"), groups[1].items.map { it.text })
    }

    @Test
    fun `groupCandidates preserves item order inside a group and caps group count`() {
        val items = (1..5).map { item("字$it", comment = "ji", index = it) } +
            (1..5).map { item("詞$it", comment = "ji ha", index = 100 + it) }
        val groups = CandidateRanking.groupCandidates(items)
        assertEquals(2, groups.size)
        assertEquals((1..5).toList(), groups[0].items.map { it.index })
        val capped = CandidateRanking.groupCandidates(items, maxGroups = 1)
        assertEquals(1, capped.size)
    }

    // ------------------------------------------------------------------ 真實數據校驗

    private val assetDir = File("src/main/assets/rime")

    private fun weights(name: String): Map<String, Int> {
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
        return ordered.foldIndexed(HashMap(n * 2)) { index, acc, entry ->
            acc[entry.key.codePointAt(0)] = (n - index) * 10000 / n
            acc
        }
    }

    private fun generalScores(): Map<Int, Int> {
        val byFreq = percentile(weights("hakka_char_freq.txt"))
        val byRank = percentile(weights("hakka_char_rank.txt"))
        val out = HashMap<Int, Int>(byFreq.size + byRank.size)
        byFreq.forEach { (cp, score) -> out[cp] = score }
        byRank.forEach { (cp, score) -> out[cp] = maxOf(out[cp] ?: 0, score) }
        return out
    }

    /** 真實字表裡某個讀音的字（按詞典出現順序 = 引擎「無 weight」時的文件順序）。 */
    private fun charsOfSyllable(syllable: String): List<String> {
        val out = ArrayList<String>()
        File(assetDir, "hakka.dict.yaml").forEachLine { raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@forEachLine
            val tab = raw.indexOf('\t')
            if (tab < 0) return@forEachLine
            val ch = raw.substring(0, tab).trim()
            if (ch.length == 1 && raw.substring(tab + 1).trim() == syllable && !out.contains(ch)) out.add(ch)
        }
        return out
    }

    @Test
    fun `real data - panel ordering is non increasing by general score for a ji group`() {
        val general = generalScores()
        val chars = charsOfSyllable("ji")
        assertTrue("字表裡應有 ji 讀音的字", chars.size >= 5)
        val items = chars.mapIndexed { index, ch -> item(ch, comment = "ji", index = index) }
        val ranked = CandidateRanking.rankPanelItems(items, { 0 }, { general[it.codePointAt(0)] ?: 0 })
        val scores = ranked.map { general[it.text.codePointAt(0)] ?: 0 }
        for (i in 0 until scores.size - 1) {
            assertTrue(
                "面板排序應按通用頻度非遞增：${ranked.map { it.text }}",
                scores[i] >= scores[i + 1],
            )
        }
        // 「以」是 ji 讀音裡的高頻字，必須排在最前面（生僻字不許插隊）
        assertEquals("以", ranked.first().text)
    }

    @Test
    fun `real data - bar puts word list entries before single chars`() {
        val words = ArrayList<String>()
        File(assetDir, "hakka_mobilewords.dict.yaml").forEachLine { raw ->
            if (words.size >= 5) return@forEachLine
            if (raw.isBlank() || raw.startsWith("#") || raw.contains(':')) return@forEachLine
            val word = raw.trim()
            if (word.length >= 2 && CandidateRanking.isCjkWord(word)) words.add(word)
        }
        val singles = listOf("以", "而", "如", "意")
        val engine = singles.mapIndexed { i, ch -> item(ch, comment = "ji", index = i) } +
            words.mapIndexed { i, w -> item(w, comment = "ji ji", index = 100 + i) }
        val bar = CandidateRanking.rankBar(engine, barSize = 9)
        // 前 N 條必須全是詞組（詞組數量不足時才輪到單字）
        val groupCut = minOf(words.size, bar.size)
        bar.take(groupCut).forEach { assertTrue("候選條前段應是詞組：${bar.map { it.text }}", CandidateRanking.isCjkWord(it.text)) }
        // 字表順序在單字之間保持
        val barSingles = bar.filter { !CandidateRanking.isCjkWord(it.text) }.map { it.text }
        assertEquals(singles.take(barSingles.size), barSingles)
    }

    // ── panelGroups：有段組時只保留引擎的詞組組（用戶 2026-11-10）───────────────
    @Test
    fun panelGroupsDropsEngineSingleSyllableGroupsWhenSegmentsExist() {
        val seg = listOf(CandidateRanking.groupCandidates(listOf(item("家", "ga", 0))).first())
        val engine = listOf(
            CandidateRanking.groupCandidates(listOf(item("加", "ga", 1))).first(),
            CandidateRanking.groupCandidates(listOf(item("家加", "ga ga", 2))).first(),
        )
        val merged = CandidateRanking.panelGroups(seg, engine)
        assertEquals("段組 + 引擎詞組組", 2, merged.size)
        assertEquals("段組應在最前", "ga", merged[0].reading)
        assertEquals("引擎的單音節組要被丟掉", "ga ga", merged[1].reading)
    }

    @Test
    fun panelGroupsKeepsAllEngineGroupsWhenNoSegments() {
        val engine = listOf(
            CandidateRanking.groupCandidates(listOf(item("加", "ga", 0))).first(),
            CandidateRanking.groupCandidates(listOf(item("家加", "ga ga", 1))).first(),
        )
        assertEquals(engine, CandidateRanking.panelGroups(emptyList(), engine))
    }

    // ── orderGroups：單音節輸入時左列按常用度排（用戶 2026-11-10）───────────────
    @Test
    fun orderGroupsSortsSingleSyllableGroupsByFrequency() {
        val g = { reading: String, text: String, index: Int ->
            CandidateRanking.groupCandidates(listOf(item(text, reading, index))).first()
        }
        // 引擎順序：a(低頻) / b(高頻) / c(詞組) / 未知讀音
        val groups = listOf(
            g("a", "阿", 0),
            g("b", "家", 1),
            CandidateRanking.groupCandidates(listOf(item("家加", "b a", 2))).first(),
            CandidateRanking.groupCandidates(listOf(item("?", "", 3))).first(),
        )
        val score = mapOf("阿" to 1, "家" to 99, "家加" to 50, "?" to 0)
        val ordered = CandidateRanking.orderGroups(groups, { score[it] ?: 0 }, { 0 })
        // 單音節組按分數降序（家 99 → 阿 1），詞組其次，未知讀音最後
        assertEquals(listOf("b", "a", "b a", "…"), ordered.map { it.reading })
    }

    @Test
    fun orderGroupsKeepsEngineOrderWhenScoresTie() {
        val groups = (1..4).map { CandidateRanking.groupCandidates(listOf(item("字$it", "ji", it))).first() }
        val ordered = CandidateRanking.orderGroups(groups, { 0 }, { 0 })
        assertEquals(groups.map { it.reading }, ordered.map { it.reading })
    }
}
