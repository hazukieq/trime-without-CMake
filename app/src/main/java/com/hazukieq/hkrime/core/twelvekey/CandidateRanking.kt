package com.hazukieq.hkrime.core.twelvekey

/**
 * 候選條與面板排序純函數；裝配與 UI 不在此處。
 * 候選條以引擎/學習混排為基礎，首位優先完整原碼匹配；面板行最終合併與分檔由 PanelRow 負責。
 */
object CandidateRanking {

    /** 首位優先取已證實覆蓋全部剩餘原碼的候選；其餘保序，不按顯示讀音或文字長度分區。 */
    fun preferCompleteFirst(ranked: List<CandidateItem>, code: String): List<CandidateItem> {
        val position = ranked.indexOfFirst { item ->
            item.inputSpan?.let { it.matches(code) && it.end == code.length } == true
        }
        if (position <= 0) return ranked
        return listOf(ranked[position]) + ranked.filterIndexed { index, _ -> index != position }
    }

    /** 漢字詞組：≥2 個漢字（含擴展 A）。顏文字、ASCII、單字都不算。 */
    fun isCjkWord(text: String): Boolean =
        text.length >= 2 && text.all { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }

    /** 含至少一個漢字（混合詞，如 `b站`、`QQ群`、`3D打印`）。 */
    fun containsCjk(text: String): Boolean =
        text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }

    /**
     * 推薦組的詞條判定：至少兩個字元且至少含一個漢字，允許 b站 等混合文字。
     * 純 ASCII 不作引擎推薦詞。
     */
    fun isWordLike(text: String): Boolean = text.length >= 2 && containsCjk(text)

    /** 單個漢字（段組只會從字典拿這種字）。ASCII/符號/顏文字一律不算。 */
    fun isSingleCjkChar(text: String): Boolean =
        text.length == 1 && (text[0].code in 0x3400..0x4DBF || text[0].code in 0x4E00..0x9FFF)

    /**
     * 固定候選條：按文字去重並保序，過濾空文字後截斷到 barSize。
     * 輸入已完成引擎/學習混排；詞與單字同池競爭。
     * @param ranked 引擎順序加少量學習調整後的候選
     * @param barSize 展示上限
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
     * 按文字去重，保留首次出現項和輸入順序。
     * 候選條與推薦排序使用此基礎順序，推薦另按自身詞/單字配額取項。
     */
    fun fullBarOrder(ranked: List<CandidateItem>): List<CandidateItem> {
        if (ranked.isEmpty()) return ranked
        return ranked.distinctBy { it.text }
    }

    /**
     * 合成學習詞與引擎候選按分數混排。合成詞使用 PhraseMemory 分數，
     * 引擎使用 1-i/n 的順序分；已有同文引擎項可套用學習分數，取較大值，同分引擎在前。
     */
    fun mergePhraseCandidates(
        ranked: List<CandidateItem>,
        synthetic: List<CandidateItem>,
        syntheticScore: (CandidateItem) -> Double,
        engineScore: (Int) -> Double,
        /**
         * 引擎已有同文詞時套用學習分數，與順序分取較大值；避免去重後丟失學習提升。
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
     * 候選條學習排序：分數降序，同分保持引擎全局下標順序。
     * 不按音節覆蓋深度分層。
     * @param score 候選學習分數
     */
    fun rankWithLearning(
        items: List<CandidateItem>,
        score: (CandidateItem) -> Int,
    ): List<CandidateItem> {
        if (items.size <= 1) return items
        // 預計算每項學習分數；同分按引擎下標，保持確定性。
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
     * 按 comment 分組；空 comment 使用 blankReading。
     * 組間按讀音 token 數與字典序排序；最終面板排序由 PanelRow 流水線決定。
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
                // 分檔依據 reading 的非空 token 數，與模型 syllables 容器長度無關。
                { if (it.syllables.isEmpty()) Int.MAX_VALUE else it.reading.split(' ').size },
                { it.reading.lowercase() },
            ),
        )
    }

    /**
     * 音節組按讀音 token 數分檔，檔內按讀音字典序；常用度只用於選取候選音節。
     * 具體面板行最終順序由 PanelRow.tierAndSort 決定。
     */
    fun orderGroups(
        groups: List<SyllableGroup>,
        @Suppress("UNUSED_PARAMETER") charScore: (String) -> Int = { 0 },
        @Suppress("UNUSED_PARAMETER") generalScore: (String) -> Int = { 0 },
    ): List<SyllableGroup> = groups.sortedWith(
        compareBy(
            // 按讀音 token 數分檔，各檔內部按字典序排列。
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
