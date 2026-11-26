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
 * 12 鍵快照控制器。refresh 在 Default 線程持 ENGINE_LOCK 讀取狀態與構建，Main 發布。
 * generation 丟棄過期結果；候選條枚舉 64、面板 600；快照帶 contextKey 供點擊校驗。
 * 選擇統一由 TextInputManager 的 CompositionSession 記錄與發放提交許可。
 */
class TwelveKeyController(
    private val scope: CoroutineScope,
    private val onSnapshot: (TwelveKeySnapshot) -> Unit,
) {

    var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
        private set

    @Volatile var appRankingEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidateForOptions()
        }

    @Volatile var phonetics: List<PhoneticSelection> = emptyList()
        set(value) {
            if (field == value) return
            field = value.toList()
            invalidateForEngine()
        }

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
     * 方案開關版本號參與快照緩存鍵，filter 輸出變化時使緩存失效。
     */
    private var optionsVersion = 0

    /** 方案開關變化：讓候選快照緩存失效。 */
    fun invalidateForOptions() {
        optionsVersion++
        generation++
        refreshJob?.cancel()
    }

    /**
     * 選字、提交或重新初始化後增加引擎版本，使快照緩存失效。
     */
    fun invalidateForEngine() {
        engineVersion++
        generation++
        refreshJob?.cancel()
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
        val rankingEnabled = appRankingEnabled
        val gen = ++generation
        refreshJob?.cancel()
        // 直接派到 Default：避免「先排到 Main 佇列、再 withContext(Default)」多一次主線程來回，
        // 按鍵到候選條落地可以少 1 幀左右（實測 30ms 級）。
        refreshJob = scope.launch(Dispatchers.Default) {
            if (!immediate) delay(DEBOUNCE_MS)
            val result = run {
                synchronized(ENGINE_LOCK) {
                    runCatching {
                        val confirmed = phonetics
                        // 已確認段（含簡拼首段）是引擎過濾與「保留簡拼分支」的唯一依據。
                        val constraint = PhoneticSelection.constraint(confirmed)
                        if (Rime.getProperty(InitialDisambiguation.PROPERTY).orEmpty() != constraint) {
                            Rime.setProperty(InitialDisambiguation.PROPERTY, constraint)
                        }
                        val local = TwelveKeySnapshotBuilder.local()
                        if (local.isEmpty) {
                            Triple(local, TwelveKeySnapshot.EMPTY, false)
                        } else {
                            val key = TwelveKeySnapshotBuilder.cacheKey(
                                local,
                                full,
                                segmentAnchor,
                                segmentMode,
                                rankingEnabled,
                            ) + "|h" + constraint + "|o" + optionsVersion + "|e" + engineVersion +
                                "|a" + TwelveKeyPreedit.assocGeneration
                            val cached = cacheLookup(key) { it }
                            val t0 = System.nanoTime()
                            val built = cached ?: TwelveKeySnapshotBuilder.build(
                                local,
                                if (full) TwelveKeySnapshotBuilder.MAX_CANDIDATES else BAR_CANDIDATES,
                                segmentAnchor = segmentAnchor,
                                segmented = segmentMode,
                                wantGroups = full,
                                initial = null,
                                appRankingEnabled = rankingEnabled,
                                phonetics = confirmed,
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
                // 面板（全量 600）打開時，候選條前 12 條必須和限流快照一致，否則會「跳」。
                if (full && built.barEntries.isNotEmpty() && snapshot.contextKey == contextKey(local)) {
                    val prev = snapshot.barEntries.take(12).map { it.text }
                    val now = built.barEntries.take(12).map { it.text }
                    if (prev != now) {
                        com.hazukieq.hkrime.util.DiagLog.d(
                            "bar",
                            "drift limited=${prev} full=${now}",
                        )
                    }
                }
                val t = System.nanoTime()
                if (local.isEmpty) emit(TwelveKeySnapshot.EMPTY) else emit(built)
                val emitMs = (System.nanoTime() - t) / 1e6
                if (emitMs > 20.0) Timber.w("twelvekey:emit %.1fms", emitMs)
            }
        }
    }

    fun reset() {
        generation++
        refreshJob?.cancel()
        emit(TwelveKeySnapshot.EMPTY)
    }

    fun contextKey(local: TwelveKeySnapshotBuilder.Local): String =
        CandidateSelection.contextKey(local.contextKey, engineVersion, optionsVersion)

    private fun emit(next: TwelveKeySnapshot) {
        val published = if (next.isEmpty) next else next.copy(
            contextKey = CandidateSelection.contextKey(next.contextKey, engineVersion, optionsVersion),
        )
        snapshot = published
        onSnapshot(published)
        if (next.hasCandidates) fallbackRetryCount = 0
        // 兜底快照（引擎暫時拿不到候選）不緩存了，但也不會自己重試：
        // 這裡排一次延遲刷新，等引擎就緒/候選落地後把候選條與 preedit 一起換成真實內容。
        if (!next.isEmpty && !next.hasCandidates) scheduleFallbackRetry()
    }

    private var fallbackRetryJob: Job? = null
    private var fallbackRetryCount = 0
    /**
     * 兜底重試上限，避免無限刷新持續爭搶 ENGINE_LOCK。
     */
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
         * 候選條使用固定 64 條引擎窗口，面板全量構建也從此窗口計算相同的候選條。
         */
        private const val BAR_CANDIDATES = TwelveKeyTuning.BAR_CANDIDATES
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
