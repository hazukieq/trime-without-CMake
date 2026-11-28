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
    fun parseTokens(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val parts = ArrayList<String>(4)
        var start = 0
        for (i in reading.indices) {
            if (reading[i] == ' ') {
                if (i > start) parts.add(reading.substring(start, i))
                start = i + 1
            }
        }
        if (start < reading.length) parts.add(reading.substring(start))
        return parts
    }

    val out = ArrayList<PanelRow>(rows.size)
    val outTokens = ArrayList<List<String>>(rows.size)
    val multiRows = ArrayList<Pair<PanelRow, List<String>>>()
    // 1) 單音節/無讀音行原樣保留（順序不變）；學習詞行不參與前綴收斂
    rows.forEach { row ->
        val t = parseTokens(row.reading)
        if (t.size < 2 || row.hasUserWord) {
            out.add(row)
            outTokens.add(t)
        } else {
            multiRows.add(row to t)
        }
    }
    if (multiRows.isEmpty()) return out
    // 2) 多音節行按音節數從短到長處理：短的先就位，長的才有前綴可並
    multiRows.sortBy { it.second.size }
    for ((row, rowTokens) in multiRows) {
        var target = -1
        var bestLen = 0
        for (index in out.indices) {
            val candidate = out[index]
            val candidateTokens = outTokens[index]
            if (candidateTokens.size < 2 || candidateTokens.size >= rowTokens.size ||
                candidate.hasUserWord
            ) {
                continue
            }
            var prefixMatch = true
            for (k in candidateTokens.indices) {
                if (rowTokens[k] != candidateTokens[k]) {
                    prefixMatch = false
                    break
                }
            }
            if (prefixMatch && candidateTokens.size > bestLen) {
                bestLen = candidateTokens.size
                target = index
            }
        }
        if (target < 0) {
            out.add(row)
            outTokens.add(rowTokens)
            continue
        }
        val existing = out[target]
        val texts = existing.items.mapTo(HashSet(existing.items.size * 2)) { it.text.lowercase() }
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
internal fun tierAndSort(rows: List<PanelRow>): List<PanelRow> {
    if (rows.size < 2) return rows
    class Keyed(val row: PanelRow, val tier: Int, val readingKey: String, val firstText: String)
    return rows.map { r ->
        val tier = when {
            RowSource.ISOLATED in r.sources -> -1
            r.syllableCount == 0 -> 2
            r.syllableCount == 1 -> 0
            else -> 1
        }
        val first = r.items.firstOrNull()?.text ?: ""
        val rKey = if (r.reading.isBlank() || r.reading == TwelveKeyTuning.BLANK_READING) {
            TwelveKeyPreedit.pinyinOfText(first)
        } else {
            r.reading.lowercase()
        }
        Keyed(r, tier, rKey, first)
    }.sortedWith(compareBy<Keyed>({ it.tier }, { it.readingKey }, { it.firstText })).map { it.row }
}

/**
 * 全局行數上限（[TwelveKeyTuning.MAX_GROUPS]），超額時**先丟 INITIAL_COMBO 行**
 * （它是最後接入的來源、優先級最低），再從尾端截斷。
 */
internal fun capRows(rows: List<PanelRow>, maxRows: Int = TwelveKeyTuning.MAX_GROUPS): List<PanelRow> {
    if (rows.size <= maxRows) return rows
    // 引擎只是候選窗口，App 源（段組/聯想/首字母組合/整串詞行）才是面板的補充。
    // 超額時先裁引擎行：否則長輸入的 200 條引擎詞會把四鍵組合行整批擠掉。
    val appSide = rows.filter { RowSource.ENGINE !in it.sources }
    if (appSide.size >= maxRows) {
        // 極端情況：App 源自身就超額，才按舊規則壓縮 INITIAL_COMBO 行後截斷。
        val comboBudget = (maxRows - appSide.count { RowSource.INITIAL_COMBO !in it.sources }).coerceAtLeast(0)
        var comboUsed = 0
        return appSide.filter { row ->
            if (RowSource.INITIAL_COMBO !in row.sources) return@filter true
            if (comboUsed >= comboBudget) return@filter false
            comboUsed++
            true
        }.take(maxRows)
    }
    val engineBudget = maxRows - appSide.size
    var engineUsed = 0
    return rows.filter { row ->
        if (RowSource.ENGINE !in row.sources) return@filter true
        if (engineUsed >= engineBudget) return@filter false
        engineUsed++
        true
    }
}

/**
 * 左列展示用的一行：聯想/詞行折進第一個音節組，左列只剩音節組（孤立音節行單獨保留）。
 * `items` 已按文字去重，音節的字在前、詞在後（沿用原組順序）。
 */
internal data class PanelDisplayGroup(
    val reading: String,
    val items: List<CandidateItem>,
    /** 折疊後第一行的組型（0=分詞段、1=詞行、-3=推薦、-4..=-6 孤立音節）。 */
    val segment: Int,
)

/**
 * 左列邏輯：多音節詞行（聯想 `man jiu`、組合 `jen cin kung ze`、整串詞行等）不再單獨成行，
 * 而是並進第一個音節（`man`）的組；孤立音節行（`d`/`f`）與推薦組不受影響。
 * 只改展示，不改快照分組、點擊語義與推薦來源。
 */
internal fun foldGroupsForDisplay(groups: List<SyllableGroup>, engineOrderOnly: Boolean = false): List<PanelDisplayGroup> {
    val order = LinkedHashMap<String, MutableList<CandidateItem>>()
    val segments = HashMap<String, Int>()
    groups.forEach { group ->
        val tokens = group.reading.split(' ').filter { it.isNotBlank() }
        // 詞行可能來自引擎組（segment=-1）或聯想/組合行（segment=1），都折；
        // 推薦組（-3，reading 空）與孤立音節（-4..-6）保持獨立。
        val fold = tokens.size > 1 && group.segment > ISOLATED_SEGMENT
        val key = if (fold) tokens.first() else group.reading
        val bucket = order.getOrPut(key) { ArrayList() }
        if (segments[key] == null) segments[key] = if (fold) group.segment else group.segment
        bucket.addAll(group.items)
    }
    return order.map { (reading, items) ->
        if (engineOrderOnly) return@map PanelDisplayGroup(
            reading, items.sortedBy { it.index }, segments[reading] ?: -1,
        )
        val seen = HashSet<String>()
        val unique = items.filter { seen.add(it.text) }
        // 單字在前、折進來的詞在後：三音節碼時第一音節組會併入上百個詞行，
        // 詞若排在單字前面，逐段選字要翻十幾屏（自造詞因此拼不完）。
        PanelDisplayGroup(
            reading = reading,
            items = unique.sortedBy { if (it.segment == 0) 0 else 1 },
            segment = segments[reading] ?: -1,
        )
    }
}
