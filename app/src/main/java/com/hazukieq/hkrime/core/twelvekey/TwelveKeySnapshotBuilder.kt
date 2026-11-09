package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.BuildConfig
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.TwelveKeyPreedit
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
    /**
     * 面板左列「每個音節」最多列幾個讀音：只列高頻
     * （用戶 2026-11-10：不要把首字母的所有組合都羅列）。
     */
    private const val MAX_SYLLABLE_GROUPS = 8

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
    fun cacheKey(
        local: Local,
        full: Boolean,
        segmentAnchor: Char? = null,
        segmented: Boolean = false,
    ): String =
        local.input + "|" + local.segmentsKey +
            (if (full) "|full" else "|bar") + "|m" + SelectionMemory.version +
            (if (segmentAnchor == null) "" else "|a" + segmentAnchor.code) +
            (if (segmented) "|seg" else "")

    /**
     * 全量枚舉 + 分組；不讀引擎以外的東西。調用方需持引擎鎖（可在後台線程）。
     *
     * @param applyLearning 是否套用 App 側學習排序（12 鍵候選條自己排序；非 12 鍵方案的
     *   候選條由 librime 直接提供，面板必須保持引擎順序，否則面板順序 ≠ 候選條順序）。
     * @param mapFallbackLetters 兜底是否把 12 鍵碼映射成拼音字母（非 12 鍵方案輸入的就是
     *   字母，原樣上屏即可）。
     */
    fun build(
        local: Local,
        maxCandidates: Int = MAX_CANDIDATES,
        applyLearning: Boolean = true,
        mapFallbackLetters: Boolean = true,
        segmentAnchor: Char? = null,
        segmented: Boolean = false,
    ): TwelveKeySnapshot {
        if (local.isEmpty) return TwelveKeySnapshot.EMPTY
        val input = local.input
        val consumedEnd = local.consumedEnd
        val remaining = local.remaining

        // 全量枚舉：native 由 engine-probe 並行補，可能 UnsatisfiedLinkError → 降級單頁。
        // 每條候選必須帶著「引擎全域下標」——重排後顯示順序 ≠ 引擎順序，
        // 點擊時要回傳原始下標（否則就是「貨不對版」）。
        val flat: List<CandidateItem>
        var degraded = false
        val snapshot = runCatching { Rime.candidatesSnapshot(maxCandidates) }.getOrNull()
        if (snapshot != null) {
            flat = snapshot.mapIndexed { index, row ->
                CandidateItem(
                    text = row.getOrNull(0) ?: "",
                    comment = cleanComment(row.getOrNull(1)),
                    index = index,
                )
            }
        } else {
            degraded = true
            flat = singlePageFallback().mapIndexed { index, (text, comment) ->
                CandidateItem(text, cleanComment(comment), index)
            }
        }

        // App 側學習排序：選過的詞（同讀音+同詞）排前，候選條與面板都吃這個順序。
        val ranked = if (applyLearning && SelectionMemory.hasAny()) {
            flat.sortedWith(
                compareByDescending<CandidateItem> {
                    SelectionMemory.score(it.text, it.comment)
                }.thenBy { it.index },
            )
        } else {
            flat
        }
        // 分詞（theme 的 composing: delimiter → 送出 '）後，引擎只會給第一段候選；
        // 這裡按「段」補上字典候選：每段列出該碼對應的讀音與字，點擊後上屏該字、
        // 再把後面幾段的碼重新餵回引擎 —— 讓「分詞」真正能逐段聯想。
        // 分詞段組放在最前面：它們才是「逐段選字」的操作入口，往後塞會被幾十個引擎組淹沒。
        // 多音節輸入一律按「碼位」拆段：面板先列第一個待選段的「高頻音節 → 單字」組
        // （n**、h**、j**…），點字上屏後把其餘碼餵回引擎繼續——不再依賴用戶按 `'`（用戶 2026-11-10：
        // 之前 `'` 那套作廢，用這套按序推進的實現）。
        val segmentGroups = segmentGroupsFor(remaining, segmentAnchor)
        // 展開面板的「音節 → 漢字」列表也按常用度排：用戶常用字 → 通用字頻 → 引擎原順序。
        val engineGroups = group(ranked).map { g -> g.copy(items = rankPanelItems(g.items)) }
        val groups = segmentGroups + engineGroups
        // 候選條按「選單順序」取前 N 條：長輸入時 reading 各不相同，
        // 按第 0 組取會只剩 1 條（用戶 2026-11-08 反饋「打到這麼多就停止候選」）。
        // 分詞輸入（`5'e`）時，候選條直接列「這一段能選的字」：用戶點一個字就上屏，
        // 再把剩下的碼餵回引擎，於是第二段接著在候選條出現 —— 全程不開面板（用戶 2026-11-09 指示）。
        // 有 segmentAnchor（剛上屏的前一個字）時，與它關聯度高的字排前面。
        // 只剩最後一段時，用引擎候選（引擎本身按詞頻/用戶詞庫排序）再疊用戶關聯度，
        // 而不是繼續用字典順序 —— 否則「最後一個字」看起來完全不受詞頻影響（用戶 2026-11-09 反饋）。
        val barEntries = wordsFirst(ranked).take(BAR_ITEMS).filter { it.text.isNotEmpty() }
        // 預覽文字與候選條第 0 條同源：一次算好，隨快照發佈。
        val previewText = barEntries.firstOrNull { it.text.isNotEmpty() }?.text ?: ""
        // 已選前綴（librime context 裡尚未 commit 的字）+ 第一條候選 = 嵌入式預覽全文
        val inlineText = runCatching { Rime.getCompositionPrefix() }.getOrDefault("") + previewText
        val fallback = if (groups.isEmpty() && remaining.isNotEmpty()) {
            val raw = remaining.takeLast(MAX_FALLBACK_CODE)
            Fallback(rawCode = raw, letters = if (mapFallbackLetters) toLetters(raw) else raw)
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
            previewText = previewText,
            inlineText = inlineText,
        )
    }

    /**
     * 多音節輸入 → 「第一個待選段」的音節→單字組。
     *
     * 不區分「有沒有按分詞鍵」：碼串先去掉 `'`，再按碼位切出第一段與其餘碼。
     * - 完整拼音（如 `5e5e`）：切成 `5e` + `5e`；
     * - 首字母相拼（如 `855`、`4i`）：切成第一個碼位 + 其餘碼，`syllablesForSegment` 會展開成
     *   「以該字母開頭的音節」，只取高頻前 [MAX_SYLLABLE_GROUPS] 個（用戶 2026-11-10：
     *   不可能把每個首字母的所有組合都羅列）。
     *
     * 每項帶 `segment = 0` 與 `restCode = 其餘碼`：點擊 = 上屏這個字 + 其餘碼餵回引擎，
     * 面板接著列下一段的單字（按順序推進）。
     */
    private fun segmentGroupsFor(remaining: String, anchor: Char?): List<SyllableGroup> {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) return emptyList()
        val split = TwelveKeyPreedit.splitFirstSyllable(codes) ?: return emptyList()
        val (head, rest) = split
        val result = ArrayList<SyllableGroup>()
        for (syllable in TwelveKeyPreedit.syllablesForSegment(head, MAX_SYLLABLE_GROUPS)) {
            val chars = TwelveKeyPreedit.rankChars(
                TwelveKeyPreedit.charsForSyllable(syllable),
                anchor,
            )
            val items = chars.map { ch ->
                CandidateItem(text = ch, comment = syllable, index = -1, segment = 0, restCode = rest)
            }
            if (items.isEmpty()) continue
            result.add(
                SyllableGroup(
                    reading = syllable,
                    syllables = listOf(syllable),
                    items = items,
                    segment = 0,
                ),
            )
        }
        // 組之間按「組內第一字與前一字的關聯度」排：打完「區」後，含「域」的那組排最前。
        if (anchor != null) {
            result.sortWith(
                compareByDescending { g ->
                    SegmentMemory.bigramScore(anchor, g.items.first().text)
                },
            )
        }
        return result
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

    /**
     * rime-ice 這類方案的 `translator/comment_format` 會把拼音包成 `［…］`
     * （`xform/^/［/` + `xform/$/］/`），面板左列的分組鍵與候選下面那行註釋都不該帶全角括號，
     * 這裡統一把包裝剝掉；其他形式的 comment 原樣保留。
     */
    private fun cleanComment(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.length >= 2 && text.startsWith("\uFF3B") && text.endsWith("\uFF3D")) {
            return text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun singlePageFallback(): List<Pair<String, String>> {
        val page = runCatching { Rime.getCandidatesWithoutSwitch() }.getOrNull() ?: return emptyList()
        return page.map { (it.text ?: "") to (it.comment ?: "") }
    }

    /** 按 comment 分組；組順序 = 首次出現順序，不重排。 */
    /**
     * 候選條「詞組優先」：在**已按所有因素（引擎詞頻 / 用戶記憶 / 關聯度）排好**的順序上做穩定分區，
     * 把漢字詞組（≥2 個漢字）整體提前，其餘（單字、顏文字、拉丁詞）保持原相對順序。
     *
     * 需求（用戶 2026-11-09）：候選欄永遠是「組詞 > 單字」，但這個分區必須在全部排序之後做，
     * 否則會蓋掉詞頻與用戶習慣的排序。
     */
    private fun wordsFirst(items: List<CandidateItem>): List<CandidateItem> {
        if (items.size < 2) return items
        val words = ArrayList<CandidateItem>(items.size)
        val rest = ArrayList<CandidateItem>(items.size)
        items.forEach { if (isCjkWord(it.text)) words.add(it) else rest.add(it) }
        if (words.isEmpty() || rest.isEmpty()) return items
        return words + rest
    }

    /**
     * 展開面板「音節 → 漢字」列表排序：用戶常用字 → 綜合通用頻度 → 引擎原順序（穩定）。
     *
     * 引擎候選順序對單字並不可靠（詞典無 weight 時基本是文件順序，生僻字會排前面），
     * 所以最後用綜合頻度（詞表統計 + 權威 8105 字表權重）做硬排序，只在同分時才看引擎順序。
     */
    private fun rankPanelItems(items: List<CandidateItem>): List<CandidateItem> {
        if (items.size < 2) return items
        val sorted = items.sortedWith(
            compareByDescending<CandidateItem> { SegmentMemory.charScore(it.text) }
                .thenByDescending { TwelveKeyPreedit.generalScore(it.text) }
                .thenBy { it.index },
        )
        if (BuildConfig.DEBUG) {
            Timber.d(
                "twelvekey:panel order=%s scores=%s",
                sorted.take(8).joinToString("") { it.text },
                sorted.take(8).joinToString(",") { "${it.text}:${TwelveKeyPreedit.generalScore(it.text)}" },
            )
        }
        return sorted
    }

    private fun isCjkWord(text: String): Boolean =
        text.length >= 2 && text.all { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }

    private fun group(items: List<CandidateItem>): List<SyllableGroup> {
        if (items.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<CandidateItem>>()
        items.forEach { item ->
            if (item.text.isEmpty()) return@forEach
            val reading = if (item.comment.isBlank()) BLANK_READING else item.comment
            grouped.getOrPut(reading) { ArrayList() }.add(item)
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
