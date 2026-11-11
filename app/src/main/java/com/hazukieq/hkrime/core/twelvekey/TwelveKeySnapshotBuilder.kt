package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.util.DiagLog
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
    /** 一次向 librime 要多少條候選（見 [TwelveKeyTuning.MAX_CANDIDATES]）。 */
    const val MAX_CANDIDATES = TwelveKeyTuning.MAX_CANDIDATES

    /** 候選條一頁顯示幾條（見 [TwelveKeyTuning.BAR_ITEMS]）。 */
    private const val BAR_ITEMS = TwelveKeyTuning.BAR_ITEMS

    /** 面板分組總數上限（見 [TwelveKeyTuning.MAX_GROUPS]）。 */
    private const val MAX_GROUPS = TwelveKeyTuning.MAX_GROUPS

    // ---- 單音節組配額：全部集中在 [TwelveKeyTuning]，這裡只是別名（改值請改 Tuning）----
    private const val MAX_SYLLABLE_GROUPS = TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT
    private const val SEGMENT_SYLLABLE_LIMIT = TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT
    private const val SEGMENT_SYLLABLE_LIMIT_RICH = TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT_RICH
    private const val RICH_SYLLABLE_THRESHOLD = TwelveKeyTuning.RICH_SYLLABLE_THRESHOLD

    /** 無候選時「本地原文」最多顯示多少碼位字母（見 [TwelveKeyTuning.MAX_FALLBACK_CODE]）。 */
    private const val MAX_FALLBACK_CODE = TwelveKeyTuning.MAX_FALLBACK_CODE

    /** 沒讀音 comment 的組佔位標題（見 [TwelveKeyTuning.BLANK_READING]）。 */
    private const val BLANK_READING = TwelveKeyTuning.BLANK_READING

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
            "|p" + PhraseMemory.version +
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
        /** 單測注入：非 null 時不碰引擎，直接用這份候選（text/comment/index）。 */
        candidatesOverride: List<CandidateItem>? = null,
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
        if (candidatesOverride != null) {
            // 單測：完全繞開引擎（bar/full 兩條構建路徑要對同一份候選給出同一份「候選條 + 預覽 + 兜底」）。
            flat = candidatesOverride
        } else {
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
        }

        // App 側學習排序：選過的詞（同讀音+同詞）排前，候選條與面板都吃這個順序。
        // 但學習只能在「同一覆蓋深度」內重排 —— 否則學過的兩音節詞（共產 / kiung than）
        // 會把覆蓋全部輸入的三音節詞（共產黨 / kiung than dong）擠下去，
        // preedit 跟著少一個音節（用戶 2026-11-10：「打完整 kiung than dong，最後一個 dong 被吃掉」）。
        val topSyllables = flat.firstOrNull { it.text.isNotEmpty() }
            ?.let { CandidateRanking.syllableCount(it.comment) } ?: 0
        val ranked = if (applyLearning && SelectionMemory.hasAny()) {
            CandidateRanking.rankWithLearning(flat, topSyllables) {
                SelectionMemory.score(it.text, it.comment)
            }
        } else {
            flat
        }
        // App 側學到的「逐段拼出來的組詞」（引擎裝不出整串）：作為合成候選混進 ranked。
        // 只有 12 鍵且開啟學習排序時才做（26 鍵候選條由 librime 直接給，必須保持引擎順序）。
        val ordered = if (applyLearning) {
            mergeLearnedPhrases(ranked, PhraseMemory.normalizeCode(input), System.currentTimeMillis())
        } else {
            ranked
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
        // 引擎一條候選都沒給（`ee5` 這種碼串在詞典/方案裡裝不出來）時，**候選條不能退成字母**：
        // 面板本來就會列「第一待選段的字典單字」（`de` 讀音下的 邸/的/地…），候選條也該用同一批。
        // 這樣候選條、面板、preedit 三者同源（用戶 2026-11-11：候選詞不該一會字母一會 邸）。
        val needDictionaryFallback = ordered.isEmpty() && remaining.isNotEmpty()
        // 左列走**唯一流水線**（見 docs/19 §3）：五源 → mergeRows → collapse → tier/sort → capRows。
        // 面板關閉（wantGroups=false）時不建分組；但「引擎一條候選都沒有」時要用同一條流水線
        // 產生的段組給候選條兜底（否則候選條會退成原始字母，見 docs/10 §15.74）。
        val panel: PanelBuild = when {
            wantGroups -> buildPanelRows(
                remaining = remaining,
                anchor = segmentAnchor,
                engineGroups = engineGroupsOf(ordered),
                includePanelOnlySources = true,
            )
            needDictionaryFallback -> buildPanelRows(
                remaining = remaining,
                anchor = segmentAnchor,
                engineGroups = emptyList(),
                includePanelOnlySources = false,
            )
            else -> PanelBuild(emptyList(), PanelStats.EMPTY)
        }
        val panelRows = panel.rows
        // 字典兜底只用「單字組」（segment != 1）：候選條不需要聯想/詞行。
        val dictionaryRows = panelRows.filter { it.segment != 1 }
        // 候選條按「選單順序」取前 N 條：長輸入時 reading 各不相同，
        // 按第 0 組取會只剩 1 條（用戶 2026-11-08 反饋「打到這麼多就停止候選」）。
        // 分詞輸入（`5'e`）時，候選條直接列「這一段能選的字」：用戶點一個字就上屏，
        // 再把剩下的碼餵回引擎，於是第二段接著在候選條出現 —— 全程不開面板（用戶 2026-11-09 指示）。
        // 有 segmentAnchor（剛上屏的前一個字）時，與它關聯度高的字排前面。
        // 只剩最後一段時，用引擎候選（引擎本身按詞頻/用戶詞庫排序）再疊用戶關聯度，
        // 而不是繼續用字典順序 —— 否則「最後一個字」看起來完全不受詞頻影響（用戶 2026-11-09 反饋）。
        val barEntries = if (ordered.isEmpty() && panelRows.isNotEmpty()) {
            // 字典兜底：取「第一待選段」的音節組候選（與面板左列第 0 組同源），最多候選條格數。
            dictionaryRows
                .flatMap { g -> g.items }
                .distinctBy { it.text }
                .take(TwelveKeyTuning.BAR_ITEMS)
        } else {
            barList(ordered)
        }
        // 候選條放不下的續頁 → 面板左列第一個「推薦」組（用戶 2026-11-11 定案）。
        // 順序與候選條同源（[CandidateRanking.fullBarOrder]），起點是候選條之後的那一條：
        // 這樣連「被 4 條詞組配額擠掉的第 5..N 個詞」也一起收進來，而不是只剩單字續頁。
        val recommend = if (ordered.isEmpty() && panelRows.isNotEmpty()) {
            // 字典兜底時同樣把「沒上條的部分」放進推薦組（續頁語義不變）
            dictionaryRows
                .flatMap { g -> g.items }
                .distinctBy { it.text }
                .drop(barEntries.size)
                .take(TwelveKeyTuning.RECOMMEND_ITEMS)
        } else {
            recommendFrom(ordered)
        }
        // 推薦組固定排左列第一（reading 為空 + [RECOMMEND_SEGMENT] 標記）；空的就不插，
        // 這樣「候選全上了條」時段組自然回到第 0 組。
        val recommendRow = if (recommend.isEmpty()) {
            emptyList()
        } else {
            listOf(
                SyllableGroup(
                    reading = "",
                    syllables = emptyList(),
                    items = recommend,
                    segment = RECOMMEND_SEGMENT,
                ),
            )
        }
        val groupsWithRecommend = recommendRow + panelRows.map { it.toSyllableGroup() }
        // 預覽文字與候選條第 0 條同源：一次算好，隨快照發佈。
        val previewText = barEntries.firstOrNull { it.text.isNotEmpty() }?.text ?: ""
        // 已選前綴（librime context 裡尚未 commit 的字）+ 第一條候選 = 嵌入式預覽全文
        // App 側暫存的字（兜底逐段選字）也要算進嵌入式預覽，否則選中的字在輸入框裡看不到。
        val inlineText = TwelveKeyPreedit.pendingPrefix +
            runCatching { Rime.getCompositionPrefix() }.getOrDefault("") + previewText
        // 兜底的判據只能是「**引擎一條候選都沒有**」（ordered 為空），不能用 groups：
        // 面板關閉時 wantGroups=false，groups 本來就是空的（故意不建分組），
        // 用它判斷會讓每一幀都掛上 fallback → 候選條顯示原始字母、preedit 也退成原碼，
        // 而開面板（wantGroups=true）時又正常 —— 用戶 2026-11-11 報的
        // 「打字顯示 dek，展開關閉後才是 de 5 / 邸」就是這條（docs/10 §15.74）。
        val fallback = if (shouldShowFallback(barEntries.size, remaining)) {
            val raw = remaining.takeLast(MAX_FALLBACK_CODE)
            val dbg = runCatching { Rime.debug_enumerate_info() }.getOrElse { "n/a(${it.javaClass.simpleName})" }
            Timber.i("twelvekey: fallback remaining=%s flat=%d composing=%b %s",
                remaining, flat.size, runCatching { Rime.isComposing() }.getOrDefault(false), dbg)
            Fallback(rawCode = raw, letters = if (mapFallbackLetters) toLetters(raw) else raw)
        } else {
            null
        }
        if (degraded) {
            Timber.w("twelvekey: enumerate native missing, degraded to single page (%d)", flat.size)
        }
        DiagLog.d(
            "snapshot",
            "build code=${input} wantGroups=$wantGroups groups=${groupsWithRecommend.size}" +
                " bar=${barEntries.size} ${panel.stats.line()}" +
                " all=[" + groupsWithRecommend.joinToString(",") { it.reading.ifEmpty { "推荐" } } + "]",
        )
        DiagLog.d("panel", panel.stats.line())
        return TwelveKeySnapshot(
            input = input,
            consumedEnd = consumedEnd,
            remaining = remaining,
            groups = groupsWithRecommend,
            fallback = fallback,
            degradedToSinglePage = degraded,
            barEntries = barEntries,
            recommend = recommend,
            previewText = previewText,
            inlineText = inlineText,
        )
    }

    /**
     * 是否該顯示「引擎沒候選」的兜底：判據只能是**引擎候選數為 0**。
     *
     * 不能用面板分組數（`groups`）判斷：面板關閉時 `wantGroups=false`，groups 恆為空，
     * 用它判斷會讓每一幀都掛上 fallback → 候選條/preedit 顯示原始字母，
     * 開面板卻正常（用戶 2026-11-11：「打字顯示 dek，展開關閉後才是 de 5 / 邸」）。
     */
    internal fun shouldShowFallback(engineCandidateCount: Int, remaining: String): Boolean =
        engineCandidateCount == 0 && remaining.isNotEmpty()

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
    // =====================================================================================
    // 五源流水線（docs/19 §3）
    //
    // 固定形狀：每源一個純函數 → mergeRows（唯一合併點）→ collapsePrefixRows
    //          → tierAndSort（固定分檔）→ capRows（固定截斷）→ project 成 SyllableGroup。
    // 源函數**不做**跨源去重、不排序、不碰引擎/UI；同一種規則只寫一次。
    // =====================================================================================

    /**
     * 一種「合法第一段切法」展開後的結果。
     *
     * [syllables] 已按配額 / 精確音節規則算好，SYNTH_SINGLE、SPLIT_SINGLE、ASSOC 三個源共用同一份
     * （避免重複展開：`syllablesForSegment` 是每鍵成本的一部分）。
     */
    internal data class SplitPart(
        val head: String,
        val rest: String,
        val syllables: List<String>,
        /** head 本身就是一個完整音節（`4i`=mi、`ea`=da）→ 只列精確音節，不再前綴展開。 */
        val headIsCompleteSyllable: Boolean,
        /** 這段之後已經沒有碼（`rest` 為空）→ 允許 `r` 這種後綴音節。 */
        val allowSuffixOnly: Boolean,
    )

    /**
     * 把碼串拆成「合法第一段切法」並展開第一段音節（純函數，不碰引擎/UI）。
     *
     * 一種碼串可能有多種合法切法（`744ii` = son+gi 或 song+g**），但面板只列**第一段**：
     *  1) 整串是一個音節（`we` = the）→ 先列這個音節的單字組；
     *  2) 再把每個碼位當獨立段切開（`w` = th*、`e` = d/e/f*），給出「第一段的音節組 + 聯想詞行」，
     *     點下去之後剩餘碼自動遞歸（pickSegmentedCandidate 會把 rest 餵回引擎）。
     *
     * 配額：第一種切法吃完整配額；備選切法減半（[TwelveKeyTuning.SECONDARY_SPLIT_DIVISOR]），
     * 避免次要解讀把左列灌滿。音節豐富的鍵放開到 24（[TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT_RICH]）。
     */
    internal fun splitParts(codes: String): List<SplitPart> {
        if (codes.isEmpty()) return emptyList()
        val single = if (TwelveKeyPreedit.isSingleSyllable(codes)) listOf(codes to "") else emptyList()
        val splits = (single + TwelveKeyPreedit.splitHeads(codes)).distinctBy { it.first }
        if (splits.isEmpty()) return emptyList()
        return splits.mapIndexed { index, split ->
            val (head, rest) = split
            val headPool = TwelveKeyPreedit.syllableCountForCode(head)
            val baseLimit = if (headPool >= RICH_SYLLABLE_THRESHOLD) {
                SEGMENT_SYLLABLE_LIMIT_RICH
            } else {
                SEGMENT_SYLLABLE_LIMIT
            }
            val quota = if (index == 0) {
                baseLimit
            } else {
                baseLimit / TwelveKeyTuning.SECONDARY_SPLIT_DIVISOR
            }
            // 這一段的碼**剛好就是一個完整音節**（`4i`=mi、`ea`=fa、`74`=po…）時只列這個音節：
            // 用戶已經把音節打全了，再展開 `mia/min/nia…`（同前綴的更長音節）屬於噪音。
            // 只有「碼只是前綴」（如 `7` = p/s）時才做前綴展開；exact 為空會自動回退到展開。
            val headIsCompleteSyllable = TwelveKeyPreedit.isSingleSyllable(head)
            // 兒化後綴 `r`：只有「這段之後沒有碼了」（rest 空）才允許當音節；
            // 打在中間/開頭時不參與展開（用戶 2026-11-11 特殊規則）。
            val allowSuffixOnly = rest.isEmpty()
            val syllables = TwelveKeyPreedit
                .syllablesForSegment(head, quota, headIsCompleteSyllable, allowSuffixOnly = allowSuffixOnly)
                .ifEmpty {
                    TwelveKeyPreedit.syllablesForSegment(
                        head, quota, false, allowSuffixOnly = allowSuffixOnly,
                    )
                }
            SplitPart(
                head = head,
                rest = rest,
                syllables = syllables,
                headIsCompleteSyllable = headIsCompleteSyllable,
                allowSuffixOnly = allowSuffixOnly,
            )
        }
    }

    /**
     * 源 1：整串碼恰好是一個完整音節（`ia` → ga/ha）→ 該音節的單字組。
     *
     * 對應 `panel_probe.py` 的 `Data.segment_groups()` 中 `single = [(codes, "")]` 那一組。
     */
    internal fun synthSingleRows(parts: List<SplitPart>, anchor: Char?): List<PanelRow> {
        val part = parts.firstOrNull() ?: return emptyList()
        if (!part.headIsCompleteSyllable || part.rest.isNotEmpty()) return emptyList()
        return syllableRows(part, anchor, RowSource.SYNTH_SINGLE)
    }

    /**
     * 源 2：備選切法的第一段單字組（`we` → w|e 的 th* / d*…）。
     *
     * 若整串本身是一個音節，那份由 [synthSingleRows] 負責，這裡只出其餘切法；
     * 否則第一種切法就是「主切法」，吃完整配額。
     */
    internal fun splitSingleRows(parts: List<SplitPart>, anchor: Char?): List<PanelRow> {
        val synthPart = parts.firstOrNull()?.takeIf { it.headIsCompleteSyllable && it.rest.isEmpty() }
        val remainingParts = if (synthPart == null) parts else parts.drop(1)
        return remainingParts.flatMap { syllableRows(it, anchor, RowSource.SPLIT_SINGLE) }
    }

    /** 一組「音節 → 單字」行（單字組的共用構造）。 */
    private fun syllableRows(part: SplitPart, anchor: Char?, source: RowSource): List<PanelRow> =
        part.syllables.mapNotNull { syllable ->
            val chars = TwelveKeyPreedit.rankChars(
                TwelveKeyPreedit.charsForSyllable(syllable, TwelveKeyTuning.SEGMENT_CHARS_LIMIT),
                anchor,
            )
            if (chars.isEmpty()) return@mapNotNull null
            PanelRow(
                reading = syllable,
                items = chars.map { ch ->
                    CandidateItem(text = ch, comment = syllable, index = -1, segment = 0, restCode = part.rest)
                },
                segment = 0,
                restCode = part.rest,
                sources = setOf(source),
            )
        }

    /**
     * 源 3：兩音節聯想詞行（`ga zi`、`than dung`）。
     *
     * 行標題一律是兩個完整讀音（精確哈希 `(第一音節, 第二音節)`）。選行規則與
     * `tools/panel_probe.py` 的 `assoc_buckets()` + `pick_bucket_rows()` 完全一致：
     *
     * 1. 每個切法的第一段取**全部**音節候選（不受面板配額截斷，否則 `(g,b)` 這類桶會漏）；
     * 2. 第二段有剩餘碼 → 把它前綴展開成確切音節再逐個查；剩餘碼為空 → 取該第一音節的全部後續音節
     *    （整輪聯想，行標題仍是完整讀音）；
     * 3. 命中的行按 **（第一音節首字母, 第二音節首字母）** 分桶；桶內按詞數降序；
     * 4. 跨桶輪轉取行：每個「第一段首字母」組內先各桶第 1 行、再第 2 行…，組與組之間再輪轉；
     * 5. 總數受 [TwelveKeyTuning.ASSOC_MAX_ROWS] 封頂，最後按 reading 字典序排。
     *
     * 用戶 2026-11-11：「ghi+abc → g b, g c, h b, h c 4 個通配聯想詞組」——分桶就是為了
     * 每個通配組合都有代表，而不是被高頻桶整批佔滿。
     */
    internal fun assocRows(parts: List<SplitPart>): List<PanelRow> {
        val buckets = LinkedHashMap<Pair<Char, Char>, MutableList<PanelRow>>()
        for (part in parts) {
            // 第一段：**前綴展開全部音節**（`ia` → ga/gam/gan/gang/han/hau…），不能用配額後的列表。
            val firstCandidates = TwelveKeyPreedit.syllablesForSegment(
                part.head,
                ASSOC_EXPAND_LIMIT,
                exactOnly = false,
                totalLimit = ASSOC_EXPAND_LIMIT,
                allowSuffixOnly = part.allowSuffixOnly,
            )
            // 第二段：有剩餘碼才展開（`r` 這類後綴音節在這裡是合法結尾，固定 allowSuffixOnly=true）。
            val secondCandidates = if (part.rest.isEmpty()) {
                null
            } else {
                TwelveKeyPreedit.syllablesForSegment(
                    part.rest,
                    ASSOC_EXPAND_LIMIT,
                    exactOnly = false,
                    totalLimit = ASSOC_EXPAND_LIMIT,
                    allowSuffixOnly = true,
                )
            }
            for (first in firstCandidates) {
                val bySecond = TwelveKeyPreedit.assocSecondMap(first)
                if (bySecond.isEmpty()) continue
                // 剩餘碼展開不到任何音節（`we` 這種「像 the 但碼不是 wie」的輸入）→ 取該第一音節的
                // 全部後續音節（整輪聯想）。這與腳本 `if not second_candidates: allowed = by_second.keys`
                // 一致；否則 `kiung th` 這種用戶場景會一行聯想都沒有（2026-11-10 報過的問題）。
                val allowed = secondCandidates
                    ?.takeIf { it.isNotEmpty() }
                    ?.filter { bySecond.containsKey(it) }
                    ?: bySecond.keys.toList()
                for (second in allowed) {
                    if (second.isEmpty()) continue
                    val words = bySecond[second]?.take(ASSOC_WORDS) ?: continue
                    if (words.isEmpty()) continue
                    buckets.getOrPut(first[0] to second[0]) { ArrayList() }
                        .add(assocRow("$first $second", words))
                }
            }
        }
        return pickBucketRows(buckets, ASSOC_BUCKET_ROW_BUDGET)
    }

    /**
     * 分桶輪轉取行（`panel_probe.py: pick_bucket_rows` 的鏡像）。
     *
     * 先按（第一首字母, 第二首字母）排序；同一「第一首字母」組內各桶輪轉（第 1 行→第 2 行…），
     * 再在組之間輪轉；總數封頂後按 reading 字典序排序（顯示順序由後續 tierAndSort 再排一次）。
     */
    private fun pickBucketRows(
        buckets: Map<Pair<Char, Char>, List<PanelRow>>,
        maxRows: Int,
    ): List<PanelRow> {
        if (buckets.isEmpty() || maxRows <= 0) return emptyList()
        val sortedKeys = buckets.keys.sortedWith(compareBy({ it.first }, { it.second }))
        val groups = LinkedHashMap<Char, MutableList<List<PanelRow>>>()
        sortedKeys.forEach { key ->
            groups.getOrPut(key.first) { ArrayList() }
                // 桶內排序必須**確定**：詞數降序、同詞數按 reading。兩份實現（App/腳本）
                // 都按這個 key 排，否則桶內插入順序不同時會選中不同行（golden 會漂）。
                .add(
                    buckets.getValue(key).sortedWith(
                        compareByDescending<PanelRow> { it.items.size }.thenBy { it.reading }
                    )
                )
        }
        val flat = LinkedHashMap<Char, MutableList<PanelRow>>()
        groups.forEach { (initial, bucketRows) ->
            val rows = ArrayList<PanelRow>()
            var round = 0
            while (true) {
                var added = false
                for (bucket in bucketRows) {
                    if (round < bucket.size) {
                        rows.add(bucket[round])
                        added = true
                    }
                }
                if (!added) break
                round++
            }
            flat[initial] = rows
        }
        val cursors = HashMap<Char, Int>()
        val picked = ArrayList<PanelRow>(maxRows)
        val seen = HashSet<String>()
        while (picked.size < maxRows) {
            var added = false
            for ((initial, rows) in flat) {
                val cursor = cursors[initial] ?: 0
                if (cursor >= rows.size) continue
                cursors[initial] = cursor + 1
                val row = rows[cursor]
                if (!seen.add(row.reading)) continue
                picked.add(row)
                added = true
                if (picked.size >= maxRows) break
            }
            if (!added) break
        }
        return picked.sortedBy { it.reading }
    }

    private fun assocRow(reading: String, words: List<String>) = PanelRow(
        reading = reading,
        items = words.map { CandidateItem(text = it, comment = reading, index = -1, segment = 1) },
        segment = 1,
        sources = setOf(RowSource.ASSOC),
    )

    /**
     * 源 4：每鍵首字母笛卡爾積 → 按「讀音首字母序列」查詞表的行（`g·b` → `gang bung`）。
     *
     * 規則已在 `tools/panel_probe.py` 驗證（`initial_pattern_rows`）；本步驟先留空，
     * 接入與常數見 docs/19 §7 步驟 3。
     */
    internal fun initialComboRows(codes: String): List<PanelRow> {
        if (codes.length < 2) return emptyList()
        val combos = TwelveKeyPreedit.initialCombos(codes)
        if (combos.isEmpty()) return emptyList()
        val rows = ArrayList<PanelRow>(INITIAL_COMBO_MAX_ROWS)
        val seen = HashSet<String>()
        // 兩輪：第一輪每個組合先出第 1 行（保證「g·b / g·c / h·b / h·c」都有代表），
        // 第二輪再給每個組合補第 2 行。節奏由 INITIAL_COMBO_ROWS_PER_PATTERN 控制。
        val entriesByCombo = combos.map { TwelveKeyPreedit.wordsByInitialPattern(it) }
        for (round in 0 until 2) {
            val start = round * INITIAL_COMBO_ROWS_PER_PATTERN
            val stop = start + INITIAL_COMBO_ROWS_PER_PATTERN
            for (hits in entriesByCombo) {
                val from = if (start < hits.size) start else hits.size
                val to = if (stop < hits.size) stop else hits.size
                for (hit in hits.subList(from, to)) {
                    val reading = hit.first
                    if (!seen.add(reading)) continue
                    rows.add(
                        PanelRow(
                            reading = reading,
                            items = hit.second.map { word ->
                                CandidateItem(text = word, comment = reading, index = -1, segment = 1)
                            },
                            segment = 1,
                            sources = setOf(RowSource.INITIAL_COMBO),
                        )
                    )
                    if (rows.size >= INITIAL_COMBO_MAX_ROWS) return rows
                }
            }
        }
        return rows
    }

    /**
     * 源 5：整串碼前綴命中的多音節詞條（`li nak si` → Linux）。
     *
     * 走**輕路徑**：只按「整串碼前綴」查表（表只有 ~1.4k 條），不做引擎/大排序那條重路徑
     * （2026-11-11 實測：塞進重路徑會讓面板全量構建卡死幾十秒）。
     * 第一段必須打全（`wordsByCodePrefix` 已修，別回退）。
     */
    internal fun wholeWordRows(remaining: String): List<PanelRow> {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) return emptyList()
        val rows = runCatching {
            TwelveKeyPreedit.wordsByCodePrefix(
                codes,
                TwelveKeyTuning.ASSOC_PREFIX_READINGS,
                ASSOC_WORDS,
            ).map { (reading, words) ->
                PanelRow(
                    reading = reading,
                    items = words.map { word ->
                        CandidateItem(text = word, comment = reading, index = -1, segment = 1)
                    },
                    segment = 1,
                    sources = setOf(RowSource.WHOLE_WORD),
                )
            }
        }.getOrElse { emptyList() }
        if (BuildConfig.DEBUG && rows.isNotEmpty()) {
            Timber.d(
                "twelvekey: prefixWords %s",
                rows.joinToString(",") { "${it.reading}:${it.items.size}" },
            )
        }
        return rows
    }

    /**
     * 源 6：引擎候選組。
     *
     * 有段組（按碼位自切的單字組）時，引擎的**單音節組**只是同一批字的另一種排法：
     * 全列出來左列會被 g* 的幾十個讀音灌滿，所以只保留「同讀音的引擎組」（並進段組）
     * 與「多音節/含非漢字的組」——與舊 `CandidateRanking.panelGroups` 同一口徑。
     */
    internal fun engineRows(engineGroups: List<SyllableGroup>, segmentReadings: Set<String>): List<PanelRow> {
        if (engineGroups.isEmpty()) return emptyList()
        // 沒有任何段組時（單鍵/純碼位、段組還沒展開）引擎組原樣全留 —— 這是舊 panelGroups 的
        // `if (segmentGroups.isEmpty()) return engineGroups` 分支；不能套用下面的「只留詞組」過濾，
        // 否則單鍵輸入的單音節引擎組會整批消失（`8` 的 `ji` 就是這樣被吞掉的）。
        if (segmentReadings.isEmpty()) {
            return engineGroups.map { group ->
                PanelRow(group.reading, group.items, group.segment, sources = setOf(RowSource.ENGINE))
            }
        }
        val out = ArrayList<PanelRow>(engineGroups.size)
        engineGroups.forEach { group ->
            if (group.reading in segmentReadings) {
                // 同讀音引擎組會並進段組：漢字在前、非漢字補在後面（舊 panelGroups 的分區口徑）。
                val (cjk, other) = group.items.partition {
                    CandidateRanking.isSingleCjkChar(it.text) || CandidateRanking.isCjkWord(it.text)
                }
                out.add(
                    PanelRow(
                        reading = group.reading,
                        items = cjk + other,
                        segment = group.segment,
                        sources = setOf(RowSource.ENGINE),
                    )
                )
            } else if (group.syllables.size >= 2 ||
                group.items.any { !CandidateRanking.isSingleCjkChar(it.text) }
            ) {
                out.add(
                    PanelRow(
                        reading = group.reading,
                        items = group.items,
                        segment = group.segment,
                        sources = setOf(RowSource.ENGINE),
                    )
                )
            }
        }
        return out
    }

    /** 面板分組統計（只進 `diag.log` / `twelvekey:build`；UI 不可見）。 */
    internal data class PanelStats(
        val synth: Int = 0,
        val split: Int = 0,
        val assoc: Int = 0,
        val combo: Int = 0,
        val word: Int = 0,
        val engine: Int = 0,
        val merged: Int = 0,
        val collapsed: Int = 0,
        val capped: Int = 0,
        val tierSingles: Int = 0,
        val tierWords: Int = 0,
        val tierBlank: Int = 0,
    ) {
        /** 每源一行日誌（docs/19 §6.1）：出問題能直接判斷「哪源多給/少給/被截斷」。 */
        fun line(): String = "panel sources synth=$synth split=$split assoc=$assoc combo=$combo" +
            " word=$word engine=$engine → merged=$merged collapsed=$collapsed" +
            " tier=[single:$tierSingles word:$tierWords blank:$tierBlank] capped=$capped"

        companion object {
            val EMPTY = PanelStats()
        }
    }

    internal data class PanelBuild(val rows: List<PanelRow>, val stats: PanelStats)

    /**
     * 面板左列的**唯一入口**：五源 → 合併 → 收斂 → 分檔排序 → 截斷。
     *
     * @param includePanelOnlySources 面板打開時才產生的來源（首字母組合行、整串詞行）；
     *   候選條兜底只需要段組，傳 false 可省掉這兩源的查表成本。
     */
    internal fun buildPanelRows(
        remaining: String,
        anchor: Char?,
        engineGroups: List<SyllableGroup>,
        includePanelOnlySources: Boolean,
    ): PanelBuild = computePanel(remaining, anchor, engineGroups, includePanelOnlySources).build

    /**
     * 單測/診斷用：把五源與合併後的行都攤開（golden 對拍腳本 `panel_probe.py json`）。
     *
     * 僅供 `internal` 調用；UI 永遠只看到 [buildPanelRows] 投影出的 `SyllableGroup`。
     */
    internal fun debugPanelSources(codes: String): PanelSources =
        computePanel(codes, null, emptyList(), includePanelOnlySources = true).let { c ->
            PanelSources(
                synth = c.synth,
                split = c.split,
                assoc = c.assoc,
                combo = c.combo,
                wholeWord = c.word,
                merged = c.build.rows,
            )
        }

    /** 五源 + 合併後的最終行（[debugPanelSources] 的回傳型別）。 */
    internal data class PanelSources(
        val synth: List<PanelRow>,
        val split: List<PanelRow>,
        val assoc: List<PanelRow>,
        val combo: List<PanelRow>,
        val wholeWord: List<PanelRow>,
        val merged: List<PanelRow>,
    )

    private data class PanelComputation(
        val synth: List<PanelRow>,
        val split: List<PanelRow>,
        val engine: List<PanelRow>,
        val assoc: List<PanelRow>,
        val combo: List<PanelRow>,
        val word: List<PanelRow>,
        val build: PanelBuild,
    )

    private fun computePanel(
        remaining: String,
        anchor: Char?,
        engineGroups: List<SyllableGroup>,
        includePanelOnlySources: Boolean,
    ): PanelComputation {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) {
            return PanelComputation(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                PanelBuild(emptyList(), PanelStats.EMPTY),
            )
        }
        val parts = splitParts(codes)
        val synth = synthSingleRows(parts, anchor)
        val split = splitSingleRows(parts, anchor)
        val segmentReadings = HashSet<String>(synth.size + split.size)
        synth.forEach { segmentReadings.add(it.reading) }
        split.forEach { segmentReadings.add(it.reading) }
        val engine = engineRows(engineGroups, segmentReadings)
        val assoc = assocRows(parts)
        val combo = if (includePanelOnlySources) initialComboRows(codes) else emptyList()
        val word = if (includePanelOnlySources) wholeWordRows(remaining) else emptyList()

        // 拼接順序固定：段組 → 引擎 → 聯想 → 首字母組合 → 整串詞行。
        // mergeRows 的「引擎項在前、App 補集在後」「單字組(0) 優先於詞行(1)」都由這個順序決定。
        val merged = mergeRows(synth + split + engine + assoc + combo + word)
        val collapsed = collapsePrefixRows(merged)
        val sorted = tierAndSort(collapsed)
        val capped = capRows(sorted)
        return PanelComputation(
            synth = synth,
            split = split,
            engine = engine,
            assoc = assoc,
            combo = combo,
            word = word,
            build = PanelBuild(
                rows = capped,
                stats = PanelStats(
                    synth = synth.size,
                    split = split.size,
                    assoc = assoc.size,
                    combo = combo.size,
                    word = word.size,
                    engine = engine.size,
                    merged = merged.size,
                    collapsed = collapsed.size,
                    capped = capped.size,
                    tierSingles = sorted.count { it.syllableCount == 1 },
                    tierWords = sorted.count { it.syllableCount >= 2 },
                    tierBlank = sorted.count { it.syllableCount == 0 },
                ),
            ),
        )
    }

    /** 引擎候選 → 面板分組（面板專用；候選條模式不建）。 */
    private fun engineGroupsOf(ordered: List<CandidateItem>): List<SyllableGroup> {
        if (ordered.isEmpty()) return emptyList()
        return CandidateRanking.orderGroups(
            group(ordered).map { g -> g.copy(items = rankPanelItems(g.items)) },
            charScore = { text -> SegmentMemory.charScore(text) },
            generalScore = { text -> TwelveKeyPreedit.generalScore(text) },
        )
    }

    /** 單測用：把「第一待選段 → 組」的結果暴露出來（只讀，不碰引擎）。 */
    internal fun debugSegmentGroups(remaining: String, anchor: Char? = null): List<SyllableGroup> =
        buildPanelRows(
            remaining = remaining,
            anchor = anchor,
            engineGroups = emptyList(),
            includePanelOnlySources = false,
        ).rows.map { it.toSyllableGroup() }

    // ---- 聯想詞配額：集中在 [TwelveKeyTuning]（改值請改 Tuning）----
    private const val ASSOC_WORDS = TwelveKeyTuning.ASSOC_WORDS
    private const val ASSOC_HEAD_SYLLABLES = TwelveKeyTuning.ASSOC_HEAD_SYLLABLES
    private const val ASSOC_INITIAL_ROWS = TwelveKeyTuning.ASSOC_INITIAL_ROWS
    private const val ASSOC_MAX_ROWS = TwelveKeyTuning.ASSOC_MAX_ROWS
    private const val ASSOC_BUCKET_ROW_BUDGET = TwelveKeyTuning.ASSOC_BUCKET_ROW_BUDGET
    private const val ASSOC_INITIALS = TwelveKeyTuning.ASSOC_INITIALS
    private const val ASSOC_READINGS = TwelveKeyTuning.ASSOC_READINGS
    private const val ASSOC_EXPAND_LIMIT = TwelveKeyTuning.ASSOC_EXPAND_LIMIT
    private const val INITIAL_COMBO_MAX_ROWS = TwelveKeyTuning.INITIAL_COMBO_MAX_ROWS
    private const val INITIAL_COMBO_ROWS_PER_PATTERN = TwelveKeyTuning.INITIAL_COMBO_ROWS_PER_PATTERN

    /** 面板組數硬上限（防止字典前綴聯想把左列灌爆；正常一屏 ~10 行）。 */
    private const val MAX_RESULT_GROUPS = 40

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
    /**
     * 把 App 側學到的組詞（[PhraseMemory]）與引擎候選混排；沒有學到任何詞就原樣返回。
     *
     * 「純」的構造 + 混排都放在這裡，方便單測（不碰引擎，只吃 ranked 與記憶）。
     */
    internal fun mergeLearnedPhrases(
        ranked: List<CandidateItem>,
        code: String,
        now: Long,
    ): List<CandidateItem> {
        if (ranked.isEmpty() || code.isEmpty()) return ranked
        val phrases = PhraseMemory.candidatesFor(code, now)
        if (phrases.isEmpty()) return ranked
        // 引擎自己也會學（userdb / custom_phrase）：同一條詞已經在引擎候選裡就不再塞合成候選，
        // 否則候選條會出現兩個一模一樣的詞（用戶 2026-11-11 報「重複詞條」）。
        val engineTexts = ranked.mapTo(HashSet()) { it.text }
        val fresh = phrases.filterNot { engineTexts.contains(it.text) }
        if (fresh.isEmpty()) return ranked
        val scoreByText = fresh.associate { it.text to PhraseMemory.score(it, now) }
        return CandidateRanking.mergePhraseCandidates(
            ranked = ranked,
            synthetic = fresh.map { e ->
                CandidateItem(
                    text = e.text,
                    comment = e.readings,
                    index = -1,
                    segment = SYNTHETIC_SEGMENT,
                    // 合成候選把「完整碼串」放在 restCode：點擊上屏後用它記學習
                    restCode = code,
                )
            },
            syntheticScore = { item -> scoreByText[item.text] ?: 0.0 },
            engineScore = { i -> 1.0 - i.toDouble() / ranked.size.coerceAtLeast(1) },
        )
    }

    private fun barList(ranked: List<CandidateItem>): List<CandidateItem> =
        CandidateRanking.rankBar(ranked, BAR_ITEMS)

    /**
     * 面板「推薦」組的內容 = 候選條放不下的續頁。
     *
     * 純函數（便於單測）：與候選條共用 [CandidateRanking.fullBarOrder] 的順序，只取第
     * `BAR_ITEMS+1` 條起的前 [TwelveKeyTuning.RECOMMEND_ITEMS] 條。
     * 關鍵：必須用 `fullBarOrder` 而不是 `rankBar` 的結果 `drop`，否則被 4 條詞組配額
     * 擠掉的第 5..N 個詞會永遠看不到（用戶 2026-11-11 定案要收進推薦）。
     */
    internal fun recommendFrom(ordered: List<CandidateItem>): List<CandidateItem> {
        val order = CandidateRanking.fullBarOrder(ordered)
        if (order.isEmpty()) return order
        // 注意：不能用 `order.drop(BAR_ITEMS)` —— 候選條除「總條數 12」外還有「詞組區 ≤4」的配額，
        // 被配額擠掉的第 5..N 個詞在 order 的前 12 條裡，`drop(12)` 會把它們一起跳過，
        // 於是「推薦」就只剩單字續頁。這裡按「不在候選條裡」取補集。
        val onBar = CandidateRanking.rankBar(ordered, BAR_ITEMS).mapTo(HashSet()) { it.text }
        val rest = order.filterNot { onBar.contains(it.text) }
        // 布局與候選條同源（用戶 2026-11-11）：詞組在前、單字在後；差別是詞組配額放寬到
        // 「最多佔一半」（條上是 12 條裡的 4 條），其餘位置留給單字。
        val words = rest.filter { CandidateRanking.isCjkWord(it.text) }
        val singles = rest.filterNot { CandidateRanking.isCjkWord(it.text) }
        // 特例與候選條一致：完全沒有單字時，詞組才可以佔滿整個推薦組。
        if (singles.isEmpty()) return words.take(TwelveKeyTuning.RECOMMEND_ITEMS)
        val wordCap = TwelveKeyTuning.RECOMMEND_ITEMS / 2
        return (words.take(wordCap) + singles).take(TwelveKeyTuning.RECOMMEND_ITEMS)
    }
}
