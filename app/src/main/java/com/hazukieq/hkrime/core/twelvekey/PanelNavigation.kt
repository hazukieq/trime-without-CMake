package com.hazukieq.hkrime.core.twelvekey

/** 左欄的純展示狀態；不持有引擎、preedit、選擇日誌或提交回調。 */
internal class PanelNavigation {
    var groups: List<PanelDisplayGroup> = emptyList()
        private set
    var activeIndex: Int = 0
        private set
    private var lastRemaining: String? = null
    private var contextKey: String = ""

    fun submit(snapshot: TwelveKeySnapshot, preserveReading: Boolean = false) {
        val previous = groups.getOrNull(activeIndex)?.reading?.takeIf { it.isNotEmpty() }
        val changed = snapshot.remaining != lastRemaining
        groups = foldGroupsForDisplay(snapshot.groups)
        activeIndex = if (changed && !preserveReading) 0 else {
            groups.indexOfFirst { it.reading == previous && it.segment != RECOMMEND_SEGMENT }
                .coerceAtLeast(0)
        }
        contextKey = snapshot.contextKey
        lastRemaining = snapshot.remaining
    }

    fun select(index: Int, reading: String, key: String): Boolean {
        if (!CandidateSelection.isCurrent(key, contextKey) || index == activeIndex ||
            groups.getOrNull(index)?.reading != reading) return false
        activeIndex = index
        return true
    }

    fun resetSelection() {
        activeIndex = 0
        lastRemaining = null
    }
}
