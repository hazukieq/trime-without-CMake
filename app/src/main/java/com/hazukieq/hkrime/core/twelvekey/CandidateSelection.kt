package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 選擇的消費範圍與快照驗證；條/面板/鍵盤確認共用。 */
object CandidateSelection {
    fun contextKey(nativeKey: String, engineVersion: Int, optionsVersion: Int): String =
        nativeKey + "|v" + engineVersion + "|o" + optionsVersion

    fun isCurrent(expected: String, actual: String): Boolean = expected.isNotEmpty() && expected == actual

    fun consumedEnd(item: CandidateItem, code: String, origin: CompositionSession.Origin): Int? {
        if (code.isEmpty() || item.text.isEmpty()) return null
        if (origin == CompositionSession.Origin.FALLBACK) {
            return code.length.takeIf { item.restCode == code }
        }
        if (item.segment == ISOLATED_CHAR_SEGMENT) {
            return code.indexOfFirst { it != '\'' && !it.isWhitespace() }.takeIf { it >= 0 }?.plus(1)
        }
        if (item.index < 0 && item.segment == 0) {
            // 字典段組保留明確切法；不可改用另一條引擎候選的邊界。
            return (code.length - item.restCode.length).takeIf { end ->
                end > 0 && code.endsWith(item.restCode) &&
                    TwelveKeySnapshotBuilder.splitParts(code).any {
                        it.rest == item.restCode && item.comment in it.syllables
                    }
            }
        }
        return TwelveKeyPreedit.selectedInputEnd(item.comment, code)
    }
}
