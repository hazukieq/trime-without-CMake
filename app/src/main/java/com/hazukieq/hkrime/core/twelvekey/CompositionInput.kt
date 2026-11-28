package com.hazukieq.hkrime.core.twelvekey

/** 按鍵前保存原碼意圖；無組字時的標點/普通直通文字不受組字提交守衛誤傷。 */
object CompositionInput {
    /** 連續組字輸入的最大編碼長度（經實測 12 鍵/9 鍵在此上限內 UI compose 維持單幀且無長句退化）。 */
    const val MAX_CODE_LENGTH = TwelveKeyTuning.MAX_FALLBACK_CODE

    /** 觸發最大編碼長度提示的最小節流間隔，避免持續連按刷屏。 */
    const val LIMIT_NOTICE_COOLDOWN_MS = 3000L

    class LimitNoticeGate(private val cooldownMs: Long = LIMIT_NOTICE_COOLDOWN_MS) {
        private var lastShownMs: Long = Long.MIN_VALUE

        @Synchronized
        fun tryAcquire(nowMs: Long): Boolean {
            if (lastShownMs != Long.MIN_VALUE && nowMs - lastShownMs < cooldownMs) {
                return false
            }
            lastShownMs = nowMs
            return true
        }

        @Synchronized
        fun reset() {
            lastShownMs = Long.MIN_VALUE
        }
    }

    data class Intent(val code: String, val allowRawCommit: Boolean)

    @JvmStatic
    fun isCodeLimitReached(code: String?): Boolean =
        !code.isNullOrEmpty() && code.length >= MAX_CODE_LENGTH

    @JvmStatic
    fun shouldBlockOverLimit(currentInput: String?, key: Int, allowAppend: Boolean): Boolean {
        if (!allowAppend || !isCodeLimitReached(currentInput)) return false
        return (key in 'a'.code..'z'.code) || (key in 'A'.code..'Z'.code) || key == '\''.code
    }

    /** 碼串僅含碼字母、分隔符與空白；標點/符號輸入不走多鍵選擇日誌。 */
    fun isCodeString(input: String): Boolean = MultiKeyProfile.active.isCodeString(input)

    /** 判斷 [end] 是否已覆蓋 [code] 的全部實質碼位（尾端僅剩分詞符或空白）。 */
    fun coversFullCode(code: String, end: Int): Boolean =
        end in 1..code.length && code.substring(end).all { it == '\'' || it.isWhitespace() }

    /**
     * 防止在組字中插入開頭 `'` 或連續重複的 `'`（如 `7i4'''`）：
     * preedit 只顯示一個 `'`，重複寫入會導致退格需要連按多次卻看不到變化。
     *
     * @param allowWhenEmpty 未組字時是否允許作為普通標點直通（長按/實體 `'` 為 true，分詞專用鍵為 false）。
     */
    fun shouldIgnoreDelimiter(input: String, caret: Int, allowWhenEmpty: Boolean = false): Boolean {
        if (input.isEmpty()) return !allowWhenEmpty
        val position = caret.coerceIn(0, input.length)
        if (position == 0) return true
        if (input[position - 1] == '\'' || input[position - 1].isWhitespace()) return true
        if (position < input.length && input[position] == '\'') return true
        return false
    }

    /**
     * 計算一次退格應刪掉的字符數：
     * 光標前若意外存在多個連續分詞符 `'`，單次退格一併刪除，避免回退多步卡頓。
     */
    fun backspaceCount(input: String, caret: Int): Int {
        val position = caret.coerceIn(0, input.length)
        if (position == 0) return 0
        if (input[position - 1] != '\'') return 1
        var i = position - 1
        while (i > 0 && input[i - 1] == '\'') i--
        return position - i
    }

    fun applyBackspace(input: String, caret: Int): String {
        val position = caret.coerceIn(0, input.length)
        val count = backspaceCount(input, position)
        return if (count <= 0) input else input.removeRange(position - count, position)
    }

    fun beforeKey(input: String, caret: Int, key: Int, hasSelection: Boolean): Intent {
        val position = caret.coerceIn(0, input.length)
        val isCodeKey = key in 0..127 && MultiKeyProfile.active.isCodeChar(key.toChar())
        val next = when {
            isCodeKey -> input.substring(0, position) + key.toChar() + input.substring(position)
            key == 0xff08 && position > 0 -> applyBackspace(input, position)
            else -> input
        }
        return Intent(next, input.isEmpty() && !hasSelection && !isCodeKey)
    }
}
