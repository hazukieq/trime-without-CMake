package com.hazukieq.hkrime.core.twelvekey

/** 按鍵前保存原碼意圖；無組字時的標點/普通直通文字不受組字提交守衛誤傷。 */
object CompositionInput {
    private const val CODE_KEYS = "aei5479381wy'"
    data class Intent(val code: String, val allowRawCommit: Boolean)

    fun beforeKey(input: String, caret: Int, key: Int, hasSelection: Boolean): Intent {
        val position = caret.coerceIn(0, input.length)
        val isCodeKey = key in 0..127 && key.toChar() in CODE_KEYS
        val next = when {
            isCodeKey -> input.substring(0, position) + key.toChar() + input.substring(position)
            key == 0xff08 && position > 0 -> input.removeRange(position - 1, position)
            else -> input
        }
        return Intent(next, input.isEmpty() && !hasSelection && !isCodeKey)
    }
}
