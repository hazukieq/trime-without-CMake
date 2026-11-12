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
     * 固定候選區最終列表：**引擎順序（已含 App 的少量學習調整）+ 去重 + 截斷**。
     *
     * 用戶 2026-11-12：取消「詞組優先」，詞/單字**平等競爭**，尊重 engine 給出的候選順序，
     * App 只在這個基礎上做少量添加/刪改（學習詞 boost、合成學習詞、按文字去重）。
     * 不再把 CJK 詞穩定分區到前面，也不再有「詞區 ≤ N 條」的上限。
     *
     * @param ranked 已按引擎順序 + App 少量調整排好的候選（順序即最終順序）
     * @param barSize 候選條總條數
     */
    fun rankBar(
        ranked: List<CandidateItem>,
        barSize: Int = 12,
    ): List<CandidateItem> {
        if (ranked.isEmpty()) return ranked
        return fullBarOrder(ranked).asSequence()
            .filter { it.text.isNotEmpty() }
            .take(barSize)
            .toList()
    }

    /**
     * 候選條與面板「推薦」組的**唯一來源**：只按文字去重（保留先出現的那條），順序原樣保留。
     *
     * 用戶 2026-11-12：不再「全部詞 → 全部單字」分區；引擎/學習混排後是什麼順序，候選條就
     * 取前 [barSize] 條，不再按詞/字重排。去重仍是必要的（候選條不顯示 comment，同文重複
     * 就是重複詞條；引擎的 userdb + custom_phrase 也可能各給一份）。
     */
    fun fullBarOrder(ranked: List<CandidateItem>): List<CandidateItem> {
        if (ranked.isEmpty()) return ranked
        return ranked.distinctBy { it.text }
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
        score: (CandidateItem) -> Int,
    ): List<CandidateItem> {
        if (items.size <= 1) return items
        // 先算好排序鍵再排：舊寫法把 score() 放進 Comparator，n log n 次重算。
        // 用戶 2026-11-12：取消「覆蓋音節多者優先」的詞組傾斜——詞/單字平等競爭，
        // 只有「學過的詞」才有分數（少量調整），同分維持引擎下標（引擎順序）。
        data class Keyed(val item: CandidateItem, val score: Int)
        val keyed = ArrayList<Keyed>(items.size)
        for (item in items) keyed.add(Keyed(item, score(item)))
        keyed.sortWith(
            compareByDescending<Keyed> { it.score }
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
