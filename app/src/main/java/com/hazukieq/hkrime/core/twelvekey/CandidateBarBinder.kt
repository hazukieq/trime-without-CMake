package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.ime.text.Candidate

/**
 * 把快照的「當前組」寫進單行候選條（[Candidate]）。
 *
 * 候選條只讀快照第 0 組；末尾的 ▼/⌄ 由 [Candidate] 自己繪製。
 * **不調 Rime.getCandidates()**，避免候選條與面板讀到不同版本的數據。
 */
class CandidateBarBinder(private val candidateView: Candidate?) {

    /** 當前條目 → (text, comment)；fallback 時只有一條字母原文。 */
    fun bind(snapshot: TwelveKeySnapshot) {
        val view = candidateView ?: return
        val entries = ArrayList<Candidate.BarEntry>(snapshot.barItems.size + 1)
        snapshot.barItems.forEach { item ->
            entries.add(Candidate.BarEntry(item.text, item.comment))
        }
        val fallback = snapshot.fallback
        if (entries.isEmpty() && fallback != null) {
            entries.add(Candidate.BarEntry(fallback.letters, fallback.rawCode))
        }
        view.setCandidates(entries)
    }

    /** 離開 12 鍵路徑時恢復「候選條自己找 Rime.getCandidates()」的舊行為。 */
    fun clear() {
        candidateView?.clearExternalCandidates()
    }

    /**
     * 空候選（未組字）時顯示方案開關：把候選條交還給 [Candidate]，
     * 它會走 Rime.getCandidates() → RimeSchema.getCandidates()（简/繁、情、联、▽ 等）。
     */
    fun showSwitches() {
        candidateView?.clearExternalCandidates()
    }
}
