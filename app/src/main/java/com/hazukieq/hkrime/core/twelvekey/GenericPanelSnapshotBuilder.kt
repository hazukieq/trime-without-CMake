package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime

/**
 * 通用（非客語 12 鍵）方案展開面板快照構建器（純邏輯層，無任何 Android/UI 依賴）。
 *
 * 適用範圍：26 鍵普通話（`rime_ice`、`luna_pinyin`）、9 鍵拼音（`t9`）、
 * 國際音標（`ipa_yunlong`）、部件拆字（`radical_pinyin`）、五筆、筆畫及其它方言/語言方案。
 *
 * 統一三段流水線：
 * 1. [classifyCommentMode]：識別引擎 `comment` 屬於「聚合讀音」「唯一補全後綴」還是「無注釋」；
 * 2. [formatCardAnnotation]：統一生成右側候選卡片底部注腳（展開 `~` 補全前綴、補齊完整編碼、表情補標「表情」）；
 * 3. [resolveRailGroupLabel] + [assembleGroups]：統一生成左側分組欄（絕不混入客語詞庫，無注釋表情不污染數字組）。
 */
object GenericPanelSnapshotBuilder {

    private const val MAX_GROUPS = 48

    enum class CommentMode {
        /** 多候選共享同一讀音/注釋（如 `t9`、`luna_pinyin`、帶重複 `~` 後綴的拆字方案）。 */
        SHARED_READINGS,
        /** 每個候選自帶唯一補全後綴（如 `ipa_yunlong`、`table_translator` 補全提示）。 */
        COMPLETION_SUFFIX,
        /** 引擎未提供有效 `comment`（如 26 鍵 `rime_ice`）。 */
        NONE,
    }

    fun build(
        local: TwelveKeySnapshotBuilder.Local,
        maxCandidates: Int = TwelveKeySnapshotBuilder.MAX_CANDIDATES,
        candidatesOverride: List<CandidateItem>? = null,
    ): TwelveKeySnapshot {
        if (local.isEmpty) return TwelveKeySnapshot.EMPTY

        var degraded = false
        val probeMore = maxCandidates >= TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT &&
            maxCandidates < TwelveKeyTuning.CONFIRM_ALL_LIMIT
        val hasEngineMore: Boolean
        val flat: List<CandidateItem> = if (candidatesOverride != null) {
            hasEngineMore = probeMore && candidatesOverride.size > maxCandidates
            candidatesOverride.take(maxCandidates)
        } else {
            val fetchLimit = if (probeMore) maxCandidates + 1 else maxCandidates
            val snapshot = runCatching { Rime.candidatesSnapshot(fetchLimit) }.getOrNull()
            if (snapshot != null) {
                hasEngineMore = probeMore && snapshot.size > maxCandidates
                snapshot.take(maxCandidates).mapIndexed { index, row ->
                    CandidateItem(
                        text = row.getOrNull(0) ?: "",
                        comment = cleanComment(row.getOrNull(1)),
                        index = row.getOrNull(5)?.toIntOrNull() ?: index,
                        engineSpan = EngineCandidateSpan.fromRow(row),
                        openccModified = row.getOrNull(6) == "1",
                    )
                }
            } else {
                degraded = true
                hasEngineMore = false
                singlePageFallback()
            }
        }

        val fallbackReading = local.rawPreedit.trim().ifEmpty { local.remaining }
        val mode = classifyCommentMode(flat)

        val displayFlat = ArrayList<CandidateItem>(flat.size)
        val grouped = LinkedHashMap<String, MutableList<CandidateItem>>()
        for (item in flat) {
            val railLabel = resolveRailGroupLabel(item, local.input, local.rawPreedit, fallbackReading, mode)
            val cardAnnotation = formatCardAnnotation(item, local.input, local.rawPreedit, fallbackReading)
            val displayItem = if (cardAnnotation != item.comment) item.copy(comment = cardAnnotation) else item
            displayFlat.add(displayItem)
            if (railLabel.isNotEmpty()) {
                grouped.getOrPut(railLabel) { ArrayList() }.add(displayItem)
            }
        }

        val groups = assembleGroups(displayFlat, grouped, fallbackReading)
        val bar = displayFlat.take(TwelveKeyTuning.BAR_ITEMS)
        val fallback = if (displayFlat.isEmpty() && local.remaining.isNotEmpty()) {
            Fallback(rawCode = local.remaining, letters = local.remaining)
        } else {
            null
        }
        val preview = bar.firstOrNull()?.text.orEmpty()
        val inline = runCatching { Rime.getCompositionPrefix() }.getOrDefault("") + preview
        val hasMore = !degraded && hasEngineMore && displayFlat.size >= TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT

        return TwelveKeySnapshot(
            input = local.input,
            consumedEnd = local.consumedEnd,
            remaining = local.remaining,
            groups = groups,
            fallback = fallback,
            degradedToSinglePage = degraded,
            barEntries = bar,
            recommend = displayFlat,
            previewText = preview,
            inlineText = inline,
            contextKey = local.contextKey,
            rawPreedit = local.rawPreedit,
            initialChoices = emptyList(),
            initial = null,
            engineOrderOnly = true,
            phoneticNavigation = false,
            hasMore = hasMore,
        )
    }

    internal fun classifyCommentMode(candidates: List<CandidateItem>): CommentMode {
        val commented = candidates.mapNotNull { cleanComment(it.comment).takeIf { c -> c.isNotEmpty() } }
        if (commented.isEmpty()) return CommentMode.NONE
        val hasShared = commented.size >= 2 && commented.toSet().size < commented.size
        return if (hasShared) CommentMode.SHARED_READINGS else CommentMode.COMPLETION_SUFFIX
    }

    internal fun formatCardAnnotation(
        item: CandidateItem,
        input: String,
        rawPreedit: String = "",
        fallbackReading: String,
    ): String {
        val raw = cleanComment(item.comment)
        val prefix = matchedDisplaySpan(item, input, rawPreedit, fallbackReading)
        return when {
            raw.startsWith("~") && prefix.isNotEmpty() -> prefix + raw.removePrefix("~")
            raw.isEmpty() && isEmojiText(item.text) -> "表情"
            raw.isEmpty() && prefix.isNotEmpty() -> prefix
            else -> raw
        }
    }

    private fun matchedDisplaySpan(
        item: CandidateItem,
        input: String,
        rawPreedit: String,
        fallbackReading: String,
    ): String {
        val span = item.engineSpan
        if (span != null && input.isNotEmpty()) {
            val s = span.start.coerceIn(0, input.length)
            val e = span.end.coerceIn(s, input.length)
            if (e > s) {
                val mapped = slicePreeditByInputSpan(input, rawPreedit, s, e)
                if (mapped.isNotEmpty()) return mapped
                return input.substring(s, e)
            }
        }
        return matchedCodeSpan(item, input, fallbackReading)
    }

    internal fun resolveRailGroupLabel(
        item: CandidateItem,
        input: String,
        rawPreedit: String,
        fallbackReading: String,
        mode: CommentMode,
    ): String {
        val raw = cleanComment(item.comment)
        if (mode == CommentMode.SHARED_READINGS) {
            return raw
        }
        val span = item.engineSpan
        if (span != null && input.isNotEmpty()) {
            val s = span.start.coerceIn(0, input.length)
            val e = span.end.coerceIn(s, input.length)
            if (e > s) {
                val mapped = slicePreeditByInputSpan(input, rawPreedit, s, e)
                if (mapped.isNotEmpty()) return mapped
                return input.substring(s, e)
            }
        }
        if (raw.isNotEmpty()) return raw
        return fallbackReading
    }

    private fun assembleGroups(
        displayFlat: List<CandidateItem>,
        grouped: Map<String, List<CandidateItem>>,
        fallbackReading: String,
    ): List<SyllableGroup> {
        if (displayFlat.isEmpty()) return emptyList()
        val readingGroups = grouped.entries
            .take(MAX_GROUPS - 1)
            .map { (reading, items) ->
                SyllableGroup(
                    reading = reading,
                    syllables = reading.split(' ').filter { it.isNotEmpty() },
                    items = items,
                    segment = 0,
                )
            }
        return when {
            readingGroups.isEmpty() -> listOf(
                SyllableGroup(
                    reading = fallbackReading.ifEmpty { "全部" },
                    syllables = emptyList(),
                    items = displayFlat,
                    segment = 0,
                ),
            )
            readingGroups.size == 1 -> readingGroups
            else -> listOf(
                SyllableGroup(
                    reading = "全部",
                    syllables = emptyList(),
                    items = displayFlat,
                    segment = 0,
                ),
            ) + readingGroups
        }
    }

    internal fun slicePreeditByInputSpan(
        input: String,
        rawPreedit: String,
        start: Int,
        end: Int,
    ): String {
        if (rawPreedit.isBlank() || start < 0 || end <= start || end > input.length) return ""
        val cleanPreedit = rawPreedit.trim()
        // 覆蓋全碼時直接復用引擎已格式化的 preedit（如地球拼音 `wǒ mén`、筆畫 `⼀⼁`）。
        if (start == 0 && end == input.length) return cleanPreedit
        // 1:1 字符變換（如五筆畫 `hspnz` → `⼀⼁⼃⼂⼄`）：直接按相同下標切片。
        val preeditNoSpace = cleanPreedit.filter { it != ' ' && it != '\'' && it != '\u200B' }
        val inputNoSpace = input.filter { it != ' ' && it != '\'' }
        if (preeditNoSpace.length == inputNoSpace.length && preeditNoSpace.indices.all {
                normalizeToneBase(preeditNoSpace[it]) != inputNoSpace[it].lowercaseChar()
            }
        ) {
            return sliceIgnoringSpaces(cleanPreedit, start, end)
        }
        // 字母/帶調拼音對齊：把聲調元音歸一為基礎字母（`ǒ → o`、`ǖ → u`），並跳過原碼中的標調符號（`-;/<,\.`）。
        var inIdx = 0
        var outStart = -1
        var outEnd = -1
        var i = 0
        while (i < cleanPreedit.length && inIdx < input.length) {
            val ch = cleanPreedit[i]
            if (ch == ' ' || ch == '\'' || ch == '\u200B') {
                i++
                continue
            }
            while (inIdx < input.length && isToneInputSymbol(input[inIdx])) {
                inIdx++
                if (inIdx == end && outStart >= 0) {
                    outEnd = i
                    return cleanPreedit.substring(outStart, outEnd).trim()
                }
            }
            if (inIdx >= input.length) break
            val normPreedit = normalizeToneBase(ch)
            val normInput = input[inIdx].lowercaseChar()
            if (normPreedit != normInput && !(normPreedit == 'u' && normInput == 'v')) return ""
            if (inIdx >= start && outStart < 0) outStart = i
            inIdx++
            while (inIdx < input.length && isToneInputSymbol(input[inIdx])) {
                if (inIdx == end) {
                    outEnd = i + 1
                    return cleanPreedit.substring(outStart, outEnd).trim()
                }
                inIdx++
            }
            if (inIdx >= end && outStart >= 0) {
                outEnd = i + 1
                break
            }
            i++
        }
        if (outStart < 0 || outEnd <= outStart) return ""
        return cleanPreedit.substring(outStart, outEnd).trim()
    }

    private fun sliceIgnoringSpaces(text: String, start: Int, end: Int): String {
        var idx = 0
        var outStart = -1
        var outEnd = -1
        for (i in text.indices) {
            val ch = text[i]
            if (ch == ' ' || ch == '\'' || ch == '\u200B') continue
            if (idx == start) outStart = i
            idx++
            if (idx == end) {
                outEnd = i + 1
                break
            }
        }
        return if (outStart >= 0 && outEnd > outStart) text.substring(outStart, outEnd).trim() else ""
    }

    private fun isToneInputSymbol(ch: Char): Boolean = ch in "-;/<,\\."

    private fun normalizeToneBase(ch: Char): Char = when (ch.lowercaseChar()) {
        'ā', 'á', 'ǎ', 'à' -> 'a'
        'ē', 'é', 'ě', 'è', 'ê' -> 'e'
        'ī', 'í', 'ǐ', 'ì' -> 'i'
        'ō', 'ó', 'ǒ', 'ò' -> 'o'
        'ū', 'ú', 'ǔ', 'ù', 'ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü' -> 'u'
        else -> ch.lowercaseChar()
    }

    private fun matchedCodeSpan(item: CandidateItem, input: String, fallback: String): String {
        val span = item.engineSpan
        if (span != null && input.isNotEmpty()) {
            val s = span.start.coerceIn(0, input.length)
            val e = span.end.coerceIn(s, input.length)
            if (e > s) return input.substring(s, e)
        }
        return fallback
    }

    private fun isEmojiText(text: String): Boolean {
        if (text.isEmpty()) return false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // 僅匹配真正的 Emoji 區段（U+1F000..U+1FAFF、U+2600..U+27BF、U+FE0F），
            // 絕不把 CJK 擴展 B/C/D/E/F 區生僻漢字（U+20000+）誤判為表情。
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun cleanComment(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotEmpty() && it != "…" }.orEmpty()

    private fun singlePageFallback(): List<CandidateItem> =
        (Rime.getCandidates() ?: emptyArray()).mapIndexedNotNull { idx, c ->
            val t = c?.text ?: return@mapIndexedNotNull null
            CandidateItem(t, cleanComment(c.comment), idx, openccModified = c.opencc_modified)
        }
}
