package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime
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
                            val key = TwelveKeySnapshotBuilder.cacheKey(local, full)
                            val cached = cacheLookup(key) { it }
                            val t0 = System.nanoTime()
                            val built = cached ?: TwelveKeySnapshotBuilder.build(
                                local,
                                if (full) TwelveKeySnapshotBuilder.MAX_CANDIDATES else BAR_CANDIDATES,
                            )
                            if (com.hazukieq.hkrime.BuildConfig.DEBUG) {
                                Timber.d(
                                    "twelvekey:build %s cached=%s ms=%.1f",
                                    key,
                                    cached != null,
                                    (System.nanoTime() - t0) / 1e6,
                                )
                            }
                            if (cached == null) cachePut(key, built)
                            Triple(local, built, true)
                        }
                    }.getOrNull()
                }
            } ?: return@launch
            if (gen != generation) return@launch
            val (local, built, _) = result
            withContext(Dispatchers.Main.immediate) {
                if (gen != generation) return@withContext
                if (local.isEmpty) emit(TwelveKeySnapshot.EMPTY) else emit(built)
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
                    val k = TwelveKeySnapshotBuilder.cacheKey(local, true)
                    key = k
                    cacheLookup(k) { it } ?: TwelveKeySnapshotBuilder.build(local)
                }
            }.getOrNull()
        } ?: TwelveKeySnapshot.EMPTY
        if (!built.isEmpty) key?.let { cachePut(it, built) }
        emit(built)
        return built
    }

    /** 點候選：全局下標；引擎自己決定消費幾個音節，剩餘碼由引擎重算。 */
    fun selectCandidate(globalIndex: Int) {
        if (globalIndex < 0) return
        // 先把選中的詞記到本地記憶（候選條/面板下次把同詞排前）——必須在 select 之前拿快照。
        runCatching {
            val snap = snapshot
            val item = snap.barEntries.firstOrNull { it.index == globalIndex }
                ?: snap.groups.asSequence().flatMap { it.items.asSequence() }
                    .firstOrNull { it.index == globalIndex }
            if (item != null) SelectionMemory.bump(item.text, item.comment)
        }
        val ok = runCatching { Rime.select_candidate(globalIndex) }.getOrDefault(false)
        if (!ok) {
            Timber.w("twelvekey: select_candidate(%d) failed", globalIndex)
            return
        }
        runCatching { Rime.getContexts() }
        // commitRimeText → updateComposing → onCandidatesRefreshed → refresh()，只重建一次快照
        onEngineChanged()
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
    }

    companion object {
        /** 面板關閉時只枚舉候選條需要的條數：避免每次按鍵都全量枚舉 600 條（按鍵卡頓）。 */
        private const val BAR_CANDIDATES = 16
        /**
         * librime session 非線程安全：後台枚舉與 UI 線程的 process_key / get_context 必須串行。
         * 鍵盤事件入口（TextInputManager.onEvent 等）也持同一把鎖。
         */
        /** 與 [Rime.ENGINE_LOCK] 是同一顆鎖（Java 側 onKey/getContexts 也用它）。 */
        val ENGINE_LOCK: Any = Rime.ENGINE_LOCK

        private const val CACHE_CAPACITY = 8

        /** 連打期間不做全量枚舉，停手後再算。 */
        private const val DEBOUNCE_MS = 120L
    }
}
