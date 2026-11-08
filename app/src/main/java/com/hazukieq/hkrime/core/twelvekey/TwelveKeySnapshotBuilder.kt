package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime
import timber.log.Timber

/**
 * 把 librime 的當前狀態一次性拍成 [TwelveKeySnapshot]。
 *
 * 這裡**不做任何分頁/分批收集**：一次枚舉全量候選（上限 [MAX_CANDIDATES]），
 * 按 comment 分組後直接返回。所有耗時操作都是只讀的 librime 調用。
 */
object TwelveKeySnapshotBuilder {

    /** 單次最多枚舉的候選數（設計 §7.2）。 */
    const val MAX_CANDIDATES = 600

    /** 左列最多展示的組數（設計 §7.2）。 */
    private const val MAX_GROUPS = 200

    /** 剩餘碼超過該長度時，fallback 只保留最後 48 個碼（設計 §8.4）。 */
    private const val MAX_FALLBACK_CODE = 48

    private const val BLANK_READING = "…"

    private val FALLBACK_LETTERS = mapOf(
        '3' to "u",
        '1' to "ng",
        'w' to "th",
        'y' to "nz",
        '4' to "m",
        '5' to "k",
        '7' to "p",
        '8' to "z",
        '9' to "q",
    )

    fun build(): TwelveKeySnapshot {
        val input = runCatching { Rime.RimeGetInput() }.getOrNull().orEmpty()
        val composing = runCatching { Rime.isComposing() }.getOrDefault(false)
        if (!composing || input.isEmpty()) return TwelveKeySnapshot.EMPTY

        val consumedEnd = consumedEnd(input)
        val remaining = remainingOf(input, consumedEnd)

        // 全量枚舉：native 由 engine-probe 並行補，可能 UnsatisfiedLinkError → 降級單頁。
        val flat: List<Pair<String, String>>
        var degraded = false
        val snapshot = runCatching { Rime.candidatesSnapshot(MAX_CANDIDATES) }.getOrNull()
        if (snapshot != null) {
            flat = snapshot.map { row -> (row.getOrNull(0) ?: "") to (row.getOrNull(1) ?: "") }
        } else {
            degraded = true
            flat = singlePageFallback()
        }

        val groups = group(flat)
        val fallback = if (groups.isEmpty() && remaining.isNotEmpty()) {
            val raw = remaining.takeLast(MAX_FALLBACK_CODE)
            Fallback(rawCode = raw, letters = toLetters(raw))
        } else {
            null
        }

        if (degraded) {
            Timber.w("twelvekey: enumerate native missing, degraded to single page (%d)", flat.size)
        }
        return TwelveKeySnapshot(
            input = input,
            consumedEnd = consumedEnd,
            remaining = remaining,
            groups = groups,
            fallback = fallback,
            degradedToSinglePage = degraded,
        )
    }

    /**
     * 已消費前綴的結束位置：所有 status >= 2（selected/confirmed）的段的最大 end。
     * start/end 都是 raw input 的 UTF-8 字節偏移（設計 §2.5）。
     */
    fun consumedEnd(input: String): Int {
        val segs = runCatching { Rime.getRimeCompositionSegments() }.getOrNull() ?: return 0
        val inputBytes = input.toByteArray(Charsets.UTF_8).size
        var end = 0
        var i = 0
        while (i + 2 < segs.size) {
            val segEnd = segs[i + 1]
            val status = segs[i + 2]
            if (status >= 2) end = maxOf(end, segEnd.coerceIn(0, inputBytes))
            i += 3
        }
        return end
    }

    fun remainingOf(input: String, consumedEnd: Int): String {
        if (consumedEnd <= 0 || input.isEmpty()) return input
        val bytes = input.toByteArray(Charsets.UTF_8)
        if (consumedEnd >= bytes.size) return ""
        return String(bytes, consumedEnd, bytes.size - consumedEnd, Charsets.UTF_8)
    }

    /** 12 鍵碼 → 拼音字母（只用於本地兜底上屏，不影響引擎）。 */
    fun toLetters(rawCode: String): String = buildString(rawCode.length + 8) {
        rawCode.forEach { code ->
            val replacement = FALLBACK_LETTERS[code]
            if (replacement != null) append(replacement) else append(code)
        }
    }

    private fun singlePageFallback(): List<Pair<String, String>> {
        val page = runCatching { Rime.getCandidatesWithoutSwitch() }.getOrNull() ?: return emptyList()
        return page.map { (it.text ?: "") to (it.comment ?: "") }
    }

    /** 按 comment 分組；組順序 = 首次出現順序，不重排。 */
    private fun group(flat: List<Pair<String, String>>): List<SyllableGroup> {
        if (flat.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<CandidateItem>>()
        flat.forEachIndexed { index, (text, comment) ->
            if (text.isEmpty()) return@forEachIndexed
            val reading = if (comment.isBlank()) BLANK_READING else comment
            grouped.getOrPut(reading) { ArrayList() }
                .add(CandidateItem(text = text, comment = comment, index = index))
        }
        return grouped.entries.take(MAX_GROUPS).map { (reading, items) ->
            SyllableGroup(
                reading = reading,
                syllables = if (reading == BLANK_READING) emptyList() else {
                    reading.split(' ').filter { it.isNotBlank() }
                },
                items = items,
            )
        }
    }
}
