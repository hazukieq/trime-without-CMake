package com.hazukieq.hkrime.core.twelvekey

/**
 * 12 鍵架構的只讀數據模型。
 *
 * 設計原則（見 docs/10-twelve-key-input-design.md §4.2）：
 * - 一次按鍵 = 一個不可變 [TwelveKeySnapshot]；候選條與展開面板都只讀同一份快照。
 * - [CandidateItem.index] 是 librime menu 裡的**全局下標**，直接餵給 Rime.select_candidate(int)。
 * - [SyllableGroup.reading] 即候選 comment（spelling_hints 打開後是精確拼音）。
 */
/**
 * 合成候選（App 側「逐段拼出來的組詞」）的 [CandidateItem.segment] 標記。
 *
 * 它不來自 librime：引擎裝不出這種整串（`enable_sentence: false`），所以點擊時不能走
 * `select_candidate`，只能整串上屏；完整碼串放在 [CandidateItem.restCode] 裡，上屏後用它記學習。
 */
const val SYNTHETIC_SEGMENT = -2

data class CandidateItem(
    val text: String,
    val comment: String,
    val index: Int,
    /** >=0 表示「分詞後按段選字」的字典候選（index=-1，不來自引擎）；值為段序號。 */
    val segment: Int = -1,
    /** 選中後要重新餵回引擎的剩餘碼（按段選字用；空表示沒有剩餘）。 */
    val restCode: String = "",
)

/** 左列一個音節組：reading 是精確拼音（comment），items 是該讀音下的全部候選。 */
data class SyllableGroup(
    val reading: String,
    val syllables: List<String>,
    val items: List<CandidateItem>,
    /** >=0 表示這組是「分詞段」的字典候選（按段選字）而不是引擎候選組。 */
    val segment: Int = -1,
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
 * @param barEntries 候選條專用的「選單順序前 N 條」（不是第 0 組！長輸入時每個候選的
 *   reading 都不同，按組取會只剩 1 條，看起來像候選停了）
 */
data class TwelveKeySnapshot(
    val input: String,
    val consumedEnd: Int,
    val remaining: String,
    val groups: List<SyllableGroup>,
    val fallback: Fallback?,
    val degradedToSinglePage: Boolean = false,
    val barEntries: List<CandidateItem> = emptyList(),
    /**
     * 嵌入式預覽（inline preview）要顯示的文字。
     *
     * 由快照本身算好（= 候選條第一條），隨快照一起發佈，這樣「候選條 / 面板 / preedit 預覽」
     * 三者讀的是同一份狀態；不再讓 EditorInstance 另外去問引擎要 commit_text_preview，
     * 避免「候選欄是第一條、預覽是引擎第 0 條」這種兩套 index 的錯位。
     */
    val previewText: String = "",
    /**
     * 嵌入式預覽要寫進輸入框的完整文本 = 已選前綴 + [previewText]。
     *
     * 12 鍵多音節輸入時選中的字還沒 commit（留在 librime context 裡），預覽只寫第一條候選
     * 會讓「已選的字」在輸入框裡消失；這裡把 librime preedit 的前綴一起帶上。
     */
    val inlineText: String = "",
) {
    val isEmpty: Boolean get() = input.isEmpty()
    val hasCandidates: Boolean get() = groups.isNotEmpty()

    /**
     * 候選條展示的條目：優先「選單順序前 N 條」[barEntries]（長輸入時每個候選
     * 的 reading/comment 都不同，按第 0 組取會只剩 1 條）；
     * 舊快照沒有 barEntries 時退回第 0 組，保持兼容。
     */
    val barItems: List<CandidateItem>
        get() = if (barEntries.isNotEmpty()) barEntries
        else groups.firstOrNull()?.items ?: emptyList()

    companion object {
        val EMPTY = TwelveKeySnapshot("", 0, "", emptyList(), null)
    }
}
