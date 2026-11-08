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
        // 固定候選欄不再顯示拼音提示（用戶 2026-11-09：「关闭固定候选栏的第一个汉字拼音提示」）。
        view.setShowComment(false)
        view.setCandidates(entries)
    }

    /** 離開 12 鍵路徑時恢復「候選條自己找 Rime.getCandidates()」的舊行為。 */
    fun clear() {
        candidateView?.setShowComment(true)
        candidateView?.clearExternalCandidates()
    }
}
