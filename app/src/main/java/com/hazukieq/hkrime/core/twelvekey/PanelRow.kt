package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/**
 * 展開面板左列的**流水線行模型**（只存在於邏輯層，不進 UI）。
 *
 * 為什麼需要它：`TwelveKeySnapshotBuilder` 以前把五種來源（合成音節 / 備選切法 / 聯想詞 /
 * 整串碼詞行 / 引擎候選）揉在一個大函數裡，各自順手去重、各自順手排序，
 * 出問題時無法判斷「哪一源多給了/少給了/被誰吞了」（見 docs/19）。
 *
 * 現在固定成一條流水線：
 *
 * ```
 * 每源純函數（SYNTH_SINGLE / SPLIT_SINGLE / ASSOC / INITIAL_COMBO / WHOLE_WORD / ENGINE）
 *   → [mergeRows]         唯一合併點（同 reading 合成一行）
 *   → [collapsePrefixRows] 多音節行按最長已有前綴收斂
 *   → [tierAndSort]        固定分檔（單音節 → 多音節 → 無讀音），檔內按讀音字典序
 *   → [capRows]            全局行數上限
 *   → project 成 [SyllableGroup]（UI 契約完全不變）
 * ```
 *
 * [PanelRow.sources] 只給診斷日誌用，投影時**丟棄**，不得讓 UI 觀察到「這行來自哪一源」。
 */
internal enum class RowSource {
    /** 整串碼恰好是一個完整音節（`ia` → ga/ha）的單字組。 */
    SYNTH_SINGLE,

    /** 備選切法的第一段單字組（`we` → w|e 的 th* / d*…）。 */
    SPLIT_SINGLE,

    /** 兩音節聯想詞行（`ga zi`），標題是兩個完整讀音。 */
    ASSOC,

    /** 每鍵首字母笛卡爾積 → 按讀音首字母序列查到的詞行（`g·b` → `gang bung`）。 */
    INITIAL_COMBO,

    /** 整串碼前綴命中的多音節詞條（`li nak si` → Linux）。 */
    WHOLE_WORD,

    /** librime 候選組（`CandidateRanking.panelGroups` 的來源）。 */
    ENGINE,
}

/**
 * 流水線裡的一行。
 *
 * @param reading 行標題的讀音（具體音節，**永不含通配/星號**；空串 = 推薦組，不進流水線）
 * @param items 該行的候選（單字組是單字，詞行是整詞）
 * @param segment 0=單字組 / 1=詞行；沿用 [SyllableGroup.segment] 語義，投影時原樣帶過去
 * @param restCode 點擊後要餵回引擎的剩餘碼（單字組用；合併時取**首行**，見 [mergeRows]）
 * @param sources 內部診斷用（本行由哪些源貢獻）；投影成 [SyllableGroup] 時丟棄
 */
internal data class PanelRow(
    val reading: String,
    val items: List<CandidateItem>,
    val segment: Int,
    val restCode: String = "",
    val sources: Set<RowSource> = emptySet(),
) {
    /** 讀音有幾個音節（`ko thiau fa` = 3）；空白讀音 = 0。**禁止**用 `syllables.size` 判檔。 */
    val syllableCount: Int get() = reading.split(' ').count { it.isNotBlank() }

    /** 投影成 UI 契約類型：字段與舊構造完全一致，`sources` 不帶出去。 */
    fun toSyllableGroup(): SyllableGroup = SyllableGroup(
        reading = reading,
        syllables = if (reading.isBlank() || reading == TwelveKeyTuning.BLANK_READING) {
            emptyList()
        } else {
            reading.split(' ').filter { it.isNotBlank() }
        },
        items = items,
        segment = segment,
    )
}

/**
 * **唯一合併點**：同讀音（大小寫不敏感）的行合成一行。
 *
 * 規則：
 * 1. 行位置 = 首次出現位置（`LinkedHashMap`，保持來源拼接順序）；
 * 2. `items` 文字大小寫不敏感去重，保留先出現的那一條（引擎項在拼接順序裡已排在 App 補集之前）；
 * 3. `segment` / `restCode` 取**首行**：來源拼接順序固定為「段組 → 引擎 → 聯想 → 組合 → 詞行」，
 *    所以「單字組(0) 優先於詞行(1)」「整串音節的空 restCode 優先於備選切法」自然成立
 *    （與重構前 `mergeRowsByReading` / `mergeAssociationGroups` / `panelGroups` 的口徑一致）；
 * 4. `sources` 取並集（只進日誌）。
 */
internal fun mergeRows(rows: List<PanelRow>): List<PanelRow> {
    if (rows.size < 2) return rows
    val out = LinkedHashMap<String, PanelRow>(rows.size)
    rows.forEach { row ->
        val key = row.reading.lowercase()
        val existing = out[key]
        if (existing == null) {
            out[key] = row
            return@forEach
        }
        // 文字去重：整串詞行（WHOLE_WORD）大小寫不敏感（`Linux`/`linux` 是同一個詞）；
        // 其餘來源用**精確文字**——引擎會同時給 `J` 和 `j`，按小寫去重會把大寫候選吞掉
        //（`8e` 的 `ze` 組實測少了 `J`），與重構前 panelGroups/mergeAssociationGroups 口徑一致。
        val caseInsensitive = row.sources.any { it == RowSource.WHOLE_WORD }
        val seen = existing.items.mapTo(HashSet(existing.items.size)) {
            if (caseInsensitive) it.text.lowercase() else it.text
        }
        val additions = row.items.filter {
            seen.add(if (caseInsensitive) it.text.lowercase() else it.text)
        }
        out[key] = existing.copy(
            items = if (additions.isEmpty()) existing.items else existing.items + additions,
            sources = existing.sources + row.sources,
        )
    }
    return out.values.toList()
}

/**
 * 多音節行按「開頭相同」收斂：`ko thiau fa` 並進 `ko thiau`、`ka thu di` 並進 `ka thu`。
 *
 * 為什麼需要（用戶 2026-11-11）：「整串碼查詢是按開頭匹配的，那 `ko thiau` 和 `ko thiau fa`
 * 就是同一個 list，為什麼面板裡是兩行」——顯示語義必須跟著查詢語義走。
 *
 * 限制：**只收斂多音節行（≥2 音節）之間**，單音節行不吸收詞行，
 * 否則 `ka then` 這種詞會跑到單字格里，破壞「單音節組 = 這一段能選的字」的語義。
 */
internal fun collapsePrefixRows(rows: List<PanelRow>): List<PanelRow> {
    if (rows.size < 2) return rows
    fun tokens(row: PanelRow) = row.reading.split(' ').filter { it.isNotBlank() }

    val out = ArrayList<PanelRow>(rows.size)
    // 1) 單音節/無讀音行原樣保留（順序不變）
    rows.forEach { if (tokens(it).size < 2) out.add(it) }
    // 2) 多音節行按音節數從短到長處理：短的先就位，長的才有前綴可並
    rows.filter { tokens(it).size >= 2 }
        .sortedBy { tokens(it).size }
        .forEach { row ->
            val rowTokens = tokens(row)
            var target = -1
            var bestLen = 0
            out.forEachIndexed { index, candidate ->
                val candidateTokens = tokens(candidate)
                if (candidateTokens.size < 2 || candidateTokens.size >= rowTokens.size) {
                    return@forEachIndexed
                }
                if (rowTokens.take(candidateTokens.size) == candidateTokens &&
                    candidateTokens.size > bestLen
                ) {
                    bestLen = candidateTokens.size
                    target = index
                }
            }
            if (target < 0) {
                out.add(row)
                return@forEach
            }
            val existing = out[target]
            val texts = existing.items.mapTo(HashSet()) { it.text.lowercase() }
            val additions = row.items.filter { texts.add(it.text.lowercase()) }
            out[target] = existing.copy(
                items = if (additions.isEmpty()) existing.items else existing.items + additions,
                sources = existing.sources + row.sources,
            )
        }
    return out
}

/**
 * 固定分檔 + 檔內排序：
 * 1. 檔位 = **讀音音節數**：1 → 0 檔，≥2 → 1 檔，空讀音 → 2 檔；
 *    注意 `BLANK_READING`（`…`）是「一個非空 token」，所以實際落在 0 檔並用首候選的字典讀音排序
 *    （與重構前 `readingSyllableCount` 的行為一致；docs/17 §8 已按實際行為更正）。
 *    **禁止**用 `syllables.size` 判檔（引擎組的 `syllables` 歷史上只有 1 個元素）；
 * 2. 檔內按 `reading.lowercase()` 字典序；無讀音組用首候選的字典讀音兜底；
 * 3. 再同按首候選文字（穩定、可重現）。
 */
internal fun tierAndSort(rows: List<PanelRow>): List<PanelRow> = rows.sortedWith(
    compareBy(
        {
            when (it.syllableCount) {
                0 -> 2
                1 -> 0
                else -> 1
            }
        },
        {
            if (it.reading.isBlank() || it.reading == TwelveKeyTuning.BLANK_READING) {
                TwelveKeyPreedit.pinyinOfText(it.items.firstOrNull()?.text ?: "")
            } else {
                it.reading.lowercase()
            }
        },
        { it.items.firstOrNull()?.text ?: "" },
    ),
)

/**
 * 全局行數上限（[TwelveKeyTuning.MAX_GROUPS]），超額時**先丟 INITIAL_COMBO 行**
 * （它是最後接入的來源、優先級最低），再從尾端截斷。
 */
internal fun capRows(rows: List<PanelRow>, maxRows: Int = TwelveKeyTuning.MAX_GROUPS): List<PanelRow> {
    if (rows.size <= maxRows) return rows
    val appSideCount = rows.count { RowSource.INITIAL_COMBO !in it.sources }
    val comboBudget = (maxRows - appSideCount).coerceAtLeast(0)
    var comboUsed = 0
    return rows.filter { row ->
        if (RowSource.INITIAL_COMBO !in row.sources) return@filter true
        if (comboUsed >= comboBudget) return@filter false
        comboUsed++
        true
    }.take(maxRows)
}
