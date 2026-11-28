package com.hazukieq.hkrime.core.twelvekey

/**
 * 不可變候選快照模型；候選條、面板、preedit 與預覽共享。
 * CandidateItem.index 保留引擎全局下標，reading 使用具體讀音。
 */
/**
 * 合成候選（App 側「逐段拼出來的組詞」）的 [CandidateItem.segment] 標記。
 *
 * 它不來自 librime；選擇依綁定的原碼跨度進入同一份選擇日誌，不用引擎下標選中。
 */
const val SYNTHETIC_SEGMENT = -2

/**
 * 展開面板「推薦」組的 [SyllableGroup.segment] 標記。
 *
 * 推薦組 = 聯想詞 + 單字（候選條同一套順序；40 個 = 30 詞 + 10 單字，
 * 見 [TwelveKeyTuning.RECOMMEND_WORDS] / [TwelveKeyTuning.RECOMMEND_CHARS]），
 * 它沒有讀音（reading 為空），在左列固定排第一。用負數與段組（>=0）、合成候選（-2）區分。
 */
const val RECOMMEND_SEGMENT = -3

/**
 * 展開面板「孤單聯想」字母行的 [SyllableGroup.segment] 標記。
 *
 * reading = 首鍵字母（`d`/`f`），items 是該字母下可延伸的單音節名；點擊它進入該音節的字。
 * 只在剩餘碼 >=2 鍵且整串不是完整音節時生成。
 */
const val ISOLATED_SEGMENT = -4

/** 孤單聯想鑽取到音節後的「字」格子 [CandidateItem.segment] 標記。 */
const val ISOLATED_CHAR_SEGMENT = -5

/** 孤單聯想鑽取層的「返回」項 [CandidateItem.segment] 標記。 */
const val ISOLATED_BACK_SEGMENT = -6

/**
 * 展開面板左列「字母行」的 [SyllableGroup.segment] 標記。
 *
 * 已確認拼音序列後，待確認/可切換的原碼鍵列出其合法首拼（`b`/`c`）；它與音節組同一份左列，
 * 只承擔「細化/確認聲母」，右列內容不動。
 */
const val INITIAL_LETTER_SEGMENT = -7

/**
 * 「首段漢字」[CandidateItem.segment] 標記。
 *
 * 已確認段是簡拼時，首段可選字來自 App 音節→字表（引擎菜單沒有只覆蓋首段的單字）；
 * 跨度由 restCode 證實（`code.length - restCode.length`），只消費首段。
 */
const val INITIAL_CHAR_SEGMENT = -8

data class CandidateItem(
    val text: String,
    val comment: String,
    val index: Int,
    /** >=0 表示「分詞後按段選字」的字典候選（index=-1，不來自引擎）；值為段序號。 */
    val segment: Int = -1,
    /** 選中後要重新餵回引擎的剩餘碼（按段選字用；空表示沒有剩餘）。 */
    val restCode: String = "",
    /** 生成快照時證實的原碼跨度；顯示 comment/左欄折疊不得改寫。 */
    val inputSpan: InputCodeSpan? = null,
    /** 引擎原始輸入的絕對跨度，與顯示說明分離；App 合成項沒有此欄。 */
    val engineSpan: EngineCandidateSpan? = null,
    /** 是否經 OpenCC 簡繁/聯想等濾鏡實際改寫（與原詞典文本不同）。 */
    val openccModified: Boolean = false,
)

data class EngineCandidateSpan(val start: Int, val end: Int, val sourceComment: String) {
    companion object {
        fun fromRow(row: Array<String>): EngineCandidateSpan? = if (row.size < 5) null else
            EngineCandidateSpan(row[2].toIntOrNull() ?: -1, row[3].toIntOrNull() ?: -1, row[4])
    }
}

/** 相對於當次剩餘原碼的消費終點，並保留選擇用的原始讀音。 */
data class InputCodeSpan(val code: String, val end: Int, val reading: String) {
    fun matches(input: String): Boolean = code == input && end in 1..input.length
}

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
 * 一次按鍵後的不可變快照，包含原始碼、已消耗前綴、剩餘碼、候選條、面板及預覽。
 * 面板按具體 reading 合併與分檔；index 是全局引擎下標。
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
     * 面板左列「推薦」組的內容 = 聯想詞 + 單字（按候選條同一套排序；詞在前、單字在後，
     * 最多 [TwelveKeyTuning.RECOMMEND_WORDS] 詞 + [TwelveKeyTuning.RECOMMEND_CHARS] 單字）。
     */
    val recommend: List<CandidateItem> = emptyList(),
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
    val contextKey: String = "",
    val rawPreedit: String = "",
    val initialChoices: List<String> = emptyList(),
    val initial: String? = null,
    val engineOrderOnly: Boolean = false,
    val phonetics: List<PhoneticSelection> = emptyList(),
    val phoneticNavigation: Boolean = false,
    /** 當前快照是否命中了枚舉上限、且仍可點擊「全部」懶加載更多候選。 */
    val hasMore: Boolean = false,
) {
    val isEmpty: Boolean get() = input.isEmpty()
    /**
     * 以候選條或面板是否有項判斷可選性；面板關閉時 groups 為空不能單獨作判據。
     */
    val hasCandidates: Boolean get() = barEntries.isNotEmpty() || groups.isNotEmpty()

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
