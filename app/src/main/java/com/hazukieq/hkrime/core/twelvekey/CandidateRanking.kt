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

    /** 含至少一個漢字（混合詞，如 `b站`、`QQ群`、`3D打印`）。 */
    fun containsCjk(text: String): Boolean =
        text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }

    /**
     * 「詞條」口徑（推薦組用）：≥2 字元且**至少含一個漢字**。
     *
     * 用戶 2026-11-11：逐字拼出「b站」後候選條有、推薦組沒有——`isCjkWord` 要求整詞全是漢字，
     * 把 `b站` 這類混合詞漏掉了。純 ASCII（`Linux`）仍不算引擎詞，避免把字母候選塞進推薦。
     */
    fun isWordLike(text: String): Boolean = text.length >= 2 && containsCjk(text)

    /** 單個漢字（段組只會從字典拿這種字）。ASCII/符號/顏文字一律不算。 */
    fun isSingleCjkChar(text: String): Boolean =
        text.length == 1 && (text[0].code in 0x3400..0x4DBF || text[0].code in 0x4E00..0x9FFF)

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
        val order = fullBarOrder(ranked)
        if (order.isEmpty()) return order
        val words = order.filter { isCjkWord(it.text) }
        val others = order.filterNot { isCjkWord(it.text) }
        // 特例（用戶 2026-11-11 原話）：**完全沒有單字時**，詞組才可以佔滿整條候選條
        // —— 只要存在任何單字（含 ASCII/顏文字等非詞組候選），詞組區仍然嚴格 ≤ wordSlots，
        // 即使候選條因此變短也不拿詞組去補（用戶明確：不做「單字不夠就補詞」的推廣）。
        if (others.isEmpty()) {
            return order.take(barSize).filter { it.text.isNotEmpty() }
        }
        val head = if (wordSlots > 0) words.take(wordSlots) else emptyList()
        return (head + others).take(barSize).filter { it.text.isNotEmpty() }
    }

    /**
     * 詞/字的最終順序（候選條與面板「推薦」組的**唯一來源**）：去重 → 全部詞 → 全部單字，
     * 各段保持 [ranked] 內的相對順序。
     *
     * 為什麼不直接拿 [rankBar] 的結果 `drop(12)`：`rankBar` 會把第 5 個起的詞丟掉
     * （詞組區嚴格 ≤ `wordSlots`），那些詞如果只靠候選條，就永遠看不到；
     * 面板「推薦」組要把「放不下的續頁」連同這些超額詞一起收進去（用戶 2026-11-11 定案）。
     */
    fun fullBarOrder(ranked: List<CandidateItem>): List<CandidateItem> {
        if (ranked.isEmpty()) return ranked
        // 候選條不顯示 comment，同一個詞出現兩次就是「重複詞條」（用戶 2026-11-11 報）。
        // 引擎本身也可能重複（userdb + custom_phrase 各學一份），這裡按文字去重、保留先出現的那條。
        val deduped = ranked.distinctBy { it.text }
        val words = ArrayList<CandidateItem>(deduped.size)
        val others = ArrayList<CandidateItem>(deduped.size)
        deduped.forEach { if (isCjkWord(it.text)) words.add(it) else others.add(it) }
        return if (others.isEmpty()) words else words + others
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
        /**
         * 已經在引擎候選裡的詞 → 學習分數（用戶 2026-11-11：重複點選 3 次以上仍不往前排）。
         *
         * 引擎候選的偽分數只反映引擎順序；App 學到的詞若與引擎條目同文，會被
         * [TwelveKeySnapshotBuilder.mergeLearnedPhrases] 過濾掉（避免重複），於是永遠無法提升。
         * 這裡用「取較大值」把學習分數套回引擎條目：同分仍由引擎在前（比較器不變）。
         */
        boostByText: Map<String, Double> = emptyMap(),
    ): List<CandidateItem> {
        if (synthetic.isEmpty() && boostByText.isEmpty()) return ranked
        val merged = ArrayList<Pair<CandidateItem, Double>>(ranked.size + synthetic.size)
        ranked.forEachIndexed { i, item ->
            val engine = engineScore(i)
            val boost = boostByText[item.text]
            merged.add(item to if (boost != null && boost > engine) boost else engine)
        }
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
    ): List<CandidateItem> {
        if (items.size <= 1) return items
        // 先算好排序鍵再排：舊寫法把 syllableCount(comment)（split）與 score()
        // 放進 Comparator，n log n 次重算，600 條候選時每鍵白燒十幾毫秒。
        data class Keyed(val item: CandidateItem, val depth: Int, val score: Int)
        val keyed = ArrayList<Keyed>(items.size)
        for (item in items) {
            val depth = if (topSyllables <= 0) 0 else minOf(syllableCount(item.comment), topSyllables)
            keyed.add(Keyed(item, depth, score(item)))
        }
        keyed.sortWith(
            compareByDescending<Keyed> { it.depth }
                .thenByDescending { it.score }
                .thenBy { it.item.index },
        )
        return keyed.map { it.item }
    }

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
                // 分檔用**讀音 token 數**，不用 `syllables.size`（docs/19 §9.1）。
                { if (it.syllables.isEmpty()) Int.MAX_VALUE else it.reading.split(' ').size },
                { it.reading.lowercase() },
            ),
        )
    }

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
                    it.reading.split(' ').count { part -> part.isNotBlank() } == 1 -> 0
                    else -> 2
                }
            },
            { it.reading.lowercase() },
            { it.items.firstOrNull()?.text ?: "" },
        ),
    )
}
