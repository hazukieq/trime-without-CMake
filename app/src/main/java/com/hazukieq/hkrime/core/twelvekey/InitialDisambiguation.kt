package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 待確認首鍵的合法首拼與底層讀音篩選入口。 */
object InitialDisambiguation {
    const val PROPERTY = "hkrime_initial"

    fun choices(code: String): List<Char> {
        if (!CompositionInput.isCodeString(code)) return emptyList()
        val first = code.firstOrNull() ?: return emptyList()
        return TwelveKeyPreedit.keyInitialLetters(first, isLast = code.length == 1).distinct()
    }

    fun matches(initial: Char?, reading: String): Boolean =
        initial == null || TwelveKeyPreedit.parseComment(reading)?.firstOrNull()?.firstOrNull() == initial
}
