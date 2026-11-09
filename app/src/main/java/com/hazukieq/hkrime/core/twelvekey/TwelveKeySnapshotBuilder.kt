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
    /** 面板左列**每個首字母**最多列幾個音節組（總數上限見 TwelveKeyPreedit.MAX_SYLLABLES_TOTAL=16）。 */
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
        wantGroups: Boolean = true,
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
        // 面板關閉時不需要分組（候選條只吃 ranked 順序）：跳過「音節桶輪轉 + 聯想詞 + 分組合併」，
        // 這是每次按鍵的主要開銷之一（用戶 2026-11-10：補聯想詞後按鍵卡頓）。
        val segmentGroups = if (wantGroups) segmentGroupsFor(remaining, segmentAnchor) else emptyList()
        // 展開面板的「音節 → 漢字」列表：選哪些音節由常用度/字頻決定，
        // 但最終順序按讀音字典序（用戶 2026-11-10：「按首字母→次字母排，不然還是找得艱難」）。
        val engineGroups = if (!wantGroups) emptyList() else CandidateRanking.orderGroups(
            group(ranked).map { g -> g.copy(items = rankPanelItems(g.items)) },
            charScore = { text -> SegmentMemory.charScore(text) },
            generalScore = { text -> TwelveKeyPreedit.generalScore(text) },
        )
        // 有段組時只保留引擎的詞組組，否則左列會被引擎的幾十個單音節讀音灌滿
        // （段組本身已按「每個首字母 8 個」限量）；單音節輸入沒有段組，引擎組照舊。
        // 段組 = 單字組；另抽出的聯想詞組按「確切讀音」與引擎組合併（引擎優先、聯想詞去重補在後面）。
        val assocGroups = segmentGroups.filter { it.segment == 1 }
        val baseSegmentGroups = segmentGroups.filter { it.segment != 1 }
        val merged = CandidateRanking.panelGroups(baseSegmentGroups, engineGroups)
        val groups = CandidateRanking.mergeAssociationGroups(merged, assocGroups)
            // 統一排序：單音節組 → 詞組 → 無讀音組，各檔內按讀音字典序；
            // 「無讀音組」（引擎沒給 comment）用第一條候選的首字讀音兜底，也要按字母排。
            .sortedWith(
                compareBy(
                    {
                        when {
                            it.syllables.isEmpty() -> 3
                            it.segment == 1 -> 1     // 聯想詞組單獨一檔
                            it.syllables.size == 1 -> 0
                            else -> 2               // 引擎詞組
                        }
                    },
                    {
                        (if (it.reading.isBlank() || it.reading == BLANK_READING) {
                            TwelveKeyPreedit.pinyinOfText(it.items.firstOrNull()?.text ?: "")
                        } else {
                            it.reading
                        }).lowercase()
                    },
                    { it.items.firstOrNull()?.text ?: "" },
                ),
            )
        // 候選條按「選單順序」取前 N 條：長輸入時 reading 各不相同，
        // 按第 0 組取會只剩 1 條（用戶 2026-11-08 反饋「打到這麼多就停止候選」）。
        // 分詞輸入（`5'e`）時，候選條直接列「這一段能選的字」：用戶點一個字就上屏，
        // 再把剩下的碼餵回引擎，於是第二段接著在候選條出現 —— 全程不開面板（用戶 2026-11-09 指示）。
        // 有 segmentAnchor（剛上屏的前一個字）時，與它關聯度高的字排前面。
        // 只剩最後一段時，用引擎候選（引擎本身按詞頻/用戶詞庫排序）再疊用戶關聯度，
        // 而不是繼續用字典順序 —— 否則「最後一個字」看起來完全不受詞頻影響（用戶 2026-11-09 反饋）。
        val barEntries = barList(ranked)
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
     *   「以該字母開頭的音節」：**每個首字母**各取高頻前 [MAX_SYLLABLE_GROUPS] 個
     *   （合計受 `TwelveKeyPreedit.MAX_SYLLABLES_TOTAL`=16 限制；用戶 2026-11-10 定案）。
     *
     * 每項帶 `segment = 0` 與 `restCode = 其餘碼`：點擊 = 上屏這個字 + 其餘碼餵回引擎，
     * 面板接著列下一段的單字（按順序推進）。
     */
    /** 單測用：把「第一待選段 → 組」的結果暴露出來（只讀，不碰引擎）。 */
    internal fun debugSegmentGroups(remaining: String, anchor: Char? = null): List<SyllableGroup> =
        segmentGroupsFor(remaining, anchor)

    private fun segmentGroupsFor(remaining: String, anchor: Char?): List<SyllableGroup> {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) return emptyList()
        // 一種碼串可能有多種合法切法（`744ii` = son+gi 或 song+g**），但面板只列**第一段**
        // 的「音節 → 單字」組，接著列「聯想詞組」行（`song g**` / `song h**`）——
        // 用戶 2026-11-10：「我要的是列出 song h**(<=6), song g**(<=6) 的联想词组，而不是 son·2」。
        // 整串就是一個音節（例如選完前幾段後只剩 `we`）→ 不拆，直接按這個音節出組；
        // 否則 splitHeads 會硬切出 `w` + `e`，`w` 不是音節 → 面板只剩「本地原文」一行。
        val splits = if (TwelveKeyPreedit.isSingleSyllable(codes)) {
            listOf(codes to "")
        } else {
            TwelveKeyPreedit.splitHeads(codes)
        }
        if (splits.isEmpty()) return emptyList()
        val (head, rest) = splits.first()
        val result = ArrayList<SyllableGroup>()
        val seen = HashSet<String>()
        val syllables = TwelveKeyPreedit.syllablesForSegment(head, MAX_SYLLABLE_GROUPS)
        for (syllable in syllables) {
            if (!seen.add(syllable)) continue
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

        // ---- 聯想詞組（用戶 2026-11-10）----
        // 行標題用**確切第二音節**（`song hau`、`song heu`…，與引擎組同一種寫法），
        // 稍後再與引擎組按讀音合併：引擎有就併進去，沒有才新建（補集）。
        val initials = TwelveKeyPreedit.initialsForPrefix(rest, ASSOC_INITIALS)
        if (initials.isNotEmpty()) {
            for (syllable in syllables.take(ASSOC_HEAD_SYLLABLES)) {
                TwelveKeyPreedit.associatedByReading(syllable, initials, ASSOC_WORDS, ASSOC_READINGS)
                    .forEach { (second, words) ->
                        if (words.isEmpty()) return@forEach
                        val reading = "$syllable $second"
                        val items = words.map { word ->
                            CandidateItem(
                                text = word,
                                comment = reading,
                                index = -1,
                                segment = 1, // 1 = 聯想詞：整詞上屏
                            )
                        }
                        result.add(
                            SyllableGroup(
                                reading = reading,
                                syllables = listOf(syllable, second),
                                items = items,
                                segment = 1,
                            ),
                        )
                    }
            }
        }

        // 組之間一律按讀音字典序（用戶 2026-11-10：先首字母、再次字母）；
        // 之前按「與上一字的關聯度」排，會讓同一段內的同音節組跳來跳去、難以掃讀。
        // anchor 只影響組內字的順序（rankChars），不再影響組的順序。
        return result.sortedWith(
            compareBy(
                { if (it.syllables.isEmpty()) 2 else if (it.syllables.size == 1) 0 else 1 },
                { it.reading.lowercase() },
            ),
        )
    }

    /** 每位音節列幾個聯想詞（用戶：≤6）。 */
    private const val ASSOC_WORDS = 6

    /** 聯想詞行只對前幾個高頻音節建（避免左列被灌滿）。 */
    private const val ASSOC_HEAD_SYLLABLES = 4

    /** 聯想詞行最多列幾個「下一個音節首字母」（`song g**` / `song h**`）。 */
    private const val ASSOC_INITIALS = 2

    /** 每個第一段音節最多列幾個「第二段讀音」的聯想行。 */
    private const val ASSOC_READINGS = 4

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

    /** 面板右側列表排序（委託 [CandidateRanking]，純函數便於單測）。 */
    private fun rankPanelItems(items: List<CandidateItem>): List<CandidateItem> {
        val sorted = CandidateRanking.rankPanelItems(
            items = items,
            charScore = { SegmentMemory.charScore(it) },
            generalScore = { TwelveKeyPreedit.generalScore(it) },
        )
        if (BuildConfig.DEBUG && sorted !== items) {
            // 打印的就是比較鍵本體（用戶分/通用分），排查時「降序」要按這個順序看：
            // 先看 u（用戶常用字），同 u 再看 g（權威+詞表綜合頻度）。
            Timber.d(
                "twelvekey:panel order=%s keys=%s",
                sorted.take(8).joinToString("") { it.text },
                sorted.take(8).joinToString(",") {
                    "${it.text}:u${SegmentMemory.charScore(it.text)}/g${TwelveKeyPreedit.generalScore(it.text)}"
                },
            )
        }
        return sorted
    }

    /** 面板左列分組（委託 [CandidateRanking]）。 */
    private fun group(items: List<CandidateItem>): List<SyllableGroup> =
        CandidateRanking.groupCandidates(items, BLANK_READING, MAX_GROUPS)

    /** 候選條最終列表（委託 [CandidateRanking]）。 */
    private fun barList(ranked: List<CandidateItem>): List<CandidateItem> =
        CandidateRanking.rankBar(ranked, BAR_ITEMS)
}
