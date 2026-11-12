package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 12 鍵輸入的無 View 控制器：按鍵/上屏後重建快照，選候選時走引擎。
 *
 * 線程模型：
 * - UI 線程只做「本地部分」（input / segments / remaining，O(段數)）+ 回調 bind；
 * - 全量枚舉（≤600 條）在 [Dispatchers.Default] 上做，期間 UI 保留上一幀快照，不閃空；
 * - 所有 librime 訪問都串行化在 [ENGINE_LOCK] 上（librime session 非線程安全），
 *   鍵盤事件入口同樣持該鎖，避免「後台枚舉」與「UI 線程 process_key」並發；
 * - [generation] 防止舊的枚舉結果覆蓋新輸入。
 *
 * 所有引擎寫操作只有兩處：
 * - [selectCandidate] → Rime.select_candidate(全局下標)，引擎自己消費若干音節；
 * - [commitFallback] → clearComposition + 本地原文上屏（不學習、不入用戶詞典）。
 */
class TwelveKeyController(
    private val scope: CoroutineScope,
    private val onSnapshot: (TwelveKeySnapshot) -> Unit,
    /** 引擎狀態已變：提交 commit buffer + 刷新 preedit（由 TextInputManager 實現）。 */
    private val onEngineChanged: () -> Unit,
    /** 本地原文上屏（fallback 用）。 */
    private val onCommitText: (String) -> Unit,
) {

    var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
        private set

    /**
     * 「按段選字」剛上屏的前一個字：分詞輸入時，下一段候選條要按與它的關聯度排序
     * （例如打完「區」，下一段「域」排最前）。由 TextInputManager 在選字後設置/清空。
     */
    var segmentAnchor: Char? = null

    /**
     * 用戶是否按過「分詞」鍵（本輪組字期間 sticky）。
     *
     * 分詞後剩餘段會被重新餵回引擎，餵回去的碼串已經沒有分隔符了；
     * 但候選條仍應繼續按段列字，所以不能只看 remaining 裡有沒有分隔符，
     * 要由 TextInputManager 記住「這一輪是分段輸入」。
     */
    var segmentMode: Boolean = false

    /**
     * 方案開關（簡/繁、情/聯、全/半…）的版本號。
     *
     * 開關一變，引擎的候選就被 filter 改寫（顏文字/聯想詞），但快照緩存鍵只看 input 與分段，
     * 會把舊候選直接吐回來 → 用戶看到「開關按了沒反應」。所以把版本號也塞進 cacheKey。
     */
    private var optionsVersion = 0

    /** 方案開關變化：讓候選快照緩存失效。 */
    fun invalidateForOptions() {
        optionsVersion++
    }

    /**
     * 引擎狀態變化（選字/上屏/重新初始化）後調用：讓候選快照緩存失效。
     *
     * 用戶 2026-11-11 報「打 `ee5` 候選條先顯示兜底 `deek`，開關面板後才變 `邸`」：
     * 首幀枚舉時引擎還沒給出候選（或剛被 destroy/重建），兜底快照被**寫進緩存**，
     * 之後同一輸入直接命中緩存 → 候選條/preedit 一直停在兜底，直到面板走了另一條
     * `full` 構建路徑才刷新。
     */
    fun invalidateForEngine() {
        engineVersion++
    }

    private var engineVersion = 0

    /** 最近 8 條快照（按 input+segments），退格/重打直接命中，不再枚舉。線程安全。 */
    private val snapshots = object :
        LinkedHashMap<String, TwelveKeySnapshot>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TwelveKeySnapshot>?,
        ): Boolean = size > CACHE_CAPACITY
    }
    private inline fun <T> cacheLookup(key: String, block: (TwelveKeySnapshot?) -> T): T =
        synchronized(snapshots) { block(snapshots[key]) }
    private fun cachePut(key: String, value: TwelveKeySnapshot) {
        synchronized(snapshots) { snapshots[key] = value }
    }

    private var generation = 0
    private var refreshJob: Job? = null

    /**
     * 異步刷新：連打時取消上一次（debounce），停手後在 Default 線程枚舉；
     * UI 線程完全不碰引擎，枚舉期間保留上一幀。
     *
     * [immediate] 用於「點候選/開面板」這類需要盡快更新的場景（仍不阻塞 UI 線程）。
     */
    fun refresh(immediate: Boolean = false, full: Boolean = false) {
        val gen = ++generation
        refreshJob?.cancel()
        // 直接派到 Default：避免「先排到 Main 佇列、再 withContext(Default)」多一次主線程來回，
        // 按鍵到候選條落地可以少 1 幀左右（實測 30ms 級）。
        refreshJob = scope.launch(Dispatchers.Default) {
            if (!immediate) delay(DEBOUNCE_MS)
            val result = run {
                synchronized(ENGINE_LOCK) {
                    runCatching {
                        val local = TwelveKeySnapshotBuilder.local()
                        if (local.isEmpty) {
                            Triple(local, TwelveKeySnapshot.EMPTY, false)
                        } else {
                            val key = TwelveKeySnapshotBuilder.cacheKey(
                                local,
                                full,
                                segmentAnchor,
                                segmentMode,
                            ) + "|o" + optionsVersion + "|e" + engineVersion +
                                "|a" + TwelveKeyPreedit.assocGeneration
                            val cached = cacheLookup(key) { it }
                            val t0 = System.nanoTime()
                            val built = cached ?: TwelveKeySnapshotBuilder.build(
                                local,
                                if (full) TwelveKeySnapshotBuilder.MAX_CANDIDATES else BAR_CANDIDATES,
                                segmentAnchor = segmentAnchor,
                                segmented = segmentMode,
                                wantGroups = full,
                            )
                            // 性能排查用：INFO 级别，真機 logcat 可直接統計每鍵快照耗時
                            Timber.i(
                                "twelvekey:build %s cached=%s ms=%.1f",
                                key,
                                cached != null,
                                (System.nanoTime() - t0) / 1e6,
                            )
                            // 只缓存「真的有候選」的快照：兜底/空快照一旦進緩存，
                            // 同一輸入之後永遠命中它（見 invalidateForEngine 註釋）。
                            if (cached == null && built.hasCandidates) cachePut(key, built)
                            Triple(local, built, true)
                        }
                    }.getOrNull()
                }
            } ?: return@launch
            if (gen != generation) return@launch
            val (local, built, _) = result
            withContext(Dispatchers.Main.immediate) {
                if (gen != generation) return@withContext
                val t = System.nanoTime()
                if (local.isEmpty) emit(TwelveKeySnapshot.EMPTY) else emit(built)
                val emitMs = (System.nanoTime() - t) / 1e6
                if (emitMs > 20.0) Timber.w("twelvekey:emit %.1fms", emitMs)
            }
        }
    }

    /**
     * 同步刷新：僅用於用戶主動開面板/開關（需要立刻判斷有無候選），
     * 或調試；普通按鍵請走 [refresh]。
     */
    fun refreshSync(): TwelveKeySnapshot {
        generation++
        refreshJob?.cancel()
        var key: String? = null
        val built = synchronized(ENGINE_LOCK) {
            runCatching {
                val local = TwelveKeySnapshotBuilder.local()
                if (local.isEmpty) {
                    TwelveKeySnapshot.EMPTY
                } else {
                    val k = TwelveKeySnapshotBuilder.cacheKey(
                        local,
                        true,
                        segmentAnchor,
                        segmentMode,
                    ) + "|o" + optionsVersion
                    key = k
                    cacheLookup(k) { it } ?: TwelveKeySnapshotBuilder.build(
                        local,
                        segmentAnchor = segmentAnchor,
                        segmented = segmentMode,
                    )
                }
            }.getOrNull()
        } ?: TwelveKeySnapshot.EMPTY
        if (!built.isEmpty) key?.let { cachePut(it, built) }
        emit(built)
        return built
    }

    /**
     * 引擎原生選中。
     *
     * [reading] 是候選的讀音（候選條/面板的 comment）。librime 對「候選 end == 輸入長度」的條目
     * 會直接 commit 整串（`express_editor` 的 `_auto_commit`）：實測 `5i4ii8i9934i54iei9`
     * 選 `灵机一动` 時 input 被整串吃光，`来的` 丟失。這裡在 select 之前按讀音音節數算出
     * 剩餘碼，若選完組合真的沒了，再把剩餘碼餵回引擎（用戶 2026-11-12：灵机一动来的 只上屏前四個字）。
     */
    fun selectCandidate(globalIndex: Int, reading: String = "") {
        if (globalIndex < 0) return
        // 先把選中的詞記到本地記憶（候選條/面板下次把同詞排前）——必須在 select 之前拿快照。
        runCatching {
            val snap = snapshot
            val item = snap.barEntries.firstOrNull { it.index == globalIndex }
                ?: snap.groups.asSequence().flatMap { it.items.asSequence() }
                    .firstOrNull { it.index == globalIndex }
            if (item != null) SelectionMemory.bump(item.text, item.comment)
        }
        val inputBefore = runCatching { Rime.RimeGetInput() }.getOrNull().orEmpty()
        val remaining = TwelveKeyPreedit.remainingCodeAfter(reading, inputBefore)
        val ok = runCatching { Rime.select_candidate(globalIndex) }.getOrDefault(false)
        if (!ok) {
            Timber.w("twelvekey: select_candidate(%d) failed", globalIndex)
            return
        }
        runCatching { Rime.getContexts() }
        // commitRimeText → updateComposing → onCandidatesRefreshed → refresh()，只重建一次快照
        onEngineChanged()
        com.hazukieq.hkrime.util.DiagLog.d(
            "select",
            "idx=$globalIndex reading='$reading' input=$inputBefore remaining=$remaining composing=${Rime.isComposing()}",
        )
        // 注意：這裡**不做**「commit 後把剩餘碼餵回」——那會讓詞先落進編輯框，
        // 用戶 2026-11-12 明確要求：剩餘碼沒選完時只能停在 preedit，不能上屏。
        // 部分上屏由 TextInputManager.holdPartialPick() 在點擊入口攔掉（不調 select_candidate）。
    }

    /** 10 鍵碼無候選時的本地兜底：清組合，直接把字母原文上屏。 */
    fun commitFallback(fallback: Fallback) {
        runCatching { Rime.clearComposition() }
        generation++
        refreshJob?.cancel()
        emit(TwelveKeySnapshot.EMPTY)
        onCommitText(fallback.letters)
    }

    fun reset() {
        generation++
        refreshJob?.cancel()
        emit(TwelveKeySnapshot.EMPTY)
    }

    private fun emit(next: TwelveKeySnapshot) {
        snapshot = next
        onSnapshot(next)
        if (next.hasCandidates) fallbackRetryCount = 0
        // 兜底快照（引擎暫時拿不到候選）不緩存了，但也不會自己重試：
        // 這裡排一次延遲刷新，等引擎就緒/候選落地後把候選條與 preedit 一起換成真實內容。
        if (!next.isEmpty && !next.hasCandidates) scheduleFallbackRetry()
    }

    private var fallbackRetryJob: Job? = null
    private var fallbackRetryCount = 0
    /** 兜底快照後最多重試幾次：無限重試會一直搶 ENGINE_LOCK（用戶 2026-11-11：開面板變卡、點好幾次才開）。 */
    private fun scheduleFallbackRetry() {
        if (fallbackRetryCount >= MAX_FALLBACK_RETRIES) return
        fallbackRetryCount++
        fallbackRetryJob?.cancel()
        val gen = generation
        fallbackRetryJob = scope.launch {
            delay(FALLBACK_RETRY_MS)
            if (gen != generation) return@launch
            if (!Rime.isComposing()) return@launch
            refresh(immediate = true)
        }
    }

    companion object {
        /** 面板關閉時只枚舉候選條需要的條數：避免每次按鍵都全量枚舉 600 條（按鍵卡頓）。 */
        /**
         * 候選條枚舉上限：必須與面板一致（2026-11-09 用戶反饋「展開面板後固定候選區會變」）。
         * 之前面板關時只枚舉 16 條，開面板換成 600 條，學習排序在更大的集合裡重排，
         * 候選條前幾名就跳了。統一成全量枚舉，兩邊永遠同一份數據。
         */
        private const val BAR_CANDIDATES = TwelveKeySnapshotBuilder.MAX_CANDIDATES
        /**
         * librime session 非線程安全：後台枚舉與 UI 線程的 process_key / get_context 必須串行。
         * 鍵盤事件入口（TextInputManager.onEvent 等）也持同一把鎖。
         */
        /** 與 [Rime.ENGINE_LOCK] 是同一顆鎖（Java 側 onKey/getContexts 也用它）。 */
        val ENGINE_LOCK: Any = Rime.ENGINE_LOCK

        private const val CACHE_CAPACITY = 8

        /** 兜底快照後的重試延遲（等引擎就緒/候選落地）。 */
        private const val FALLBACK_RETRY_MS = 90L

        /** 兜底重試上限：超過就停，避免一直搶鎖讓面板/鍵盤卡頓。 */
        private const val MAX_FALLBACK_RETRIES = 3

        /** 連打期間不做全量枚舉，停手後再算。 */
        private const val DEBOUNCE_MS = 120L
    }
}
