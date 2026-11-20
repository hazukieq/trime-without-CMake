package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 候選構建時綁定原碼跨度；點擊時只驗證，不解析顯示文字。 */
object CandidateSelection {
    fun contextKey(nativeKey: String, engineVersion: Int, optionsVersion: Int): String =
        nativeKey + "|v" + engineVersion + "|o" + optionsVersion

    fun isCurrent(expected: String, actual: String): Boolean = expected.isNotEmpty() && expected == actual

    fun consumedEnd(item: CandidateItem, code: String, origin: CompositionSession.Origin): Int? {
        val span = item.inputSpan?.takeIf { it.matches(code) } ?: return null
        if (origin == CompositionSession.Origin.FALLBACK && item.restCode != code) return null
        return span.end
    }

    fun fallback(text: String, code: String): CandidateItem = CandidateItem(
        text, "", -1, restCode = code, inputSpan = InputCodeSpan(code, code.length, ""),
    )

    fun bind(item: CandidateItem, code: String): CandidateItem = InputBinder(code).bind(item)

    /** 一幀內同讀音/切法只解析一次，單字組不因字數重複計算。 */
    class InputBinder(private val code: String, private val inputOffset: Int = 0) {
        private data class Key(val reading: String, val segment: Int, val restCode: String)
        private val spans = HashMap<Key, InputCodeSpan?>()
        private val isCode = CompositionInput.isCodeString(code)
        private val dictionarySplits by lazy { TwelveKeySnapshotBuilder.splitParts(code) }
        fun bind(item: CandidateItem): CandidateItem {
            if (item.text.isEmpty() || !isCode) return item.copy(inputSpan = null)
            item.engineSpan?.let { native ->
                val end = native.end - inputOffset
                val reading = TwelveKeySnapshotBuilder.cleanCommentForSelection(native.sourceComment)
                val tokens = TwelveKeyPreedit.parseComment(reading)
                val knownReading = reading.takeIf { !tokens.isNullOrEmpty() &&
                    tokens.all { token -> TwelveKeyPreedit.isKnownSyllable(token) } }.orEmpty()
                val span = if (item.index >= 0 && native.start == inputOffset && end in 1..code.length)
                    InputCodeSpan(code, end, knownReading) else null
                return item.copy(inputSpan = span)
            }
            val key = Key(item.comment, item.segment, item.restCode)
            if (key !in spans) {
                val splits = if (item.index < 0 && item.segment == 0) dictionarySplits else emptyList()
                spans[key] = resolveEnd(item, code, splits)?.let { InputCodeSpan(code, it, item.comment) }
            }
            return item.copy(inputSpan = spans[key])
        }
    }

    private fun resolveEnd(item: CandidateItem, code: String, splits: List<TwelveKeySnapshotBuilder.SplitPart>): Int? {
        if (code.isEmpty() || item.text.isEmpty()) return null
        if (item.segment == ISOLATED_CHAR_SEGMENT) {
            return code.indexOfFirst { it != '\'' && !it.isWhitespace() }.takeIf { it >= 0 }?.plus(1)
        }
        if (item.segment == ISOLATED_SEGMENT || item.segment == ISOLATED_BACK_SEGMENT) return null
        if (item.index < 0 && item.segment == 0) {
            // 字典段組保留明確切法；不可改用另一條引擎候選的邊界。
            return (code.length - item.restCode.length).takeIf { end ->
                end > 0 && code.endsWith(item.restCode) &&
                    splits.any {
                        it.rest == item.restCode && item.comment in it.syllables
                    }
            }
        }
        return CandidateInputBoundary.selectedInputEnd(item.comment, code)
    }
}
