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
    fun `rankBar keeps engine order, truncates and drops empties`() {
        val ranked = listOf(
            item("以", index = 0),
            item("你好", index = 1),
            item("", index = 2), // 空文字不顯示
            item("而", index = 3),
            item("爾雅", index = 4),
            item("abc", index = 5),
        )
        val bar = CandidateRanking.rankBar(ranked, barSize = 4)
        assertEquals(listOf("以", "你好", "而", "爾雅"), bar.map { it.text })
        // 原始下標必須保留（點擊要回傳引擎全局下標）
        assertEquals(listOf(0, 1, 3, 4), bar.map { it.index })
    }

    @Test
    fun `rankBar does not put words before single chars`() {
        // 詞與單字保留引擎順序。
        val ranked = listOf(
            item("以", index = 0),
            item("你好", index = 1),
            item("而", index = 2),
            item("爾雅", index = 3),
        )
        val bar = CandidateRanking.rankBar(ranked, barSize = 12)
        assertEquals(listOf("以", "你好", "而", "爾雅"), bar.map { it.text })
    }

    @Test
    fun `rankBar dedupes identical texts but keeps engine order`() {
        val ranked = listOf(
            item("半截碼", "ban zet ma", 0),
            item("半截碼", "ban zet ma", 1),
            item("半截", "ban zet", 2),
            item("半", "ban", 3),
        )
        val bar = CandidateRanking.rankBar(ranked, barSize = 12)
        assertEquals(listOf("半截碼", "半截", "半"), bar.map { it.text })
        assertEquals(listOf(0, 2, 3), bar.map { it.index })
    }

    @Test
    fun `rankBar on empty or blank input returns what remains`() {
        assertEquals(emptyList<CandidateItem>(), CandidateRanking.rankBar(emptyList(), barSize = 12))
        assertEquals(
            emptyList<CandidateItem>(),
            CandidateRanking.rankBar(listOf(item("", index = 0), item("", index = 1)), barSize = 12),
        )
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
            val parts = raw.split('\t')
            val ch = parts[0].trim()
            if (ch.length == 1 && parts.getOrNull(1)?.trim() == syllable && !out.contains(ch)) out.add(ch)
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
        // 單字在前或詞在前都不作類型分區。
        assertEquals(engine.take(9).map { it.text }, bar.map { it.text })
        assertEquals(engine.take(9).map { it.index }, bar.map { it.index })
    }

    // 左列各檔按讀音字典序排列。
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
        // 單節與多節檔各自排序。
        assertEquals(listOf("a", "b", "b a", "…"), ordered.map { it.reading })
    }

    @Test
    fun orderGroupsKeepsAssociationRowsInTheirOwnBucket() {
        // 不同檔位不互相混排。
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

    // -------------------------------------------------- 學習排序（少量調整，尊重引擎順序）

    @Test
    fun `rankWithLearning keeps engine order when nothing learned`() {
        val engine = listOf(
            item("共产党", "kiung than dong", 0),
            item("共产", "kiung than", 1),
            item("穷", "kiung", 2),
        )
        val ranked = CandidateRanking.rankWithLearning(engine) { 0 }
        assertEquals(engine.map { it.text }, ranked.map { it.text })
    }

    @Test
    fun `rankWithLearning lifts picked word but keeps engine order for the rest`() {
        val engine = listOf(
            item("穷酸", "kiung thon", 0),
            item("共产", "kiung than", 1),
            item("穷", "kiung", 2),
        )
        val ranked = CandidateRanking.rankWithLearning(engine) {
            if (it.text == "共产") 9000 else 0
        }
        assertEquals(listOf("共产", "穷酸", "穷"), ranked.map { it.text })
    }

    @Test
    fun `rankWithLearning lets single char compete equally`() {
        // 已學單字也能依分數提升至首位。
        val engine = listOf(
            item("里", "li", 0),
            item("理会", "li voi", 1),
            item("柳眉", "liu mei", 2),
            item("柳", "liu", 3),
        )
        val ranked = CandidateRanking.rankWithLearning(engine) {
            when (it.text) {
                "里" -> 9000
                "柳眉" -> 8000
                else -> 0
            }
        }
        assertEquals(listOf("里", "柳眉", "理会", "柳"), ranked.map { it.text })
    }

    @Test
    fun `rankWithLearning falls back to engine order when scores all zero`() {
        val engine = listOf(item("甲", "", 0), item("乙", "", 1))
        val ranked = CandidateRanking.rankWithLearning(engine) { 0 }
        assertEquals(listOf("甲", "乙"), ranked.map { it.text })
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

    // ------------------------------------------------------------------ 推薦組（候選條續頁）

    @Test
    fun `fullBarOrder keeps given order and dedupes texts`() {
        // 混排結果只按文字去重，不另作詞/字分區。
        val ranked = listOf(
            item("以", index = 0),
            item("你好", index = 1),
            item("爾雅", index = 2),
            item("吧", index = 3),
        )
        assertEquals(listOf("以", "你好", "爾雅", "吧"), CandidateRanking.fullBarOrder(ranked).map { it.text })
    }

    @Test
    fun `fullBarOrder dedupes by text keeping the first occurrence`() {
        val ranked = listOf(
            item("你好", index = 0),
            item("以", index = 1),
            item("你好", index = 2),
        )
        assertEquals(listOf("你好", "以"), CandidateRanking.fullBarOrder(ranked).map { it.text })
    }

    @Test
    fun `recommend puts 30 words before 10 single chars`() {
        fun cjk(n: Int): Char = (0x4E00 + n).toChar()
        val words = (1..35).map { i -> item("${cjk(i)}${cjk(i + 200)}", index = i) }
        val singles = (1..12).map { i -> item("${cjk(i + 900)}", index = 100 + i) }
        val recommend = TwelveKeySnapshotBuilder.recommendWords(words + singles, emptyList())
        assertEquals(40, recommend.size)
        assertEquals(30, recommend.count { CandidateRanking.isCjkWord(it.text) })
        assertEquals(10, recommend.count { CandidateRanking.isSingleCjkChar(it.text) })
        // 詞在前、單字在後
        assertEquals(true, CandidateRanking.isCjkWord(recommend[29].text))
        assertEquals(true, CandidateRanking.isSingleCjkChar(recommend[30].text))
    }

    @Test
    fun `recommend shows words in candidate bar order then single chars`() {
        // 推薦詞在前，單字先引擎再段組補位。
        val engineWords = listOf(item("你好", index = 0), item("爾雅", index = 1))
        val engineSingles = listOf(item("以", index = 2), item("夜", index = 3))
        val appWords = listOf(item("掛紙", index = 10), item("下把", index = 11))
        val panelRows = listOf(
            PanelRow("ga zi", appWords, segment = 1),
            PanelRow("以", listOf(item("以", "ji", -1, segment = 0)), segment = 0),
        )
        val recommend = TwelveKeySnapshotBuilder.recommendWords(engineWords + engineSingles, panelRows)
        assertEquals(listOf("你好", "爾雅", "掛紙", "下把", "以", "夜"), recommend.map { it.text })
    }

    @Test
    fun `recommend includes mixed CJK engine words like b站`() {
        // 混合文字詞如 b站 應參與推薦詞判定。
        val engine = listOf(item("你好", index = 0), item("b站", index = 1), item("Linux", index = 2))
        val recommend = TwelveKeySnapshotBuilder.recommendWords(engine, emptyList())
        assertEquals(listOf("你好", "b站"), recommend.map { it.text })
    }

    @Test
    fun `recommend dedupes words repeated between engine and panel rows`() {
        val engineWord = item("共產黨", "kiung than", index = 0)
        val dup = item("共產黨", "kiung than", index = 7)
        val panelRows = listOf(PanelRow("kiung than", listOf(dup, item("群眾", index = 8)), segment = 1))
        val recommend = TwelveKeySnapshotBuilder.recommendWords(listOf(engineWord), panelRows)
        assertEquals(listOf("共產黨", "群眾"), recommend.map { it.text })
        // 引擎那條優先（index 保留 0，不被面板補的 index=7 蓋掉）
        assertEquals(0, recommend.first().index)
    }

    @Test
    fun `recommend fills chars from panel rows when engine has few`() {
        val panelRows = listOf(
            PanelRow("以夜", listOf(item("以", "ji", -1, segment = 0), item("夜", "ja", -1, segment = 0)), segment = 0),
        )
        val recommend = TwelveKeySnapshotBuilder.recommendWords(emptyList(), panelRows)
        assertEquals(listOf("以", "夜"), recommend.map { it.text })
    }

    @Test
    fun `recommend is capped at 30 words and 10 chars`() {
        fun cjk(n: Int): Char = (0x4E00 + n).toChar()
        val words = (1..50).map { i -> item("${cjk(i)}${cjk(i + 200)}", index = i) }
        val recommend = TwelveKeySnapshotBuilder.recommendWords(words, emptyList())
        assertEquals(TwelveKeyTuning.RECOMMEND_WORDS, recommend.size)
        assertTrue(recommend.all { CandidateRanking.isCjkWord(it.text) })
    }

    @Test
    fun `recommend keeps only CJK engine words but includes ASCII words from panel rows`() {
        // 引擎候選裡的 ASCII（`Linux`）不算 CJK 詞組，不進「引擎段」；
        // 但它作為整串詞行的條目（`li nak si → Linux`）屬於聯想詞，推薦組要收。
        val engine = listOf(item("Linux", index = 0), item("你好", index = 1))
        val panelRows = listOf(
            PanelRow("li nak si", listOf(item("Linux", "li nak si", -1, segment = 1)), segment = 1),
        )
        val recommend = TwelveKeySnapshotBuilder.recommendWords(engine, panelRows)
        assertEquals(listOf("你好", "Linux"), recommend.map { it.text })
    }
}
