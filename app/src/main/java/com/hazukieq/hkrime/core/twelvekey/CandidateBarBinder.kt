package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.ime.text.Candidate

/**
 * 把快照的「當前組」寫進單行候選條（[Candidate]）。
 *
 * 候選條只讀快照第 0 組；末尾的 ▼/⌄ 由 [Candidate] 自己繪製。
 * **不調 Rime.getCandidates()**，避免候選條與面板讀到不同版本的數據。
 */
class CandidateBarBinder(private val candidateView: Candidate?) {

    /** 上次綁定的條目；同一份快照會被多次 updateComposing 重複綁定，內容沒變就跳過重繪。 */
    private var lastEntries: List<Pair<String, String>>? = null

    /** 當前條目 → (text, comment)；fallback 時只有一條字母原文。 */
    fun bind(snapshot: TwelveKeySnapshot) {
        val view = candidateView ?: return
        val entries = ArrayList<Candidate.BarEntry>(snapshot.barItems.size + 1)
        val keys = ArrayList<Pair<String, String>>(snapshot.barItems.size + 1)
        snapshot.barItems.forEach { item ->
            entries.add(Candidate.BarEntry(item.text, item.comment))
            keys.add(item.text to item.comment)
        }
        val fallback = snapshot.fallback
        if (entries.isEmpty() && fallback != null) {
            entries.add(Candidate.BarEntry(fallback.letters, fallback.rawCode))
            keys.add(fallback.letters to fallback.rawCode)
        }
        if (keys == lastEntries) return
        lastEntries = keys
        // 固定候選欄不再顯示拼音提示（用戶 2026-11-09：「关闭固定候选栏的第一个汉字拼音提示」）。
        view.setShowComment(false)
        view.setCandidates(entries)
    }

    /**
     * 離開 12 鍵路徑時恢復「候選條自己找 Rime.getCandidates()」的舊行為。
     *
     * 註釋仍然關閉：拼音只給展開面板左列分組用，固定候選欄在所有方案裡都不顯示拼音提示，
     * 高度才統一（用戶 2026-11-09：全字母鍵盤候選區高度要和 12 鍵一致）。
     */
    fun clear() {
        lastEntries = null
        candidateView?.setShowComment(false)
        candidateView?.clearExternalCandidates()
    }
}
