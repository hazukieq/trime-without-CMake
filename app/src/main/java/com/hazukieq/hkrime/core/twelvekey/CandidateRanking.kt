package com.hazukieq.hkrime.core.twelvekey

/**
 * 候選排序的**純函數**集合（不依賴 Android / Rime / 全局狀態）。
 *
 * 為什麼單獨抽出來：固定候選區與展開面板的排序規則用戶已經改過很多輪（詞組優先、按段選字、
 * 權威字頻……），規則是「資料 + 比較鍵」決定的，最容易回歸。抽成純函數後可以在 JVM 單元測試裡
 * 用假的分數來源覆蓋全部分支，不用在手機上點 UI（見 app/src/test/.../CandidateRankingTest.kt）。
 *
 * 分數來源（字頻/用戶記憶）用 lambda 注入，生產代碼傳 [SegmentMemory] 與 [TwelveKeyPreedit]。
 */
object CandidateRanking {

    /** 漢字詞組：≥2 個漢字（含擴展 A）。顏文字、ASCII、單字都不算。 */
    fun isCjkWord(text: String): Boolean =
        text.length >= 2 && text.all { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }

    /**
     * 固定候選區的「詞組優先」：對**已排好序**的候選做穩定分區。
     *
     * 要求（用戶 2026-11-09）：候選欄永遠「組詞 > 單字」，但必須在全部排序之後做，
     * 否則會蓋掉詞頻/用戶習慣的順序。單字、顏文字、拉丁詞之間保持原相對順序。
     */
    fun wordsFirst(items: List<CandidateItem>): List<CandidateItem> {
        if (items.size < 2) return items
        val words = ArrayList<CandidateItem>(items.size)
        val rest = ArrayList<CandidateItem>(items.size)
        items.forEach { if (isCjkWord(it.text)) words.add(it) else rest.add(it) }
        if (words.isEmpty() || rest.isEmpty()) return items
        return words + rest
    }

    /**
     * 固定候選區最終列表：**左邊詞組區（嚴格 ≤[wordSlots] 條）+ 右邊單字區** → 取前 [barSize] 條。
     *
     * 用戶 2026-11-11：詞組優先不變（永遠在左），但數量要嚴格限制 —— 否則多音節輸入時
     * 整個候選條被詞組佔滿，高頻單字一個都露不出來（24 鍵輸入實測「共产党 共产 理会 柳眉 球迷」全是詞）。
     * 超額的詞組不進候選條（仍留在 [ranked] 裡，展開面板看得到）。
     *
     * @param ranked 已按「引擎詞頻 / 用戶記憶 / 關聯度」排好的候選（順序即最終順序）
     * @param barSize 候選條總條數（詞組區 + 單字區）
     * @param wordSlots 詞組區上限（見 [TwelveKeyTuning.BAR_WORD_SLOTS]）
     */
    fun rankBar(
        ranked: List<CandidateItem>,
        barSize: Int = 12,
        wordSlots: Int = TwelveKeyTuning.BAR_WORD_SLOTS,
    ): List<CandidateItem> {
        if (ranked.isEmpty()) return ranked
        val words = ArrayList<CandidateItem>(ranked.size)
        val others = ArrayList<CandidateItem>(ranked.size)
        ranked.forEach { if (isCjkWord(it.text)) words.add(it) else others.add(it) }
        // 特例（用戶 2026-11-11 原話）：**完全沒有單字時**，詞組才可以佔滿整條候選條
        // —— 只要存在任何單字（含 ASCII/顏文字等非詞組候選），詞組區仍然嚴格 ≤ wordSlots，
        // 即使候選條因此變短也不拿詞組去補（用戶明確：不做「單字不夠就補詞」的推廣）。
        if (others.isEmpty()) {
            return words.take(barSize).filter { it.text.isNotEmpty() }
        }
        val head = if (wordSlots > 0) words.take(wordSlots) else emptyList()
        return (head + others).take(barSize).filter { it.text.isNotEmpty() }
    }

    /**
     * 把 App 側學到的合成候選與引擎候選**混排**（用戶 2026-11-11 選擇的口徑）。
     *
     * - 合成候選分數 = (次數-1) × 時間衰減（見 [PhraseMemory.score]）；
     * - 引擎候選的偽分數 = `1 - i/n`（引擎第 0 條 ≈1，越往後越低）——讓「學過的詞」能與
     *   「引擎自己排的詞」放在同一把尺上比；
     * - 分數高者在前；**同分時引擎候選在前**（用戶明確：同分引擎優先，學到的詞不無條件霸榜）。
     */
    fun mergePhraseCandidates(
        ranked: List<CandidateItem>,
        synthetic: List<CandidateItem>,
        syntheticScore: (CandidateItem) -> Double,
        engineScore: (Int) -> Double,
    ): List<CandidateItem> {
        if (synthetic.isEmpty()) return ranked
        val merged = ArrayList<Pair<CandidateItem, Double>>(ranked.size + synthetic.size)
        ranked.forEachIndexed { i, item -> merged.add(item to engineScore(i)) }
        synthetic.forEach { merged.add(it to syntheticScore(it)) }
        return merged.sortedWith(
            compareByDescending<Pair<CandidateItem, Double>> { it.second }
                .thenBy { if (it.first.segment == SYNTHETIC_SEGMENT) 1 else 0 },
        ).map { it.first }
    }

    /**
     * 展開面板右側「音節 → 漢字」列表排序：
     * 用戶常用字 → 綜合通用頻度（詞表統計 + 權威 8105 字頻）→ 引擎原順序（穩定）。
     *
     * 引擎順序對單字不可靠（詞典無 weight 時基本是文件順序，生僻字會排前面），
     * 所以用頻度做硬排序，只在同分時才看引擎下標。
     */
    fun rankPanelItems(
        items: List<CandidateItem>,
        charScore: (String) -> Int,
        generalScore: (String) -> Int,
    ): List<CandidateItem> {
        if (items.size < 2) return items
        return items.sortedWith(
            compareByDescending<CandidateItem> { charScore(it.text) }
                .thenByDescending { generalScore(it.text) }
                .thenBy { it.index },
        )
    }

    /**
     * 按段選字時「該段能選的字」排序：
     * 與上一字的組詞關聯度（bigram）→ 用戶常用字 → 綜合通用頻度（由調用方在 [tieBreak] 裡決定）。
     */
    fun rankSegmentChars(
        chars: List<String>,
        anchor: Char?,
        bigramScore: (Char?, String) -> Int,
        charScore: (String) -> Int,
        tieBreak: (String) -> Int = { 0 },
    ): List<String> = chars.sortedWith(
        compareByDescending<String> { bigramScore(anchor, it) }
            .thenByDescending { charScore(it) }
            .thenByDescending { tieBreak(it) },
    )

    /** comment 的音節數（空白分隔）；空 comment 記 0。 */
    fun syllableCount(comment: String): Int =
        if (comment.isBlank()) 0 else comment.split(' ').count { it.isNotBlank() }

    /**
     * 套用「用戶用字記憶」的排序（候選條用）。
     *
     * 主鍵 = **覆蓋深度**（候選讀音的音節數，封頂到引擎首條的深度），次鍵 = 學習分數，再次 = 引擎下標。
     *
     * 為甚麼要做覆蓋深度：`共產` 的讀音是 `kiung than`（2 音節），`共產黨` 是 `kiung than dong`（3 音節）。
     * 用戶打的是三音節的碼，但因為以前選過「共產」，學習分數把它頂到第一 ——
     * 候選條第一條變成只覆蓋前兩音節的詞，preedit 也就只顯示 `kiung than`，
     * 第三個音節看起來「被吃掉」（用戶 2026-11-10 反饋）。
     * 覆蓋更深的候選永遠排在前面，學習只在同一深度內決定先後。
     *
     * @param topSyllables 引擎首條候選的音節數（= 引擎認為這段碼最完整的讀法）；≤0 表示未知，退化為純學習排序
     * @param score 學習分數（[SelectionMemory.score] 之類），分數高的排前
     */
    fun rankWithLearning(
        items: List<CandidateItem>,
        topSyllables: Int,
        score: (CandidateItem) -> Int,
    ): List<CandidateItem> = items.sortedWith(
        compareByDescending<CandidateItem> {
            if (topSyllables <= 0) 0 else minOf(syllableCount(it.comment), topSyllables)
        }.thenByDescending { score(it) }
            .thenBy { it.index },
    )

    /**
     * 按 comment 分組（面板左列）：空白 comment 併成一組「…」並排在最後；
     * 其餘組順序 = 單音節組在前（先手動選單字，用戶 2026-11-08），再按音節數升序，同音節數按 reading。
     */
    fun groupCandidates(
        items: List<CandidateItem>,
        blankReading: String = "…",
        maxGroups: Int = 200,
    ): List<SyllableGroup> {
        if (items.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<CandidateItem>>()
        items.forEach { item ->
            if (item.text.isEmpty()) return@forEach
            val reading = if (item.comment.isBlank()) blankReading else item.comment
            grouped.getOrPut(reading) { ArrayList() }.add(item)
        }
        return grouped.entries.take(maxGroups).map { (reading, groupItems) ->
            SyllableGroup(
                reading = reading,
                syllables = if (reading == blankReading) emptyList() else {
                    reading.split(' ').filter { it.isNotBlank() }
                },
                items = groupItems,
            )
        }.sortedWith(
            // 讀音未知（comment 空）的組永遠排最後：它是兜底分組，不該擋在真正的單字組前面。
            compareBy(
                { if (it.syllables.isEmpty()) Int.MAX_VALUE else it.syllables.size },
                { it.reading.lowercase() },
            ),
        )
    }

    /**
     * 面板左列「段組 + 引擎組」的合併規則（用戶 2026-11-10）。
     *
     * 有段組（按碼位自切的單字組，已按「每個首字母 8 個」限量）時，引擎那邊的
     * **單音節組**只是同一批字的另一種排法：全列出來左列會被 g* 的幾十個讀音灌滿。
     * 因此只保留引擎的**多音節（詞組）組**；沒有段組時（單音節輸入）引擎組照舊全留。
     */
    fun panelGroups(
        segmentGroups: List<SyllableGroup>,
        engineGroups: List<SyllableGroup>,
    ): List<SyllableGroup> {
        if (segmentGroups.isEmpty()) return engineGroups
        val words = engineGroups.filter { it.syllables.size >= 2 }
        return segmentGroups + words
    }

    /**
     * 把「聯想詞組」並進面板分組（用戶 2026-11-10）。
     *
     * 規則：**同讀音就合併進引擎那一組**（引擎候選在前，聯想詞補在後面、按詞去重）；
     * 引擎沒有這個讀音的組時，才新建一組（此時它就是補集）。
     * 這樣行標題與引擎完全一致（`song hau`、`song heu`…），不會出現 `h**` 這種通配寫法。
     */
    fun mergeAssociationGroups(
        base: List<SyllableGroup>,
        associations: List<SyllableGroup>,
    ): List<SyllableGroup> {
        if (associations.isEmpty()) return base
        val result = base.toMutableList()
        associations.forEach { assoc ->
            val idx = result.indexOfFirst { it.reading == assoc.reading }
            if (idx < 0) {
                result.add(assoc)
            } else {
                val existing = result[idx]
                val extra = assoc.items.filter { candidate ->
                    existing.items.none { it.text == candidate.text }
                }
                if (extra.isNotEmpty()) {
                    result[idx] = existing.copy(items = existing.items + extra)
                }
            }
        }
        return result
    }

    /**
     * 左列的最終顯示順序（用戶 2026-11-10）：
     * 單音節輸入時左列全是「同一批音節」的組，必須按常用度排（常用音節在前），
     * 不能按讀音字母序 —— 否則找想要的音節要找半天。
     *
     * 排序鍵：單音節組 → 多音節（詞組）組 → 讀音未知；同類內按組內第一個字的
     * 用戶字頻、通用字頻降序，最後用讀音兜底保證穩定。數量不做限制（引擎給多少列多少）。
     */
    /**
     * 左列分組排序（用戶 2026-11-10 定案）：
     * **先按首字母、再按次字母**——即讀音字典序，而不是常用度降序。
     * 高頻音節靠常用度只決定「留哪些」，留在列表裡之後一律按字母排，掃讀才找得到。
     *
     * 分三檔：單音節組 → 詞組（多音節）→ 讀音未知（空 comment）組；
     * 每檔內部都按 reading 字典序（空 comment 組用第一條候選字兜底）。
     */
    fun orderGroups(
        groups: List<SyllableGroup>,
        @Suppress("UNUSED_PARAMETER") charScore: (String) -> Int = { 0 },
        @Suppress("UNUSED_PARAMETER") generalScore: (String) -> Int = { 0 },
    ): List<SyllableGroup> = groups.sortedWith(
        compareBy(
            // 分檔：單音節組 → 聯想詞組（App 合成）→ 引擎詞組 → 無讀音組；
            // 每檔內部各自按字典序排，互不摻和（用戶 2026-11-10：「單音節排自己的，聯想詞排自己的」）。
            {
                when {
                    it.syllables.isEmpty() -> 3
                    it.segment == 1 -> 1
                    it.syllables.size == 1 -> 0
                    else -> 2
                }
            },
            { it.reading.lowercase() },
            { it.items.firstOrNull()?.text ?: "" },
        ),
    )
}
