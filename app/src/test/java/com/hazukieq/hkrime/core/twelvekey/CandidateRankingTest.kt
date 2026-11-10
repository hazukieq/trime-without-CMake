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

    // ── mergeAssociationGroups：聯想詞按讀音併進引擎組（用戶 2026-11-10）────────
    @Test
    fun mergeAssociationGroupsMergesSameReadingAndAppendsNewOnes() {
        fun eng(reading: String, vararg texts: String) = SyllableGroup(
            reading = reading,
            syllables = reading.split(' '),
            items = texts.mapIndexed { i, t -> item(t, reading, i) },
        )
        fun assoc(reading: String, vararg texts: String) = SyllableGroup(
            reading = reading,
            syllables = reading.split(' '),
            items = texts.map { t -> item(t, reading, -1).copy(segment = 1) },
            segment = 1,
        )
        val engine = listOf(eng("song hau", "上號", "上好"), eng("son gi", "船記", "船"))
        val assocRows = listOf(assoc("song hau", "上好", "上光"), assoc("song heu", "上下", "上學"))
        val merged = CandidateRanking.mergeAssociationGroups(engine, assocRows)
        assertEquals(3, merged.size)
        // 同讀音：引擎在前，聯想詞去重後補在後面；組標記保留引擎組的（segment=0）
        assertEquals(listOf("上號", "上好", "上光"), merged[0].items.map { it.text })
        assertEquals(-1, merged[0].segment) // 引擎組原樣，只是補了詞
        // 引擎沒有這個讀音 → 新建一組（補集，segment=1 整詞上屏）
        assertEquals("song heu", merged[2].reading)
        assertEquals(listOf("上下", "上學"), merged[2].items.map { it.text })
        assertEquals(1, merged[2].segment)
    }

    // ── orderGroups：單音節輸入時左列按常用度排（用戶 2026-11-10）───────────────
    @Test
    fun orderGroupsSortsAlphabetically() {
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
        // 單音節組按讀音字母序（a → b），詞組其次，未知讀音最後（用戶 2026-11-10 定案）
        assertEquals(listOf("a", "b", "b a", "…"), ordered.map { it.reading })
    }

    @Test
    fun orderGroupsKeepsAssociationRowsInTheirOwnBucket() {
        // 用戶 2026-11-10：「單音節排自己的，聯想詞排自己的，不要搞混」。
        // 分檔必須是：單音節 → 聯想詞(segment=1) → 引擎詞組 → 無讀音。
        val single = CandidateRanking.groupCandidates(listOf(item("共", "kiung", 0))).first()
        val assoc = CandidateRanking.groupCandidates(listOf(item("共產黨", "kiung than", 1)))
            .first().copy(segment = 1)
        val word = CandidateRanking.groupCandidates(listOf(item("共生", "kiung sen", 2))).first()
        val blank = CandidateRanking.groupCandidates(listOf(item("?", "", 3))).first()
        val ordered = CandidateRanking.orderGroups(listOf(blank, word, assoc, single))
        assertEquals(
            listOf("kiung", "kiung than", "kiung sen", "…"),
            ordered.map { it.reading },
        )
    }

    // -------------------------------------------------- 學習排序（覆蓋深度優先）

    @Test
    fun rankWithLearningKeepsDeeperCandidateFirst() {
        // 用戶 2026-11-10：「打完整 kiung than dong，最後一個 dong 被吃掉」。
        // 候選條第一條必須是覆蓋三音節的「共產黨」，不能是以前選過、只覆蓋兩音節的「共產」。
        val engine = listOf(
            item("共产党", "kiung than dong", 0),
            item("共产", "kiung than", 1),
            item("穷", "kiung", 2),
        )
        val learned = mapOf("共产" to 9000, "穷" to 9000)
        val ranked = CandidateRanking.rankWithLearning(engine, topSyllables = 3) { learned[it.text] ?: 0 }
        assertEquals(listOf("共产党", "共产", "穷"), ranked.map { it.text })
    }

    @Test
    fun rankWithLearningReordersWithinSameDepth() {
        // 同一覆蓋深度（都是兩音節）：學習分數說話 —— 這是「選過的詞往前排」的本意。
        val engine = listOf(
            item("穷酸", "kiung thon", 0),
            item("共产", "kiung than", 1),
            item("穷", "kiung", 2),
        )
        val ranked = CandidateRanking.rankWithLearning(engine, topSyllables = 2) {
            if (it.text == "共产") 9000 else 0
        }
        assertEquals(listOf("共产", "穷酸", "穷"), ranked.map { it.text })
    }

    @Test
    fun rankWithLearningHandlesSingleCharDepth() {
        // 兩音節輸入時單字是「淺一層」：學習過也要排在兩音節詞後面（詞組優先），
        // 但同為單字時學習分數仍然有效。
        val engine = listOf(
            item("里", "li", 0),
            item("理会", "li voi", 1),
            item("柳眉", "liu mei", 2),
            item("柳", "liu", 3),
        )
        val ranked = CandidateRanking.rankWithLearning(engine, topSyllables = 2) {
            when (it.text) {
                "里" -> 9000
                "柳眉" -> 8000
                else -> 0
            }
        }
        assertEquals(listOf("柳眉", "理会", "里", "柳"), ranked.map { it.text })
    }

    @Test
    fun rankWithLearningFallsBackToPureLearningWhenDepthUnknown() {
        // 引擎沒給 comment（方案沒開 spelling_hints）→ 深度未知，退化為純學習排序。
        val engine = listOf(item("甲", "", 0), item("乙", "", 1))
        val ranked = CandidateRanking.rankWithLearning(engine, topSyllables = 0) {
            if (it.text == "乙") 9000 else 0
        }
        assertEquals(listOf("乙", "甲"), ranked.map { it.text })
    }

    @Test
    fun syllableCountCountsSpaces() {
        assertEquals(3, CandidateRanking.syllableCount("kiung than dong"))
        assertEquals(1, CandidateRanking.syllableCount("kiung"))
        assertEquals(0, CandidateRanking.syllableCount(""))
        assertEquals(0, CandidateRanking.syllableCount("   "))
    }

    @Test
    fun orderGroupsKeepsEngineOrderWhenScoresTie() {
        val groups = (1..4).map { CandidateRanking.groupCandidates(listOf(item("字$it", "ji", it))).first() }
        val ordered = CandidateRanking.orderGroups(groups, { 0 }, { 0 })
        assertEquals(groups.map { it.reading }, ordered.map { it.reading })
    }
}
