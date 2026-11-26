package com.hazukieq.hkrime.core.twelvekey

/**
 * 候選與面板策略常量：集中配額、上限及調參影響。
 * 修改需同步腳本、文檔與相關單測，再檢查真機布局和性能門禁。
 */
object TwelveKeyTuning {

    // ---------- 候選枚舉 ----------


    /**
     * 固定條原料窗口為 64 條；調大增加可競爭項與枚舉成本，調小減少候選覆蓋。
     * 全量面板仍用相同窗口計算固定條。
     */
    const val BAR_CANDIDATES = 64

    /**
     * 全量面板最多枚舉 600 條；調大提高覆蓋並增加構建/持鎖成本，調小可能截掉長尾詞。
     */
    const val MAX_CANDIDATES = 600

    /** 固定候選條一頁顯示幾條（`barItems` 的截斷長度）。 */
    const val BAR_ITEMS = 12

    /** 多音節條內缺單字時，末尾最多補入的第一待選段單字數；首候選必須保留。 */
    const val FIRST_SEGMENT_CHARS = 4

    /**
     * 已確認簡拼時「首段漢字」的候選上限：引擎菜單只給整串詞，首段單字由 App 字表補。
     * 調大覆蓋更全但右列更長；調小更快、更容易只看常用字。
     */
    const val INITIAL_CHARS_LIMIT = 120

    /**
     * 已確認拼音序列後，右列（推薦組）最多放多少個引擎詞。
     * 引擎在 600 窗口內已按確認序列過濾；這裡只限制首屏，滾動仍可看長尾。
     */
    const val CONFIRM_RECOMMEND_LIMIT = 200

    /**
     * 已確認拼音序列時，首段漢字插在多少個引擎詞之後（面板一頁約 3 列 × 4 行）。
     * 保持引擎詞順序不變，只把「首段可上屏的字」放到第一頁末尾，避免要翻幾十屏才選得到。
     */
    const val CONFIRM_CHARS_AFTER_WORDS = 6



    /**
     * 每音節最多 1000 字，供面板完整列字。調小可能缺字，調大增加構建成本。
     */
    const val SEGMENT_CHARS_LIMIT = 1000

    /**
     * 推薦組詞數配額：2 字 / 3 字 / 4 字及以上各 10，合計 30。
     * 某一档不足时由其余档按出现顺序补满；调大增加刷新成本，调小减少覆盖。
     */
    const val RECOMMEND_WORDS_TWO = 10
    const val RECOMMEND_WORDS_THREE = 10
    const val RECOMMEND_WORDS_FOUR_PLUS = 10

    /** 推薦組最多 30 詞（三档配额之和）。 */
    const val RECOMMEND_WORDS =
        RECOMMEND_WORDS_TWO + RECOMMEND_WORDS_THREE + RECOMMEND_WORDS_FOUR_PLUS

    /**
     * 推薦組最多 10 單字，先引擎候選再段組補位；調大增加候選與滾動，調小減少覆蓋。
     */
    const val RECOMMEND_CHARS = 10

    /** 面板分組總數上限（引擎按讀音分組可能上百個）。 */
    const val MAX_GROUPS = 200

    // ---------- 單音節組：每個碼位給幾個音節 ----------

    /**
     * 普通鍵的單音節組上限：一個碼位最多列 16 個「音節 → 單字」組。
     *
     * 調大：左列更長、更全，但要滑更多；調小：滑動少，但部分音節夠不到。
     * 有段組時生效（面板展開才有分組）。
     */
    const val SEGMENT_SYLLABLE_LIMIT = 16

    /**
     * 「音節構成豐富」的鍵放開到多少個單音節組。
     *
     * 12 鍵裡大部分首碼能覆蓋 60~86 個音節（`y` 29、`3` 23），16 個明顯不夠滑，
     * 故對可用音節數超過 [RICH_SYLLABLE_THRESHOLD] 的鍵放開到 24。
     */
    const val SEGMENT_SYLLABLE_LIMIT_RICH = 24

    /**
     * 「豐富」的判定閾值 = 該碼位可用音節數（精確碼 + 前綴命中）。
     *
     * 超過它就用 [SEGMENT_SYLLABLE_LIMIT_RICH]。現在等於 16：
     * 即「基礎上限不夠用」就算豐富（避免 17~23 個的鍵被砍掉一截，例：`3` 有 23 個）。
     */
    const val RICH_SYLLABLE_THRESHOLD = SEGMENT_SYLLABLE_LIMIT

    /**
     * 單次 `syllablesForSegment` 的默認總上限（= [SEGMENT_SYLLABLE_LIMIT]，16）。
     *
     * 影響：每個碼位最多列多少個音節組（跨首字母合計）；現在 16。
     * 獨立於 [SEGMENT_SYLLABLE_LIMIT] 的意義：`limit` 是「每個首字母各取幾個」，
     * 這個是「全部首字母合計」的防呆上限，避免一個鍵覆蓋 3 個聲母時左列過長。
     * 調大：音節更全、面板更長；調小：後面的首字母整批看不到。
     */
    const val MAX_SYLLABLES_TOTAL = SEGMENT_SYLLABLE_LIMIT

    /**
     * 同一個碼位的「備選切法」拿多少配額（按一半算）。
     *
     * 例如 `we` 既能整串讀 the、也能切成 w|e：第一種切法吃滿 16/24，
     * 備選切法只拿一半，避免次要解讀把左列灌滿。
     */
    const val SECONDARY_SPLIT_DIVISOR = 2

    // ---------- 聯想詞組 ----------

    /**
     * 每聯想行最多 20 詞，不足時完整列出。調大增加行內候選與構建成本，調小減少覆蓋。
     */
    const val ASSOC_WORDS = 20

    /**
     * 聯想行預算公式基底 4；實際總額由 ASSOC_BUCKET_ROW_BUDGET 計算，調整等比影響行數。
     */
    const val ASSOC_MAX_ROWS = 4

    /**
     * 聯想行總額為 ASSOC_MAX_ROWS×3，即 12；調大增加桶覆蓋與面板長度，調小可能漏桶。
     */
    const val ASSOC_BUCKET_ROW_BUDGET = ASSOC_MAX_ROWS * 3

    /**
     * 整串碼前綴聯想（三音節以上詞，如 `li nak si` → Linux）最多幾行；行內詞數沿用 [ASSOC_WORDS]。
     *
     * 影響：`WHOLE_WORD` 源行數；現在 4。
     * 調大：長詞/英文詞露得更全，但面板更長；調小：只留詞數最多的前幾行。
     */
    const val ASSOC_PREFIX_READINGS = 4

    // ---------- 首字母組合（INITIAL_COMBO 源） ----------

    /** 12 鍵碼位 → 該鍵上的字母（與 `TwelveKeyPreedit.codeOf` 的映射互為逆）。 */
    val KEY_LETTERS: Map<Char, String> = mapOf(
        'a' to "abc",
        'e' to "def",
        'i' to "ghi",
        '5' to "kl",
        '4' to "mn",
        '7' to "ps",
        '9' to "qt",
        '3' to "vur",
        '8' to "zj",
        '1' to "ng",
        'w' to "th",
        'y' to "nz",
    )

    /**
     * 元音字母：不能作音節首字母。
     *
     * 影響：`INITIAL_COMBO` 的組合生成（只由輔音開頭）；現在 `aeiou`。
     * 調大：更多字母被排除，組合更少、更「像聲母」；調小：元音也進組合，
     * 會生成 `a·b` 這種現實中不存在的首字母序列（詞表命中率極低）。
     */
    val VOWEL_LETTERS: Set<Char> = setOf('a', 'e', 'i', 'o', 'u')

    /**
     * 首字母組合行總行數上限。
     *
     * 影響：`INITIAL_COMBO` 源（`ia8` 8 組、`ia8i3` 32 組）；現在 8。
     * 調大：面板首字母組合行更多、更全，但會把單字組/聯想行往後推；
     * 調小：只保留每組代表，部分組合看不到詞。
     */
    const val INITIAL_COMBO_MAX_ROWS = 8

    /**
     * 每個首字母組合**每輪**先出幾行。
     *
     * 影響：輪轉節奏；現在 1（第一輪每組合保 1 行，第二輪補第 2 行）。
     * 調大：少數高頻組合壟斷行數預算；調小到 0：不生成首字母組合行。
     */
    const val INITIAL_COMBO_ROWS_PER_PATTERN = 1

    /**
     * 首字母組合數上限。
     *
     * 影響：`INITIAL_COMBO` 是否生成；現在 64（`5i4a57` 6 鍵恰 64 組仍生成）。
     * 調大：2^k 爆炸風險（組合數 × 詞表查詢變慢）；調小：5 鍵以上就沒有首字母組合行。
     */
    const val INITIAL_COMBO_PATTERN_LIMIT = 64

    /**
     * 聯想行「分桶」時展開第一/第二段音節的上限（= 不截斷）。
     *
     * 影響：`ASSOC` 源收集 (第一音節 × 第二音節) 桶時取多少候選音節；現在 1000000。
     * 調小：桶覆蓋不全，`g·b`/`h·c` 這類通配組合會漏行（早期用面板配額 16 就漏過）。
     * 調大：沒有意義，只是防呆。
     */
    const val ASSOC_EXPAND_LIMIT = 1_000_000

    // ---------- 兜底 ----------

    /** 無候選時「本地原文」最多顯示多少個碼位對應的字母。 */
    const val MAX_FALLBACK_CODE = 48

    /** 沒讀音 comment 的組顯示的佔位標題。 */
    const val BLANK_READING = "…"
}
