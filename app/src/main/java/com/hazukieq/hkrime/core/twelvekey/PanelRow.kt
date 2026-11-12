package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/**
 * 面板內部行流水線：五個 App 源與引擎源
 * → mergeRows → collapsePrefixRows → tierAndSort → capRows → SyllableGroup。
 * sources 僅供診斷，投影時丟棄，不向 UI 暴露來源。
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

    /** librime 候選組（引擎源，經 [mergeRows] 並入面板）。 */
    ENGINE,

    /** 孤單聯想字母行（`d`/`f`）：items 是可延伸單音節，固定排在單字組前面。 */
    ISOLATED,
}

/**
 * 流水線裡的一行。
 *
 * @param reading 行標題的讀音（具體音節，**永不含通配/星號**；空串 = 推薦組，不進流水線）
 * @param items 該行的候選（單字組是單字，詞行是整詞）
 * @param segment 0=單字組 / 1=詞行；沿用 [SyllableGroup.segment] 語義，投影時原樣帶過去
 * @param restCode 點擊後要餵回引擎的剩餘碼（單字組用；合併時取**首行**，見 [mergeRows]）
 * @param sources 內部診斷用（本行由哪些源貢獻）；投影成 [SyllableGroup] 時丟棄
 * @param hasUserWord 行內是否含用戶詞庫的詞；只用於 LS 選行優先（不進 UI、不影響顯示順序）
 */
internal data class PanelRow(
    val reading: String,
    val items: List<CandidateItem>,
    val segment: Int,
    val restCode: String = "",
    val sources: Set<RowSource> = emptySet(),
    val hasUserWord: Boolean = false,
) {
    /** 讀音有幾個音節（`ko thiau fa` = 3）；空白讀音 = 0。**禁止**用 `syllables.size` 判檔。 */
    val syllableCount: Int get() = reading.split(' ').count { it.isNotBlank() }

    /**
     * 投影到 UI 模型，sources 與 hasUserWord 不帶出邏輯層。
     */
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
 * 按小寫 reading 合成一行，保留首行位置、segment 和 restCode，合併來源集合。
 * 追加 WHOLE_WORD 行時按小寫文字去重，其它來源按精確文字去重；保留先出現項。
 * 固定拼接順序為段組、引擎、聯想、組合、整串詞。
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
        // 追加 WHOLE_WORD 行按小寫文字去重，其它來源按精確文字去重以保留 J/j。
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
 * 多音節行並入最長已有多音節前綴；單音節不吸收詞行，保持單字組語義。
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
 * 按 reading 非空 token 數分單音節、多音節、空串三檔；檔內按讀音，再按首候選文字。
 * BLANK_READING 的省略號是一個 token，實際在單音節檔，用首候選反查讀音排序。
 * 分檔不可使用 syllables 容器長度。
 */
internal fun tierAndSort(rows: List<PanelRow>): List<PanelRow> = rows.sortedWith(
    compareBy(
        {
            when {
                RowSource.ISOLATED in it.sources -> -1
                it.syllableCount == 0 -> 2
                it.syllableCount == 1 -> 0
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
