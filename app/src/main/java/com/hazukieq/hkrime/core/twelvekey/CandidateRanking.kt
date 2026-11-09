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
     * 固定候選區最終列表：詞組優先 → 取前 [barSize] 條 → 去掉空文字。
     *
     * @param ranked 已按「引擎詞頻 / 用戶記憶 / 關聯度」排好的候選（順序即最終順序）
     */
    fun rankBar(ranked: List<CandidateItem>, barSize: Int = 12): List<CandidateItem> =
        wordsFirst(ranked).take(barSize).filter { it.text.isNotEmpty() }

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
     * 左列的最終顯示順序（用戶 2026-11-10）：
     * 單音節輸入時左列全是「同一批音節」的組，必須按常用度排（常用音節在前），
     * 不能按讀音字母序 —— 否則找想要的音節要找半天。
     *
     * 排序鍵：單音節組 → 多音節（詞組）組 → 讀音未知；同類內按組內第一個字的
     * 用戶字頻、通用字頻降序，最後用讀音兜底保證穩定。數量不做限制（引擎給多少列多少）。
     */
    fun orderGroups(
        groups: List<SyllableGroup>,
        charScore: (String) -> Int,
        generalScore: (String) -> Int,
    ): List<SyllableGroup> = groups.sortedWith(
        compareBy(
            { if (it.syllables.isEmpty()) 2 else if (it.syllables.size == 1) 0 else 1 },
            { g -> -(charScore(g.items.firstOrNull()?.text ?: "")) },
            { g -> -(generalScore(g.items.firstOrNull()?.text ?: "")) },
            { it.reading.lowercase() },
        ),
    )
}
