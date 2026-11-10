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
        val segmentGroups = if (wantGroups || needDictionaryFallback) {
            segmentGroupsFor(remaining, segmentAnchor)
        } else {
            emptyList()
        }
        // 展開面板的「音節 → 漢字」列表：選哪些音節由常用度/字頻決定，
        // 但最終順序按讀音字典序（用戶 2026-11-10：「按首字母→次字母排，不然還是找得艱難」）。
        val engineGroups = if (!wantGroups) emptyList() else CandidateRanking.orderGroups(
            group(ordered).map { g -> g.copy(items = rankPanelItems(g.items)) },
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
        val barEntries = if (ordered.isEmpty() && segmentGroups.isNotEmpty()) {
            // 字典兜底：取「第一待選段」的音節組候選（與面板左列第 0 組同源），最多候選條格數。
            segmentGroups.filter { it.segment != 1 }
                .flatMap { g -> g.items }
                .distinctBy { it.text }
                .take(TwelveKeyTuning.BAR_ITEMS)
        } else {
            barList(ordered)
        }
        // 候選條放不下的續頁 → 面板左列第一個「推薦」組（用戶 2026-11-11 定案）。
        // 順序與候選條同源（[CandidateRanking.fullBarOrder]），起點是候選條之後的那一條：
        // 這樣連「被 4 條詞組配額擠掉的第 5..N 個詞」也一起收進來，而不是只剩單字續頁。
        val recommend = if (ordered.isEmpty() && segmentGroups.isNotEmpty()) {
            // 字典兜底時同樣把「沒上條的部分」放進推薦組（續頁語義不變）
            segmentGroups.filter { it.segment != 1 }
                .flatMap { g -> g.items }
                .distinctBy { it.text }
                .drop(barEntries.size)
                .take(TwelveKeyTuning.RECOMMEND_ITEMS)
        } else {
            recommendFrom(ordered)
        }
        // 推薦組固定排左列第一（reading 為空 + [RECOMMEND_SEGMENT] 標記）；空的就不插，
        // 這樣「候選全上了條」時段組自然回到第 0 組。
        val groupsWithRecommend = if (recommend.isEmpty()) {
            groups
        } else {
            listOf(
                SyllableGroup(
                    reading = "",
                    syllables = emptyList(),
                    items = recommend,
                    segment = RECOMMEND_SEGMENT,
                ),
            ) + groups
        }
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
    /** 單測用：把「第一待選段 → 組」的結果暴露出來（只讀，不碰引擎）。 */
    internal fun debugSegmentGroups(remaining: String, anchor: Char? = null): List<SyllableGroup> =
        segmentGroupsFor(remaining, anchor)

    private fun segmentGroupsFor(remaining: String, anchor: Char?): List<SyllableGroup> {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) return emptyList()
        // 一種碼串可能有多種合法切法（`744ii` = son+gi 或 song+g**），但面板只列**第一段**
        // 的「音節 → 單字」組，接著列「聯想詞組」行（`song g**` / `song h**`）——
        // 用戶 2026-11-10：「我要的是列出 song h**(<=6), song g**(<=6) 的联想词组，而不是 son·2」。
        // 兩種解讀都要給（用戶 2026-11-10 定案：「剩下那兩個編碼不是兩個了嗎？每兩個處理完->遞歸」）：
        //  1) 整串是一個音節（`we` = the）→ 先列這個音節的單字組；
        //  2) 再把每個碼位當獨立段切開（`w` = th*、`e` = d/e/f*），給出「第一段的音節組 + 聯想詞行」，
        //     點下去之後剩餘碼自動遞歸（pickSegmentedCandidate 會把 rest 餵回引擎）。
        // 早期版本只做 (1)，於是 `we` 面板只剩 the 系列、聯想詞全無。
        val single = if (TwelveKeyPreedit.isSingleSyllable(codes)) listOf(codes to "") else emptyList()
        val splits = (single + TwelveKeyPreedit.splitHeads(codes)).distinctBy { it.first }
        if (splits.isEmpty()) return emptyList()
        val result = ArrayList<SyllableGroup>()
        val seen = HashSet<String>()
        // ---- 整串碼前綴聯想：三音節以上的詞（`li nak si` → Linux）只能靠這條 ----
        // 舊邏輯的聯想索引只按前兩個音節建 key，打第三節（`li nak s`）時查不到；
        // 用戶 2026-11-11：「詞庫是有 li nak si->linux，面板居然沒有」。
        if (codes.length >= 2 && result.size < MAX_RESULT_GROUPS) {
            val prefixRows = TwelveKeyPreedit.wordsByCodePrefix(
                codes,
                TwelveKeyTuning.ASSOC_PREFIX_READINGS,
                ASSOC_WORDS,
            )
            for ((reading, words) in prefixRows) {
                if (words.isEmpty()) continue
                if (result.size >= MAX_RESULT_GROUPS) break
                // 與現有聯想行去重：同一個讀音只要一份（兩音節詞通常已被上面的關聯行拿走）。
                if (!seen.add("$reading|any")) continue
                val items = words.map { word ->
                    CandidateItem(text = word, comment = reading, index = -1, segment = 1)
                }
                result.add(
                    SyllableGroup(
                        reading = reading,
                        syllables = reading.split(' '),
                        items = items,
                        segment = 1,
                    ),
                )
            }
        }

        splits.forEachIndexed { splitIndex, split ->
            val (head, rest) = split
            // 第一種切法吃完整配額；備選切法減半（面板不能被次要切法灌滿）。
            // 有段組時放寬音節數（用戶 2026-11-10）：普通鍵 16、音節豐富的鍵 24；
            // 備選切法減半，避免次要解讀把面板灌滿。
            val headPool = TwelveKeyPreedit.syllableCountForCode(split.first)
            val baseLimit = if (headPool >= RICH_SYLLABLE_THRESHOLD) {
                SEGMENT_SYLLABLE_LIMIT_RICH
            } else {
                SEGMENT_SYLLABLE_LIMIT
            }
            val quota = if (splitIndex == 0) {
                baseLimit
            } else {
                baseLimit / TwelveKeyTuning.SECONDARY_SPLIT_DIVISOR
            }
            // 備選切法：head 本身是完整音節時只取精確碼（避免和前綴重複）；
            // head 只是裸碼（`w`）時必須允許前綴展開，否則 th* 全被吃掉。
            val exactOnly = splitIndex > 0 && TwelveKeyPreedit.isSingleSyllable(head)
            val syllables = TwelveKeyPreedit.syllablesForSegment(head, quota, exactOnly)
            for (syllable in syllables) {
                // 同一個音節可能由多種切法得到（`the` 既可整段、也可 w|e）：
                // 只留第一種切法那份，避免面板出現 `then` / `then·1` 這種重複行。
                if (!seen.add(syllable)) continue
                val chars = TwelveKeyPreedit.rankChars(
                    TwelveKeyPreedit.charsForSyllable(
                        syllable,
                        TwelveKeyTuning.SEGMENT_CHARS_LIMIT,
                    ),
                    anchor,
                )
                val items = chars.map { ch ->
                    CandidateItem(
                        text = ch,
                        comment = syllable,
                        index = -1,
                        segment = 0,
                        restCode = rest,
                    )
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

            // ---- 聯想詞組：按這一種切法的剩餘碼推第二段首字母 ----
            val initials = TwelveKeyPreedit.initialsForPrefix(rest, ASSOC_INITIALS)
            if (initials.isNotEmpty()) {
                var assocAdded = 0
                for (syllable in syllables.take(ASSOC_HEAD_SYLLABLES)) {
                    if (assocAdded >= ASSOC_MAX_ROWS) break
                    // 寬鬆行（第二段只按首字母匹配）：先算，數量受 ASSOC_INITIAL_ROWS 限制，
                    // 用戶 2026-11-10「散一點沒關係，但增補聯想組不要給太多」。
                    TwelveKeyPreedit.associatedBySegmentCode(
                        syllable, rest, ASSOC_WORDS, ASSOC_INITIAL_ROWS,
                    ).forEach { (reading, words) ->
                        if (words.isEmpty() || assocAdded >= ASSOC_MAX_ROWS) return@forEach
                        if (!seen.add("$reading|any")) return@forEach
                        val items = words.map { word ->
                            CandidateItem(text = word, comment = reading, index = -1, segment = 1)
                        }
                        result.add(
                            SyllableGroup(
                                reading = reading,
                                syllables = listOf(syllable),
                                items = items,
                                segment = 1,
                            ),
                        )
                        assocAdded++
                    }
                    TwelveKeyPreedit.associatedByReading(syllable, initials, ASSOC_WORDS, ASSOC_READINGS)
                        .forEach { (second, words) ->
                            if (words.isEmpty() || assocAdded >= ASSOC_MAX_ROWS) return@forEach
                            val reading = "$syllable $second"
                            if (!seen.add("$reading|any")) return@forEach
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
                            assocAdded++
                        }
                }
            }
        }

        // 組之間一律按讀音字典序（用戶 2026-11-10：先首字母、再次字母）；
        // 之前按「與上一字的關聯度」排，會讓同一段內的同音節組跳來跳去、難以掃讀。
        // anchor 只影響組內字的順序（rankChars），不再影響組的順序。
        // 聯想行（segment=1）總數封頂：整串碼前綴行先佔位（它們對當前輸入更具體），
        // 其餘按原順序補；超過 [ASSOC_MAX_ROWS] 的行直接丟掉（用戶 2026-11-10 定的配額）。
        var assocRows = 0
        val capped = ArrayList<SyllableGroup>(result.size)
        for (group in result) {
            if (group.segment == 1) {
                if (assocRows >= ASSOC_MAX_ROWS) continue
                assocRows++
            }
            capped.add(group)
        }
        return capped.sortedWith(
            compareBy(
                { if (it.syllables.isEmpty()) 2 else if (it.syllables.size == 1) 0 else 1 },
                { it.reading.lowercase() },
            ),
        )
    }

    /** 每位音節列幾個聯想詞（用戶：≤6）。 */
    // ---- 聯想詞配額：集中在 [TwelveKeyTuning]（改值請改 Tuning）----
    private const val ASSOC_WORDS = TwelveKeyTuning.ASSOC_WORDS
    private const val ASSOC_HEAD_SYLLABLES = TwelveKeyTuning.ASSOC_HEAD_SYLLABLES
    private const val ASSOC_INITIAL_ROWS = TwelveKeyTuning.ASSOC_INITIAL_ROWS
    private const val ASSOC_MAX_ROWS = TwelveKeyTuning.ASSOC_MAX_ROWS
    private const val ASSOC_INITIALS = TwelveKeyTuning.ASSOC_INITIALS
    private const val ASSOC_READINGS = TwelveKeyTuning.ASSOC_READINGS

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
