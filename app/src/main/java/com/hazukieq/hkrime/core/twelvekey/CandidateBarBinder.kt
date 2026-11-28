package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.ime.text.Candidate

/**
 * 把快照 barItems 綁到固定候選條；barItems 優先 barEntries，僅缺失時回退首組。
 * 不獨立讀引擎；展開圖示由 Candidate 繪製。
 */
class CandidateBarBinder(private val candidateView: Candidate?) {

    /** 上次綁定的條目；同一份快照會被多次 updateComposing 重複綁定，內容沒變就跳過重繪。 */
    private var lastEntries: List<Triple<String, String, Boolean>>? = null

    /** 當前條目 → (text, comment, openccModified)；fallback 時只有一條字母原文。 */
    fun bind(snapshot: TwelveKeySnapshot) {
        val view = candidateView ?: return
        val entries = ArrayList<Candidate.BarEntry>(snapshot.barItems.size + 1)
        val keys = ArrayList<Triple<String, String, Boolean>>(snapshot.barItems.size + 1)
        snapshot.barItems.forEach { item ->
            entries.add(Candidate.BarEntry(item.text, item.comment, item.openccModified))
            keys.add(Triple(item.text, item.comment, item.openccModified))
        }
        val fallback = snapshot.fallback
        if (entries.isEmpty() && fallback != null) {
            entries.add(Candidate.BarEntry(fallback.letters, fallback.rawCode, false))
            keys.add(Triple(fallback.letters, fallback.rawCode, false))
        }
        if (keys == lastEntries) return
        lastEntries = keys
        // 固定候選條關閉拼音提示，讀音留給面板左列。
        view.setShowComment(false)
        view.setCandidates(entries)
    }

    /**
     * 非 12 鍵模式由候選條讀 Rime.getCandidates，仍關閉固定條讀音提示。
     */
    fun clear() {
        lastEntries = null
        candidateView?.setShowComment(false)
        candidateView?.clearExternalCandidates()
    }
}
