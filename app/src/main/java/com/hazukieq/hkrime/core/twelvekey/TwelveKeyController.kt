package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.Rime
import timber.log.Timber

/**
 * 12 鍵輸入的無 View 控制器：按鍵/上屏後重建快照，選候選時走引擎。
 *
 * 所有引擎寫操作只有兩處：
 * - [selectCandidate] → Rime.select_candidate(全局下標)，引擎自己消費若干音節；
 * - [commitFallback] → clearComposition + 本地原文上屏（不學習、不入用戶詞典）。
 */
class TwelveKeyController(
    private val onSnapshot: (TwelveKeySnapshot) -> Unit,
    /** 引擎狀態已變：提交 commit buffer + 刷新 preedit（由 TextInputManager 實現）。 */
    private val onEngineChanged: () -> Unit,
    /** 本地原文上屏（fallback 用）。 */
    private val onCommitText: (String) -> Unit,
) {

    var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
        private set

    /** 每次按鍵（或任何可能改變 input/候選的操作）後調用。 */
    fun refresh() {
        snapshot = TwelveKeySnapshotBuilder.build()
        onSnapshot(snapshot)
    }

    /** 點候選：全局下標；引擎自己決定消費幾個音節，剩餘碼由引擎重算。 */
    fun selectCandidate(globalIndex: Int) {
        if (globalIndex < 0) return
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
        snapshot = TwelveKeySnapshot.EMPTY
        onSnapshot(snapshot)
        onCommitText(fallback.letters)
    }

    fun reset() {
        snapshot = TwelveKeySnapshot.EMPTY
        onSnapshot(snapshot)
    }
}
