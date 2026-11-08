package com.hazukieq.hkrime.core.twelvekey

/**
 * 12 鍵架構的只讀數據模型。
 *
 * 設計原則（見 docs/10-twelve-key-input-design.md §4.2）：
 * - 一次按鍵 = 一個不可變 [TwelveKeySnapshot]；候選條與展開面板都只讀同一份快照。
 * - [CandidateItem.index] 是 librime menu 裡的**全局下標**，直接餵給 Rime.select_candidate(int)。
 * - [SyllableGroup.reading] 即候選 comment（spelling_hints 打開後是精確拼音）。
 */
data class CandidateItem(
    val text: String,
    val comment: String,
    val index: Int,
)

/** 左列一個音節組：reading 是精確拼音（comment），items 是該讀音下的全部候選。 */
data class SyllableGroup(
    val reading: String,
    val syllables: List<String>,
    val items: List<CandidateItem>,
) {
    val size: Int get() = items.size
}

/** 引擎無法給出候選、但輸入串還在時的本地兜底：把 12 鍵碼逐字轉成拼音字母。 */
data class Fallback(
    val rawCode: String,
    val letters: String,
)

/**
 * 一次按鍵後的完整輸入快照。
 *
 * @param input librime 的 raw input（12 鍵碼串，如 "1i4ia3"）
 * @param consumedEnd 已選中/已確認段的最大字節 end；0 表示尚無已消費前綴
 * @param remaining 尚未消費的碼串（input 的 consumedEnd 之後部分）
 * @param groups 按 comment 分組的候選；順序 = 首次出現順序，不重排
 * @param fallback 無候選但有輸入時的本地兜底
 * @param degradedToSinglePage native 枚舉不可用，已降級為 getContexts 單頁
 */
data class TwelveKeySnapshot(
    val input: String,
    val consumedEnd: Int,
    val remaining: String,
    val groups: List<SyllableGroup>,
    val fallback: Fallback?,
    val degradedToSinglePage: Boolean = false,
) {
    val isEmpty: Boolean get() = input.isEmpty()
    val hasCandidates: Boolean get() = groups.isNotEmpty()

    /** 候選條展示的是第 0 組（設計 §5.4：默認組固定第 0 組）。 */
    val barItems: List<CandidateItem> get() = groups.firstOrNull()?.items ?: emptyList()

    companion object {
        val EMPTY = TwelveKeySnapshot("", 0, "", emptyList(), null)
    }
}
