package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime
import timber.log.Timber

/**
 * 把 librime 的當前狀態拍成 [TwelveKeySnapshot]。
 *
 * 分兩步，方便把「枚舉 600 條」搬離 UI 線程：
 * - [local]：只讀 input / segments / remaining（O(段數)，很便宜），必須在 UI 線程+引擎鎖內做；
 * - [build]：拿 [Local] 後全量枚舉 + 分組，可在後台線程做（調用方負責持 [TwelveKeyController.ENGINE_LOCK]）。
 *
 * 這裡**不做任何分頁/分批收集**：一次枚舉全量候選（上限 [MAX_CANDIDATES]），
 * 按 comment 分組後直接返回。
 */
object TwelveKeySnapshotBuilder {

    /** 單次最多枚舉的候選數（設計 §7.2）。 */
    const val MAX_CANDIDATES = 600

    /** 左列最多展示的組數（設計 §7.2）。 */
    private const val MAX_GROUPS = 200

    /** 剩餘碼超過該長度時，fallback 只保留最後 48 個碼（設計 §8.4）。 */
    private const val MAX_FALLBACK_CODE = 48

    /** 候選條最多顯示的條目數（與選單順序一致；候選條可橫向滾動）。 */
    private const val BAR_ITEMS = 12

    private const val BLANK_READING = "…"

    /** UI 線程同步算出的「本地部分」；segmentsKey 用於區分「input 不變但分段變了」。 */
    data class Local(
        val input: String,
        val consumedEnd: Int,
        val remaining: String,
        val segmentsKey: String,
    ) {
        val isEmpty: Boolean get() = input.isEmpty()
    }

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

    /** 只讀 input/segments/remaining，不做枚舉；調用方需持引擎鎖。 */
    fun local(): Local {
        val input = runCatching { Rime.RimeGetInput() }.getOrNull().orEmpty()
        if (input.isEmpty() || !runCatching { Rime.isComposing() }.getOrDefault(false)) {
            return Local("", 0, "", "")
        }
        val segs = runCatching { Rime.getRimeCompositionSegments() }.getOrNull() ?: IntArray(0)
        val consumedEnd = consumedEndOf(segs, input)
        return Local(
            input = input,
            consumedEnd = consumedEnd,
            remaining = remainingOf(input, consumedEnd),
            segmentsKey = segs.joinToString(","),
        )
    }

    /** 快照緩存鍵：input + 分段（status 也會影響 consumedEnd）。 */
    fun cacheKey(local: Local, full: Boolean): String =
        local.input + "|" + local.segmentsKey + (if (full) "|full" else "|bar")

    /** 全量枚舉 + 分組；不讀引擎以外的東西。調用方需持引擎鎖（可在後台線程）。 */
    fun build(local: Local, maxCandidates: Int = MAX_CANDIDATES): TwelveKeySnapshot {
        if (local.isEmpty) return TwelveKeySnapshot.EMPTY
        val input = local.input
        val consumedEnd = local.consumedEnd
        val remaining = local.remaining

        // 全量枚舉：native 由 engine-probe 並行補，可能 UnsatisfiedLinkError → 降級單頁。
        val flat: List<Pair<String, String>>
        var degraded = false
        val snapshot = runCatching { Rime.candidatesSnapshot(maxCandidates) }.getOrNull()
        if (snapshot != null) {
            flat = snapshot.map { row -> (row.getOrNull(0) ?: "") to (row.getOrNull(1) ?: "") }
        } else {
            degraded = true
            flat = singlePageFallback()
        }

        val groups = group(flat)
        // 候選條按「選單順序」取前 N 條：長輸入時 reading 各不相同，
        // 按第 0 組取會只剩 1 條（用戶 2026-11-08 反饋「打到這麼多就停止候選」）。
        val barEntries = flat.take(BAR_ITEMS).mapIndexedNotNull { index, (text, comment) ->
            if (text.isEmpty()) null else CandidateItem(text, comment, index)
        }
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
            barEntries = barEntries,
        )
    }

    /** 兼容舊調用：同步一次做完（UI 線程慎用）。 */
    fun build(): TwelveKeySnapshot = build(local())

    /**
     * 已消費前綴的結束位置：所有 status >= 2（selected/confirmed）的段的最大 end。
     * start/end 都是 raw input 的 UTF-8 字節偏移（設計 §2.5）。
     */
    fun consumedEnd(input: String): Int {
        val segs = runCatching { Rime.getRimeCompositionSegments() }.getOrNull() ?: return 0
        return consumedEndOf(segs, input)
    }

    private fun consumedEndOf(segs: IntArray, input: String): Int {
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
        // 左列排序：單音節組排前面（用戶 2026-11-08：對聯想詞不滿意時，先手動選單字），
        // 其餘按音節數升序，同音節數保持候選原有順序（stable sort）。
        return grouped.entries.take(MAX_GROUPS).map { (reading, items) ->
            SyllableGroup(
                reading = reading,
                syllables = if (reading == BLANK_READING) emptyList() else {
                    reading.split(' ').filter { it.isNotBlank() }
                },
                items = items,
            )
        }.sortedWith(
            compareBy(
                { if (it.syllables.isEmpty()) 1 else it.syllables.size },
                { it.reading.lowercase() },
            ),
        )
    }
}
