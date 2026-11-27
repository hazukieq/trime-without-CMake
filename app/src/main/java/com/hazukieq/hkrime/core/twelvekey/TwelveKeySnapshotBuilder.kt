package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.util.DiagLog
import com.hazukieq.hkrime.BuildConfig
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import timber.log.Timber

/**
 * 構建不可變候選快照。調用方持 ENGINE_LOCK，local 讀取輸入/分段，
 * build 枚舉候選並生成候選條、面板與預覽。普通刷新在後台執行。
 */
object TwelveKeySnapshotBuilder {


    /** 一次向 librime 要多少條候選（見 [TwelveKeyTuning.MAX_CANDIDATES]）。 */
    const val MAX_CANDIDATES = TwelveKeyTuning.MAX_CANDIDATES

    /** 候選條一頁顯示幾條（見 [TwelveKeyTuning.BAR_ITEMS]）。 */
    private const val BAR_ITEMS = TwelveKeyTuning.BAR_ITEMS

    /** 面板分組總數上限（見 [TwelveKeyTuning.MAX_GROUPS]）。 */
    private const val MAX_GROUPS = TwelveKeyTuning.MAX_GROUPS

    // ---- 單音節組配額：全部集中在 [TwelveKeyTuning]，這裡只是別名（改值請改 Tuning）----
    private const val SEGMENT_SYLLABLE_LIMIT = TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT
    private const val SEGMENT_SYLLABLE_LIMIT_RICH = TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT_RICH
    private const val RICH_SYLLABLE_THRESHOLD = TwelveKeyTuning.RICH_SYLLABLE_THRESHOLD

    /** 無候選時「本地原文」最多顯示多少碼位字母（見 [TwelveKeyTuning.MAX_FALLBACK_CODE]）。 */
    private const val MAX_FALLBACK_CODE = TwelveKeyTuning.MAX_FALLBACK_CODE

    private const val FIRST_SEGMENT_CHARS = TwelveKeyTuning.FIRST_SEGMENT_CHARS

    /** 沒讀音 comment 的組佔位標題（見 [TwelveKeyTuning.BLANK_READING]）。 */
    private const val BLANK_READING = TwelveKeyTuning.BLANK_READING

    /** UI 線程同步算出的「本地部分」；segmentsKey 用於區分「input 不變但分段變了」。 */
    data class Local(
        val input: String,
        val consumedEnd: Int,
        val remaining: String,
        val segmentsKey: String,
        val rawPreedit: String = "",
    ) {
        val contextKey: String get() = input + "\u0000" + consumedEnd + "\u0000" + segmentsKey + "\u0000" + rawPreedit
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
            rawPreedit = Rime.getRawPreedit().orEmpty(),
        )
    }

    /** 快照緩存鍵：input + 分段（status 也會影響 consumedEnd）。 */
    fun cacheKey(
        local: Local,
        full: Boolean,
        segmentAnchor: Char? = null,
        segmented: Boolean = false,
        appRankingEnabled: Boolean = true,
    ): String =
        local.contextKey +
            "|p" + MultiKeyProfile.active.id +
            (if (full) "|full" else "|bar") +
            (if (segmentAnchor == null) "" else "|a" + segmentAnchor.code) +
            (if (segmented) "|seg" else "") + "|r" + (if (appRankingEnabled) "1" else "0")

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
        initial: String? = null,
        appRankingEnabled: Boolean = true,
        phonetics: List<PhoneticSelection> = emptyList(),
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
        val probeMore = wantGroups && phonetics.isNotEmpty() &&
            maxCandidates >= TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT &&
            maxCandidates < TwelveKeyTuning.CONFIRM_ALL_LIMIT
        val hasEngineMore: Boolean
        var __enumStartNs = System.nanoTime()
        var __enumMs = 0L
        if (candidatesOverride != null) {
            // 單測：完全繞開引擎（bar/full 兩條構建路徑要對同一份候選給出同一份「候選條 + 預覽 + 兜底」）。
            val filtered = candidatesOverride.filter { InitialDisambiguation.matches(initial, it.engineSpan?.sourceComment ?: it.comment) }
            hasEngineMore = probeMore && filtered.size > maxCandidates
            flat = filtered.take(maxCandidates)
        } else {
            __enumStartNs = System.nanoTime()
            val fetchLimit = if (probeMore) maxCandidates + 1 else maxCandidates
            val snapshot = runCatching { Rime.candidatesSnapshot(fetchLimit) }.getOrNull()
            __enumMs = (System.nanoTime() - __enumStartNs) / 1_000_000
            if (__enumMs >= 8) DiagLog.d("perf", "enum=${__enumMs}ms code=$input n=${snapshot?.size ?: -1}")

        if (snapshot != null) {
            hasEngineMore = probeMore && snapshot.size > maxCandidates
            flat = snapshot.take(maxCandidates).mapIndexed { index, row ->
                CandidateItem(
                    text = row.getOrNull(0) ?: "",
                    comment = cleanComment(row.getOrNull(1)),
                    index = row.getOrNull(5)?.toIntOrNull() ?: index,
                    engineSpan = EngineCandidateSpan.fromRow(row),
                )
            }
        } else {
                degraded = true
                hasEngineMore = false
                flat = singlePageFallback().mapIndexed { index, (text, comment) ->
                    CandidateItem(text, cleanComment(comment), index)
                }
            }
        }

        if (phonetics.isNotEmpty()) {
            return phoneticSnapshot(local, flat, wantGroups, phonetics, appRankingEnabled, degraded, maxCandidates, hasEngineMore)
        }
        if (!appRankingEnabled) return engineOnlySnapshot(local, flat, wantGroups, mapFallbackLetters, initial, degraded)

        val __tFlat = System.nanoTime()
        // 排序與學習已收歸 librime 原生引擎（Table 烘焙字頻 + UserDictionary 內存索引），
        // App 側不再用 SelectionMemory / PhraseMemory 二次重排或混排合成詞。
        val ordered = flat
        val __tRanked = System.nanoTime()
        val __tMerge0 = __tRanked
        // 12 鍵左列已改為確認行（confirmGroups），右列固定引擎候選；
        // 不再在 12 鍵主路徑構建 ASSOC / INITIAL_COMBO / WHOLE_WORD 面板補集。
        // 僅在非 12 鍵方案展開面板、或引擎完全無候選需要單字兜底時才構造段組。
        val needDictionaryFallback = ordered.isEmpty() && remaining.isNotEmpty()
        val panel: PanelBuild = when {
            !mapFallbackLetters && wantGroups -> buildPanelRows(
                remaining = remaining,
                anchor = segmentAnchor,
                engineGroups = engineGroupsOf(ordered),
                includePanelOnlySources = false,
                initial = initial,
            )
            needDictionaryFallback -> buildPanelRows(
                remaining = remaining,
                anchor = segmentAnchor,
                engineGroups = emptyList(),
                includePanelOnlySources = false,
                initial = initial,
            )
            else -> PanelBuild(emptyList(), PanelStats.EMPTY)
        }
        val panelRows = panel.rows
        // 字典兜底只用「單字組」（segment == 0）。
        val dictionaryRows = panelRows.filter { it.segment == 0 }
        // 候選條固定從前 BAR_CANDIDATES 條引擎窗口計算，確保 bar/full 前 12 項一致。
        val barOrdered = if (wantGroups && flat.size > TwelveKeyTuning.BAR_CANDIDATES) {
            flat.subList(0, TwelveKeyTuning.BAR_CANDIDATES)
        } else {
            ordered
        }
        val inputBinder = CandidateSelection.InputBinder(remaining, consumedEnd)
        val barCandidates = if (mapFallbackLetters) {
            CandidateRanking.preferCompleteFirst(barOrdered.map(inputBinder::bind), remaining)
        } else {
            barOrdered
        }
        val barEntries0 = if (ordered.isEmpty() && panelRows.isNotEmpty()) {
            // 字典兜底：取「第一待選段」的音節組候選（與面板左列第 0 組同源），最多候選條格數。
            dictionaryRows
                .flatMap { g -> g.items }
                .distinctBy { it.text }
                .take(TwelveKeyTuning.BAR_ITEMS)
        } else {
            withFirstSegmentChars(barList(barCandidates), barCandidates, remaining, initial)
        }
        val barEntries = barEntries0
        // 推薦組最多 30 詞與 10 單字；詞來自聯想/組合/整串與引擎詞，單字可用段組補位。
        val recommend = recommendWords(ordered, panelRows, local.remaining)
        // 過長編碼：候選條/字典都沒有覆蓋全碼的詞時，按「每鍵第一個給拼音聲母」造一個組詞，
        // 放在條首與推薦首位，避免 preedit/預覽裸露原碼。
        // 「盡力找」只在**候選條固定窗口**裡找：bar/full 必須用同一窗口判定，
        // 否則窗口外的完整詞會讓兩條路徑一個造詞、一個不造（bar drift）。
        val fullCodeCovered = barCandidates.any { it.inputSpan?.let { s -> CompositionInput.coversFullCode(remaining, s.end) } == true } ||
            barEntries.any { it.inputSpan?.let { s -> CompositionInput.coversFullCode(remaining, s.end) } == true } ||
            dictionaryRows.any { it.restCode.isEmpty() }
        // 僅在剩餘碼無法完整切分為合法音節時才按每鍵首拼造詞；
        // 全拼長句（如 7 音節整句）本身可切分成真實音節，不得用逐鍵假詞劫持首選與 preedit。
        val synthesized = if (mapFallbackLetters && MultiKeyProfile.active.allowLongCodeSynthesis &&
            !fullCodeCovered && remaining.length >= 2 &&
            !MultiKeyProfile.active.hasFullSyllable(remaining)
        ) {
            synthesizeLongCode(remaining, initial)
        } else {
            null
        }
        if (synthesized != null) {
            DiagLog.d("snapshot", "synth code=$remaining text=${synthesized.text} reading=${synthesized.comment}")
        }
        val barWithSynth = if (synthesized != null) listOf(synthesized) + barEntries else barEntries
        val recommendWithSynth = if (synthesized != null) listOf(synthesized) + recommend else recommend
        // 推薦組固定排左列第一（reading 為空 + [RECOMMEND_SEGMENT] 標記）；空的就不插，
        // 這樣「候選全上了條」時段組自然回到第 0 組。
        val recommendRow = if (recommendWithSynth.isEmpty()) {
            emptyList()
        } else {
            listOf(
                SyllableGroup(
                    reading = "",
                    syllables = emptyList(),
                    items = recommendWithSynth,
                    segment = RECOMMEND_SEGMENT,
                ),
            )
        }
        // 12 鍵：左列只放「確認」行（把模糊碼轉成確切音節/首字母），與右側候選徹底分離；
        // 其它方案保持原來的「讀音組 + 右格」導航。
        val groupsWithRecommend = if (mapFallbackLetters) {
            recommendRow + confirmGroups(remaining, emptyList())
        } else {
            recommendRow + panelRows.map { it.toSyllableGroup() }
        }
        // 預覽文字與候選條第 0 條同源：一次算好，隨快照發佈。
        val previewText = barWithSynth.firstOrNull { it.text.isNotEmpty() }?.text ?: ""
        // 已選前綴（librime context 裡尚未 commit 的字）+ 第一條候選 = 嵌入式預覽全文
        // App 側暫存的字（兜底逐段選字）也要算進嵌入式預覽，否則選中的字在輸入框裡看不到。
        val inlineText = runCatching { Rime.getCompositionPrefix() }.getOrDefault("") + previewText
        // 兜底按引擎和字典候選是否可用判斷，不能把面板關閉的空 groups 當成無候選。
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
        val boundGroups = groupsWithRecommend.map { group -> group.copy(items = group.items.map(inputBinder::bind)) }
        val boundBar = barWithSynth.map(inputBinder::bind)
        val boundRecommend = recommendWithSynth.map(inputBinder::bind)
        DiagLog.d(
            "snapshot",
            "build code=${input} mode=app wantGroups=$wantGroups groups=${groupsWithRecommend.size}" +
                " bar=${barEntries.size} ${panel.stats.line()}" +
                " all=[" + groupsWithRecommend.joinToString(",") { it.reading.ifEmpty { "推荐" } } + "]",
        )
        DiagLog.d("panel", panel.stats.line())
        val __tEnd = System.nanoTime()
        val __totalMs = (__tEnd - __tFlat) / 1_000_000
        if (__totalMs >= 10) {
            DiagLog.d(
                "perf",
                "build java: create=${((__tFlat - __enumStartNs) / 1_000_000) - __enumMs}ms rank=${(__tRanked - __tFlat) / 1_000_000}ms rest=${(__tEnd - __tMerge0) / 1_000_000}ms total=${__totalMs}ms n=${flat.size} code=$input",
            )
        }
        return TwelveKeySnapshot(
            input = input,
            consumedEnd = consumedEnd,
            remaining = remaining,
            groups = boundGroups,
            fallback = fallback,
            degradedToSinglePage = degraded,
            barEntries = boundBar,
            recommend = boundRecommend,
            previewText = previewText,
            inlineText = inlineText,
            contextKey = local.contextKey,
            rawPreedit = local.rawPreedit,
            initialChoices = if (mapFallbackLetters) InitialDisambiguation.choices(remaining) else emptyList(),
            initial = null,
            phoneticNavigation = mapFallbackLetters,
        )
    }

    /**
     * 已確認拼音序列：詞條走引擎（會話屬性讓簡拼分支不被全拼剪枝），
     * 首段漢字走 App 音節→字表（引擎菜單只有整串詞，沒有只覆蓋首段的單字）。
     * 左列字母行與音節行同一份 groups；字母由 [letterChoices] 生成。
     */
    private fun phoneticSnapshot(
        local: Local,
        flat: List<CandidateItem>,
        wantGroups: Boolean,
        phonetics: List<PhoneticSelection>,
        appRankingEnabled: Boolean,
        degraded: Boolean,
        maxCandidates: Int = TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT,
        hasEngineMore: Boolean = false,
    ): TwelveKeySnapshot {
        val code = local.remaining
        val binder = CandidateSelection.InputBinder(code, local.consumedEnd)
        // 引擎已按 hkrime_initial 在窗口前收窄；這裡再用「完整序列 + 實際跨度」校驗：
        // 只覆蓋前幾段的整音節單字不能冒充已確認序列。
        fun validate(source: List<CandidateItem>): List<CandidateItem> = source.map(binder::bind).filter { item ->
            val span = item.inputSpan ?: return@filter false
            PhoneticSelection.matches(span.reading, phonetics) &&
                PhoneticSelection.matchesInput(span.reading, code, span.end, phonetics)
        }
        // 候選條固定只從前 64 條引擎窗口算，full/limited 的前 12 項必須一致。
        val barWindow = if (flat.size > TwelveKeyTuning.BAR_CANDIDATES) flat.subList(0, TwelveKeyTuning.BAR_CANDIDATES) else flat
        val matched = validate(flat)
        val barMatched = if (barWindow === flat) matched else validate(barWindow)
        val words = matched.distinctBy { it.text }
        val pending = PhoneticSelection.pending(code, phonetics)
        val first = phonetics.firstOrNull()
        // 候選條與面板都需要首段漢字（引擎菜單沒有只覆蓋首段的單字）。
        val firstChars = if (appRankingEnabled && first != null) {
            firstSegmentChars(first, code).map(binder::bind)
        } else emptyList()
        val recommend = if (wantGroups) {
            val rankedWords = words.take(maxCandidates)
            val split = TwelveKeyTuning.CONFIRM_CHARS_AFTER_WORDS.coerceIn(0, rankedWords.size)
            (rankedWords.take(split) + firstChars + rankedWords.drop(split)).distinctBy { it.text }
        } else emptyList()
        val hasMore = wantGroups && !degraded &&
            hasEngineMore &&
            recommend.size >= TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT &&
            maxCandidates < TwelveKeyTuning.CONFIRM_ALL_LIMIT
        val bar = (barMatched.distinctBy { it.text } + firstChars).distinctBy { it.text }
            .take(TwelveKeyTuning.BAR_ITEMS)
        val letters = letterChoices(code, phonetics)
        val groups = if (wantGroups) {
            listOf(SyllableGroup("", emptyList(), recommend, RECOMMEND_SEGMENT)) +
                confirmGroups(code, phonetics)
        } else emptyList()
        val preview = bar.firstOrNull()?.text.orEmpty()
        DiagLog.d("snapshot", "build code=${local.input} mode=confirm wantGroups=$wantGroups" +
            " groups=${groups.size} bar=${bar.size} words=${words.size} chars=${firstChars.size}" +
            " hasMore=$hasMore phonetics=${phonetics.joinToString(" ")}")
        return TwelveKeySnapshot(
            input = local.input, consumedEnd = local.consumedEnd, remaining = code,
            groups = groups, fallback = null, degradedToSinglePage = degraded,
            barEntries = bar, recommend = recommend,
            previewText = preview,
            inlineText = runCatching { Rime.getCompositionPrefix() }.getOrDefault("") + preview,
            contextKey = local.contextKey, rawPreedit = local.rawPreedit,
            initialChoices = letters,
            initial = selectedLetter(code, phonetics),
            engineOrderOnly = !appRankingEnabled,
            phonetics = phonetics, phoneticNavigation = true,
            hasMore = hasMore,
        )
    }

    /**
     * 樂觀瞬時快照（<1ms，不碰引擎）：左列點擊確認或退格撤回時，立刻推進左列與預覽，
     * 右列先從當前內存候選 + 首段漢字過濾展示，後台引擎枚舉完成後再無縫補齊。
     */
    fun optimisticPhonetic(
        previous: TwelveKeySnapshot,
        remaining: String,
        phonetics: List<PhoneticSelection>,
        appRankingEnabled: Boolean,
    ): TwelveKeySnapshot? {
        if (remaining.isEmpty() || previous.isEmpty || previous.remaining != remaining) return null
        val binder = CandidateSelection.InputBinder(remaining, 0)
        val pool = if (previous.recommend.isNotEmpty()) previous.recommend else previous.barEntries
        val matched = if (phonetics.isEmpty()) {
            pool
        } else {
            pool.map { item ->
                // 樂觀預覽不走引擎 index，避免後台尚未重放完成時誤觸舊菜單下標。
                binder.bind(item.copy(index = -1, engineSpan = null))
            }.filter { item ->
                val span = item.inputSpan ?: return@filter false
                PhoneticSelection.matches(span.reading, phonetics) &&
                    PhoneticSelection.matchesInput(span.reading, remaining, span.end, phonetics)
            }
        }
        val words = matched.distinctBy { it.text }
        val first = phonetics.firstOrNull()
        val firstChars = if (appRankingEnabled && first != null) {
            firstSegmentChars(first, remaining).map(binder::bind)
        } else emptyList()
        val rankedWords = words.take(TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT)
        val split = TwelveKeyTuning.CONFIRM_CHARS_AFTER_WORDS.coerceIn(0, rankedWords.size)
        val recommend = (rankedWords.take(split) + firstChars + rankedWords.drop(split)).distinctBy { it.text }
        val bar = (words.take(TwelveKeyTuning.BAR_CANDIDATES) + firstChars).distinctBy { it.text }
            .take(TwelveKeyTuning.BAR_ITEMS)
        val letters = letterChoices(remaining, phonetics)
        val groups = listOf(SyllableGroup("", emptyList(), recommend, RECOMMEND_SEGMENT)) +
            confirmGroups(remaining, phonetics)
        val preview = bar.firstOrNull()?.text.orEmpty()
        return TwelveKeySnapshot(
            input = remaining,
            consumedEnd = 0,
            remaining = remaining,
            groups = groups,
            fallback = null,
            degradedToSinglePage = false,
            barEntries = bar,
            recommend = recommend,
            previewText = preview,
            inlineText = preview,
            contextKey = previous.contextKey,
            rawPreedit = previous.rawPreedit.ifEmpty { remaining },
            initialChoices = letters,
            initial = selectedLetter(remaining, phonetics),
            engineOrderOnly = !appRankingEnabled,
            phonetics = phonetics,
            phoneticNavigation = true,
        )
    }

    /**
     * 左列「確認」行：把當前待確認碼轉成確切音節或首字母。
     *
     * 完整音節 = 碼前綴命中的音節（`5a → ka/la`，`we → the`，`5we → 無`）；
     * 字母 = 下一個碼鍵的合法首拼（`5a → k/l`）。點按即確認該段並推進；
     * 待確認碼為空時，列最後一段簡拼可切換的字母（`b ↔ c`）。
     */
    internal fun confirmGroups(code: String, phonetics: List<PhoneticSelection>): List<SyllableGroup> {
        val profile = MultiKeyProfile.active
        val pending = PhoneticSelection.pending(code, phonetics)
        val rows = ArrayList<SyllableGroup>()
        if (pending.isNotEmpty()) {
            profile.syllablesCompletingPrefix(pending)
                .take(TwelveKeyTuning.MAX_CONFIRM_SYLLABLES)
                .forEach { syllable ->
                    rows.add(SyllableGroup(syllable, listOf(syllable), emptyList()))
                }
            InitialDisambiguation.choices(pending).forEach { letter ->
                rows.add(SyllableGroup(letter, emptyList(), emptyList(), INITIAL_LETTER_SEGMENT))
            }
            return rows
        }
        val last = phonetics.lastOrNull() ?: return rows
        val lastCode = last.code.trim { it == '\'' || it.isWhitespace() }
        if (lastCode.isEmpty()) return rows
        if (!last.initialOnly) {
            // 末段已確認為完整音節（含單音節如 pau / tai）：保留左列該段候選音節並高亮當前選中項，
            // 不再隱藏左列，方便直覺查看已選音節或同段切換（如 pau ↔ sau、ke ↔ le）。
            val syllables = profile.syllablesCompletingPrefix(lastCode)
                .take(TwelveKeyTuning.MAX_CONFIRM_SYLLABLES)
            val ordered = if (last.reading in syllables) syllables else listOf(last.reading) + syllables
            ordered.forEach { syllable ->
                rows.add(SyllableGroup(syllable, listOf(syllable), emptyList()))
            }
            InitialDisambiguation.choices(lastCode).forEach { letter ->
                rows.add(SyllableGroup(letter, emptyList(), emptyList(), INITIAL_LETTER_SEGMENT))
            }
            return rows
        }
        InitialDisambiguation.choices(lastCode).forEach { letter ->
            rows.add(SyllableGroup(letter, emptyList(), emptyList(), INITIAL_LETTER_SEGMENT))
        }
        return rows
    }

    /**
     * 左列字母：有待確認碼就列待確認首鍵的合法首拼；否則列最後一段（簡拼）可切換的首拼。
     * 與 [TwelveKeyPreedit.keyInitialLetters] 同口徑，不在 UI 重複判定。
     */
    internal fun letterChoices(code: String, phonetics: List<PhoneticSelection>): List<String> {
        val pending = PhoneticSelection.pending(code, phonetics)
        if (pending.isNotEmpty()) return InitialDisambiguation.choices(pending)
        val last = phonetics.lastOrNull() ?: return InitialDisambiguation.choices(code)
        if (!last.initialOnly) return emptyList()
        return InitialDisambiguation.choices(last.code)
    }

    /** 選中態：只有「原碼已到尾、最後一段是簡拼」時才高亮字母（可切換）。 */
    internal fun selectedLetter(code: String, phonetics: List<PhoneticSelection>): String? {
        val last = phonetics.lastOrNull() ?: return null
        if (!last.initialOnly) return null
        if (PhoneticSelection.pending(code, phonetics).isNotEmpty()) return null
        return last.reading
    }

    /** 首段漢字：簡拼段取該首字母下全部音節的字；完整音節取該音節的字。 */
    private fun firstSegmentChars(first: PhoneticSelection, code: String): List<CandidateItem> {
        val profile = MultiKeyProfile.active
        val unit = profile.initialUnit(first.reading)
        val syllables = if (first.initialOnly) {
            profile.syllablesByInitials(listOf(unit))
        } else {
            listOf(first.reading)
        }
        if (syllables.isEmpty()) return emptyList()
        if (!code.startsWith(first.code)) return emptyList()
        val rest = code.substring(first.code.length)
        val seen = HashSet<String>()
        val chars = ArrayList<Triple<String, String, Int>>()
        for (syllable in syllables) {
            val nativeChars = runCatching {
                Rime.query_syllable_chars(syllable, TwelveKeyTuning.SEGMENT_CHARS_LIMIT)?.toList()
            }.getOrNull().orEmpty()
            val list = if (nativeChars.isNotEmpty()) {
                nativeChars
            } else {
                TwelveKeyPreedit.charsForSyllable(syllable, TwelveKeyTuning.SEGMENT_CHARS_LIMIT)
            }
            for ((rankIdx, ch) in list.withIndex()) {
                if (!seen.add(ch)) continue
                val baseScore = if (nativeChars.isNotEmpty()) {
                    100_000 - rankIdx
                } else {
                    TwelveKeyPreedit.generalScore(ch)
                }
                // 首拼確認態（如點 b）：若詞典裡有對應的單字母候選（b/B），確保直接露出在前排不被 120 上限截掉。
                val score = if (first.initialOnly && unit.length == 1 &&
                    ch.length == 1 && ch[0].lowercaseChar() == unit[0]
                ) {
                    maxOf(baseScore, 100_000)
                } else {
                    baseScore
                }
                chars.add(Triple(syllable, ch, score))
            }
        }
        return chars.sortedWith(
            compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.second },
        ).take(TwelveKeyTuning.INITIAL_CHARS_LIMIT).map { (syllable, ch, _) ->
            CandidateItem(
                text = ch,
                comment = syllable,
                index = -1,
                segment = INITIAL_CHAR_SEGMENT,
                restCode = rest,
            )
        }
    }

    private fun engineOnlySnapshot(
        local: Local,
        flat: List<CandidateItem>,
        wantGroups: Boolean,
        mapFallbackLetters: Boolean,
        initial: String?,
        degraded: Boolean,
    ): TwelveKeySnapshot {
        val binder = CandidateSelection.InputBinder(local.remaining, local.consumedEnd)
        val candidates = flat.map(binder::bind)
        val bar = candidates.take(TwelveKeyTuning.BAR_ITEMS)
        val groups = if (wantGroups && candidates.isNotEmpty()) {
            listOf(SyllableGroup("", emptyList(), candidates, RECOMMEND_SEGMENT)) +
                CandidateRanking.groupCandidates(candidates, maxGroups = MAX_GROUPS - 1)
        } else emptyList()
        val preview = bar.firstOrNull()?.text.orEmpty()
        DiagLog.d("snapshot", "build code=${local.input} mode=engine wantGroups=$wantGroups" +
            " groups=${groups.size} bar=${bar.size} engine=${candidates.size}" +
            " indices=${bar.joinToString(",") { it.index.toString() }}")
        return TwelveKeySnapshot(
            input = local.input,
            consumedEnd = local.consumedEnd,
            remaining = local.remaining,
            groups = groups,
            fallback = null,
            degradedToSinglePage = degraded,
            barEntries = bar,
            recommend = if (wantGroups) candidates else emptyList(),
            previewText = preview,
            inlineText = runCatching { Rime.getCompositionPrefix() }.getOrDefault("") + preview,
            contextKey = local.contextKey,
            rawPreedit = local.rawPreedit,
            initialChoices = if (mapFallbackLetters) InitialDisambiguation.choices(local.remaining) else emptyList(),
            initial = null,
            engineOrderOnly = true,
            phoneticNavigation = mapFallbackLetters,
        )
    }

    /**
     * 判斷引擎候選數是否為零；面板關閉時 groups 為空不能用作此判據。
     */
    internal fun shouldShowFallback(engineCandidateCount: Int, remaining: String): Boolean =
        engineCandidateCount == 0 && remaining.isNotEmpty()

    /**
     * 對待選碼枚舉完整首音節切法；無完整切法時回退首碼。
     * 按 Tuning 限制每首字母與總音節數，返回第一待選段的單字組來源。
     */
    // 五源及引擎源只經 mergeRows、collapsePrefixRows、tierAndSort、capRows。
    // 源內只構造與選行，不另起跨源合併和最終排序。

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
        /**
         * 完整段碼僅取 exact 音節，避免展開更長前綴音節。
         */
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
     *     選字後統一選擇入口按原碼跨度消費，未選尾碼重新餵回引擎。
     *
     * 配額：第一種切法吃完整配額；備選切法減半（[TwelveKeyTuning.SECONDARY_SPLIT_DIVISOR]），
     * 避免次要解讀把左列灌滿。音節豐富的鍵放開到 24（[TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT_RICH]）。
     */
    internal fun splitParts(codes: String, initial: String? = null): List<SplitPart> {
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
            // r 為後綴音節，僅在 rest 為空時允許。
            val allowSuffixOnly = rest.isEmpty()
            val syllables = TwelveKeyPreedit
                .syllablesForSegment(head, quota, headIsCompleteSyllable, allowSuffixOnly = allowSuffixOnly, initial = initial)
                .ifEmpty {
                    TwelveKeyPreedit.syllablesForSegment(
                        head, quota, false, allowSuffixOnly = allowSuffixOnly, initial = initial,
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
     * 源 3：按兩個具體音節精確查聯想詞；音節候選依每段第一鍵首字母產生。
     * 已完成的第二音節採精確候選；按兩音節首字母分桶輪轉，預算 ASSOC_BUCKET_ROW_BUDGET。
     * 桶內學習詞優先，再按詞數與讀音排序，行內最多 ASSOC_WORDS。
     */
    /**
     * 這一段「一個音節」的首字母集合：只看該段**第一個碼位**的首字母（元音不釘死）。
     *
     * `ee` → {d,f}、`5` → {k,l}；`r` 只在該段只有一個碼位且是輸入末鍵時才出現。
     * 與 `panel_probe.py` 的 `key_initial_letters(part[0], is_last=...)` 同口徑。
     */
    private fun partInitials(code: String, isLastKey: Boolean): List<String> {
        val first = code.firstOrNull() ?: return emptyList()
        return TwelveKeyPreedit.keyInitialUnits(first, isLast = isLastKey && code.length == 1)
    }

    internal fun assocRows(parts: List<SplitPart>, initial: String? = null): List<PanelRow> {
        val buckets = LinkedHashMap<Pair<String, String>, MutableList<PanelRow>>()
        parts.forEachIndexed { index, part ->
            val isLastPart = index == parts.lastIndex
            // 有剩餘碼時按每段第一鍵首字母匹配，元音自由。
            // 剩餘碼開頭有完整音節時只取完成的第二音節，第一段完整則取 exact。
            // 沒有剩餘碼時按首段前綴展開其後續音節。
            val completedSeconds = if (part.rest.isEmpty()) {
                emptyList()
            } else {
                TwelveKeyPreedit.syllablesCompletingPrefix(part.rest)
            }
            val firstCandidates = when {
                part.rest.isEmpty() -> TwelveKeyPreedit.syllablesForSegment(
                    part.head,
                    ASSOC_EXPAND_LIMIT,
                    exactOnly = false,
                    totalLimit = ASSOC_EXPAND_LIMIT,
                    allowSuffixOnly = part.allowSuffixOnly,
                    initial = initial,
                )
                completedSeconds.isNotEmpty() && part.headIsCompleteSyllable ->
                    TwelveKeyPreedit.syllablesForSegment(
                        part.head,
                        ASSOC_EXPAND_LIMIT,
                        exactOnly = true,
                        totalLimit = ASSOC_EXPAND_LIMIT,
                        allowSuffixOnly = part.allowSuffixOnly,
                        initial = initial,
                    )
                else -> TwelveKeyPreedit.syllablesByInitials(partInitials(part.head, isLastKey = false))
            }
            // 第二段：有剩餘碼才展開（`r` 這類後綴音節在這裡是合法結尾，末位鍵要保留 r）。
            val secondCandidates = when {
                part.rest.isEmpty() -> null
                completedSeconds.isNotEmpty() -> completedSeconds
                else -> TwelveKeyPreedit.syllablesByInitials(partInitials(part.rest, isLastKey = isLastPart))
            }
            for (first in firstCandidates) {
                if (initial != null && TwelveKeyPreedit.initialUnit(first) != initial) continue
                val bySecond = TwelveKeyPreedit.assocSecondMap(first)
                if (bySecond.isEmpty()) continue
                // 第二段無可展開音節時回退第一音節的全部後續，與腳本保持一致。
                val allowed = secondCandidates
                    ?.takeIf { it.isNotEmpty() }
                    ?.filter { bySecond.containsKey(it) }
                    ?: bySecond.keys.toList()
                for (second in allowed) {
                    if (second.isEmpty()) continue
                    val words = bySecond[second]?.take(ASSOC_WORDS) ?: continue
                    if (words.isEmpty()) continue
                    buckets.getOrPut(
                        TwelveKeyPreedit.initialUnit(first) to TwelveKeyPreedit.initialUnit(second),
                    ) { ArrayList() }.add(assocRow("$first $second", words))
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
        buckets: Map<Pair<String, String>, List<PanelRow>>,
        maxRows: Int,
    ): List<PanelRow> {
        if (buckets.isEmpty() || maxRows <= 0) return emptyList()
        val sortedKeys = buckets.keys.sortedWith(compareBy({ it.first }, { it.second }))
        val groups = LinkedHashMap<String, MutableList<List<PanelRow>>>()
        sortedKeys.forEach { key ->
            groups.getOrPut(key.first) { ArrayList() }
                // 桶內排序必須**確定**：詞數降序、同詞數按 reading。兩份實現（App/腳本）
                // 都按這個 key 排，否則桶內插入順序不同時會選中不同行（golden 會漂）。
                .add(
                    buckets.getValue(key).sortedWith(
                        compareByDescending<PanelRow> { it.hasUserWord }
                            .thenByDescending { it.items.size }
                            .thenBy { it.reading }
                    )
                )
        }
        val flat = LinkedHashMap<String, MutableList<PanelRow>>()
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
        val cursors = HashMap<String, Int>()
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
        // 含 PhraseMemory 學習詞的行在桶內優先選取，最終顯示仍按讀音排序。
        hasUserWord = words.any { TwelveKeyPreedit.isLearnedWord(it) },
    )

    /**
     * 源 4：首字母序列查真實詞行，包含純簡拼與簡拼+完整末音節。
     * 按組合兩輪選行，總組合數和行數受 Tuning 限制，對應腳本 initial_pattern_rows。
     */
    internal fun initialComboRows(codes: String, initial: String? = null): List<PanelRow> {
        if (codes.length < 2) return emptyList()
        // 純簡拼與簡拼+完整末音節共同參與選行；完整音節由集合判定。
        val combos = TwelveKeyPreedit.initialPatterns(codes, initial)
        if (combos.isEmpty()) return emptyList()
        val rows = ArrayList<PanelRow>(INITIAL_COMBO_MAX_ROWS)
        val seen = HashSet<String>()
        // 兩輪：第一輪每個組合先出第 1 行（保證「g·b / g·c / h·b / h·c」都有代表），
        // 第二輪再給每個組合補第 2 行。節奏由 INITIAL_COMBO_ROWS_PER_PATTERN 控制。
        // 每個組合內若某讀音含學習詞，優先出這一行：否則資產詞表的長尾讀音
        // （如 `b|cam` 的 `bau cam`）會把學習詞讀音 `bi cam` 擠出組合行上限。
        val entriesByCombo = combos.map { pattern ->
            TwelveKeyPreedit.wordsByPattern(pattern).sortedByDescending { hit ->
                hit.second.any { TwelveKeyPreedit.isLearnedWord(it) }
            }
        }
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
     * 源 5：用輕量整串碼索引查多音節詞，第一音節必須打全。
     * 不對無讀音大詞表重新建重索引或全量排序。
     */
    internal fun wholeWordRows(remaining: String, initial: String? = null): List<PanelRow> {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) return emptyList()
        val rows = runCatching {
            TwelveKeyPreedit.wordsByCodePrefix(
                codes,
                TwelveKeyTuning.ASSOC_PREFIX_READINGS,
                ASSOC_WORDS,
                initial = initial,
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
     * 源 6（App 側）：孤單聯想字母行（`d`/`f`）。
     *
     * 只在剩餘碼 >=2 鍵且整串不是完整音節時生成；reading 是首鍵字母，items 是
     * 該字母下「繼續按鍵就能得到」的單音節名。點擊字母行由面板做二級鑽取（音節 → 字）。
     */
    internal fun isolatedRows(codes: String): List<PanelRow> {
        val groups = runCatching { TwelveKeyPreedit.isolatedSyllables(codes) }.getOrElse { emptyList() }
        if (groups.isEmpty()) return emptyList()
        return groups.map { (letter, syllables) ->
            PanelRow(
                reading = letter,
                items = syllables.map { syllable ->
                    CandidateItem(
                        text = syllable,
                        comment = "",
                        index = -1,
                        segment = ISOLATED_SEGMENT,
                        restCode = codes.replace("'", ""),
                    )
                },
                segment = ISOLATED_SEGMENT,
                restCode = codes.replace("'", ""),
                sources = setOf(RowSource.ISOLATED),
            )
        }
    }

    /**
     * 引擎讀音組轉為 PanelRow；有段組時保留同讀音單音節組並入，
     * 另保留多音節與含非漢字的組。多音節以 reading token 數判斷。
     */
    internal fun engineRows(engineGroups: List<SyllableGroup>, segmentReadings: Set<String>): List<PanelRow> {
        if (engineGroups.isEmpty()) return emptyList()
        // 沒有任何段組時（單鍵/純碼位、段組還沒展開）引擎組原樣全留 —— 這是重構前的
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
                // 同讀音引擎組會並進段組：漢字在前、非漢字補在後面（重構前的分區口徑）。
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
            } else if (group.reading.split(' ').count { part -> part.isNotBlank() } >= 2 ||
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
        val isolated: Int = 0,
        val merged: Int = 0,
        val collapsed: Int = 0,
        val capped: Int = 0,
        val tierSingles: Int = 0,
        val tierWords: Int = 0,
        val tierBlank: Int = 0,
    ) {
        /**
         * 記錄各源、合併、收斂和截斷數量，供定位候選行。
         */
        fun line(): String = "panel sources synth=$synth split=$split assoc=$assoc combo=$combo" +
            " word=$word engine=$engine isolated=$isolated → merged=$merged collapsed=$collapsed" +
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
        initial: String? = null,
    ): PanelBuild = computePanel(remaining, anchor, engineGroups, includePanelOnlySources, initial).build

    /**
     * 單測/診斷用：把五源與合併後的行都攤開（golden 對拍腳本 `panel_probe.py json`）。
     *
     * 僅供 `internal` 調用；UI 永遠只看到 [buildPanelRows] 投影出的 `SyllableGroup`。
     */
    internal fun debugPanelSources(codes: String, initial: String? = null): PanelSources =
        computePanel(codes, null, emptyList(), includePanelOnlySources = true, initial = initial).let { c ->
            PanelSources(
                isolated = if (initial == null) isolatedRows(codes) else emptyList(),
                synth = c.synth,
                split = c.split,
                assoc = c.assoc,
                combo = c.combo,
                wholeWord = c.word,
                merged = c.build.rows,
            )
        }

    /** 六個 App 來源與合併後的最終行（測試診斷用）。 */
    internal data class PanelSources(
        val isolated: List<PanelRow>,
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
        initial: String? = null,
    ): PanelComputation {
        val codes = remaining.replace("'", "")
        if (codes.isEmpty()) {
            return PanelComputation(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                PanelBuild(emptyList(), PanelStats.EMPTY),
            )
        }
        val parts = splitParts(codes, initial)
        val synth = synthSingleRows(parts, anchor)
        val split = splitSingleRows(parts, anchor)
        val segmentReadings = HashSet<String>(synth.size + split.size)
        synth.forEach { segmentReadings.add(it.reading) }
        split.forEach { segmentReadings.add(it.reading) }
        val engine = engineRows(engineGroups, segmentReadings)
        val assoc = assocRows(parts, initial)
        val combo = if (includePanelOnlySources) initialComboRows(codes, initial) else emptyList()
        val word = if (includePanelOnlySources) wholeWordRows(remaining, initial) else emptyList()
        val isolated = if (includePanelOnlySources && initial == null) isolatedRows(codes) else emptyList()

        // 拼接順序固定：孤單聯想 → 段組 → 引擎 → 聯想 → 首字母組合 → 整串詞行。
        // mergeRows 的「引擎項在前、App 補集在後」「單字組(0) 優先於詞行(1)」都由這個順序決定；
        // 孤單聯想行由 tierAndSort 固定在單字組之前。
        val merged = mergeRows(isolated + synth + split + engine + assoc + combo + word)
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
                    isolated = isolated.size,
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
            charScore = { 0 },
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
    private const val ASSOC_MAX_ROWS = TwelveKeyTuning.ASSOC_MAX_ROWS
    private const val ASSOC_BUCKET_ROW_BUDGET = TwelveKeyTuning.ASSOC_BUCKET_ROW_BUDGET
    private const val ASSOC_EXPAND_LIMIT = TwelveKeyTuning.ASSOC_EXPAND_LIMIT
    private const val ASSOC_PREFIX_READINGS = TwelveKeyTuning.ASSOC_PREFIX_READINGS
    private const val INITIAL_COMBO_MAX_ROWS = TwelveKeyTuning.INITIAL_COMBO_MAX_ROWS
    private const val INITIAL_COMBO_ROWS_PER_PATTERN = TwelveKeyTuning.INITIAL_COMBO_ROWS_PER_PATTERN

    /**
     * 同步完成 local 與 build；調用方負責執行線程和引擎鎖。
     */
    fun build(): TwelveKeySnapshot = build(local())

    /**
     * 已選/已確認（status>=2）段的最大 end，使用 raw input 的 UTF-8 字節偏移。
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

    /** 多鍵碼 → 代表字母（只用於本地兜底上屏，不影響引擎）。 */
    fun toLetters(rawCode: String): String = buildString(rawCode.length + 8) {
        val profile = MultiKeyProfile.active
        rawCode.forEach { code ->
            append(profile.fallbackLetter(code))
        }
    }

    /**
     * rime-ice 這類方案的 `translator/comment_format` 會把拼音包成 `［…］`
     * （`xform/^/［/` + `xform/$/］/`），面板左列的分組鍵與候選下面那行註釋都不該帶全角括號，
     * 這裡統一把包裝剝掉；其他形式的 comment 原樣保留。
     */
    internal fun cleanCommentForSelection(comment: String?): String = cleanComment(comment)

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
        return CandidateRanking.rankPanelItems(
            items = items,
            charScore = { 0 },
            generalScore = { TwelveKeyPreedit.generalScore(it) },
        )
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
    /**
     * 過長編碼無完整候選時的「創造組詞」：每個碼鍵取**第一個首拼單元**（kl→k、th/ng/nz 整段），
     * 每個單元取該單元下最常用的漢字，拼成一個合成候選（reading=單元串，text=字串）。
     *
     * 只作為「找不到覆蓋全碼的詞」的兜底；有完整詞時絕不插入。
     */
    internal fun synthesizeLongCode(code: String, initial: String? = null): CandidateItem? {
        if (code.isEmpty()) return null
        val units = ArrayList<String>(code.length)
        for (ch in code) {
            if (ch == '\'' || ch.isWhitespace()) continue
            // 末鍵也不取後綴 r：組詞取的是聲母，r 只作兒化尾。
            val unit = TwelveKeyPreedit.keyInitialUnits(ch, isLast = false).firstOrNull() ?: return null
            units.add(unit)
        }
        if (units.isEmpty()) return null
        if (initial != null && units.firstOrNull() != initial) return null
        val chars = ArrayList<String>(units.size)
        for (unit in units) {
            val best = TwelveKeyPreedit.syllablesByInitials(listOf(unit)).asSequence()
                .flatMap { TwelveKeyPreedit.charsForSyllable(it, 2).asSequence() }
                .maxByOrNull { TwelveKeyPreedit.generalScore(it) } ?: return null
            chars.add(best)
        }
        val text = chars.joinToString("")
        if (text.isEmpty()) return null
        return CandidateItem(
            text = text,
            comment = units.joinToString(" "),
            index = -1,
            segment = SYNTHETIC_SEGMENT,
            restCode = code,
        )
    }

    private fun barList(ranked: List<CandidateItem>): List<CandidateItem> =
        CandidateRanking.rankBar(ranked, BAR_ITEMS)

    /**
     * 候選條為「第一待選段」的單字提供末尾補位。
     *
     * 多音節碼且條內缺單字時，只在末尾補位，保留引擎/學習混排首選及前列順序。
     * 單字可供逐段選字，但不能改變首候選與 preedit 的讀音來源。
     * 單音節碼或條內已有單字時不動。
     */
    private fun withFirstSegmentChars(
        bar: List<CandidateItem>,
        ranked: List<CandidateItem>,
        remaining: String,
        initial: String? = null,
    ): List<CandidateItem> {
        if (remaining.isEmpty() || bar.isEmpty() || bar.any { it.text.length == 1 }) return bar
        val head = splitParts(remaining, initial).firstOrNull() ?: return bar
        if (head.rest.isEmpty()) return bar
        val syllables = head.syllables.take(FIRST_SEGMENT_CHARS).toSet()
        if (syllables.isEmpty()) return bar
        val chars = ranked.asSequence()
            .filter { it.text.length == 1 }
            .filter { it.comment.trim().substringBefore(' ') in syllables }
            .distinctBy { it.text }
            .take(FIRST_SEGMENT_CHARS)
            .toList()
        if (chars.isEmpty()) return bar
        return bar.take((TwelveKeyTuning.BAR_ITEMS - chars.size).coerceAtLeast(1)) + chars
    }


    /**
     * 推薦候選沿引擎/學習基礎順序取詞，結合面板詞行和段組單字補位。
     */
    internal fun recommendWords(
        engineCandidates: List<CandidateItem>,
        panelRows: List<PanelRow>,
        code: String = "",
    ): List<CandidateItem> {
        // 推薦組配額為 30 詞+10 單字，詞在前、單字在後。
        // 詞再按字數分三档：2 字 / 3 字 / 4 字及以上，各 10 個；某档不夠由其餘档按順序補滿。
        val seen = HashSet<String>()
        val wordCandidates = ArrayList<CandidateItem>(TwelveKeyTuning.RECOMMEND_WORDS * 2)
        val chars = ArrayList<CandidateItem>(TwelveKeyTuning.RECOMMEND_CHARS)

        fun addWord(item: CandidateItem) {
            if (item.text.isEmpty() || !seen.add(item.text)) return
            wordCandidates.add(item)
        }

        fun addChar(item: CandidateItem) {
            if (item.text.length != 1 || !seen.add(item.text)) return
            if (chars.size < TwelveKeyTuning.RECOMMEND_CHARS) chars.add(item)
        }

        // 學習詞（面板 hasUserWord 行）優先：用戶點過的字詞不能被字數配額擠掉。
        panelRows.forEach { row ->
            if (row.segment != 1 || !row.hasUserWord) return@forEach
            row.items.forEach { item ->
                if (CandidateRanking.isWordLike(item.text)) addWord(item)
            }
        }
        CandidateRanking.fullBarOrder(engineCandidates).forEach { item ->
            when {
                // 純漢字詞、混合詞（`b站`），以及詞典自帶讀音的英文詞（`GPT`/`gemini`/`deepseek`/`Linux`）都進推薦。
                CandidateRanking.isWordLike(item.text) ||
                    (item.text.length >= 2 && item.comment.isNotBlank() && item.text.any { it.isLetter() }) -> addWord(item)
                CandidateRanking.isSingleCjkChar(item.text) -> addChar(item)
            }
        }
        // 面板詞行：先排「完整碼前綴命中」的詞，再排其餘（見下方 comment）。
        val matched = ArrayList<CandidateItem>()
        val others = ArrayList<CandidateItem>()
        panelRows.forEach { row ->
            if (row.segment != 1) return@forEach
            row.items.forEach { item ->
                if (item.text.isEmpty()) return@forEach
                val wordCode = TwelveKeyPreedit.derivedWordCode(item.text)
                val hit = wordCode != null && code.isNotEmpty() &&
                    (wordCode.startsWith(code) || code.startsWith(wordCode))
                if (hit) matched.add(item) else others.add(item)
            }
        }
        matched.forEach(::addWord)
        others.forEach(::addWord)
        // 按字數分档選詞：2 字 / 3 字 / 4+ 字各取配額，保序；不足時用剩餘候選補滿總數。
        val quotas = intArrayOf(
            TwelveKeyTuning.RECOMMEND_WORDS_TWO,
            TwelveKeyTuning.RECOMMEND_WORDS_THREE,
            TwelveKeyTuning.RECOMMEND_WORDS_FOUR_PLUS,
        )
        val picked = IntArray(3)
        val words = ArrayList<CandidateItem>(TwelveKeyTuning.RECOMMEND_WORDS)
        val overflow = ArrayList<CandidateItem>(wordCandidates.size)
        wordCandidates.forEach { item ->
            val bucket = when (item.text.length) {
                2 -> 0
                3 -> 1
                else -> if (item.text.length >= 4) 2 else 0
            }
            if (picked[bucket] < quotas[bucket]) {
                picked[bucket]++
                words.add(item)
            } else {
                overflow.add(item)
            }
        }
        var extra = 0
        while (words.size < TwelveKeyTuning.RECOMMEND_WORDS && extra < overflow.size) {
            words.add(overflow[extra++])
        }
        // 單字補位：面板段組（segment==0）的字，按面板行序。
        panelRows.forEach { row ->
            if (row.segment != 0) return@forEach
            row.items.forEach { item -> addChar(item) }
        }
        return words + chars
    }
}
