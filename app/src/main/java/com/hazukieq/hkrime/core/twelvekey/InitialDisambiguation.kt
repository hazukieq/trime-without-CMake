package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 待確認首鍵的合法首拼與底層讀音篩選入口。 */
object InitialDisambiguation {
    const val PROPERTY = "hkrime_initial"

    /** 待確認碼首鍵的合法首拼單元（th/ng/nz 是組合聲母，整段返回）。 */
    fun choices(code: String): List<String> {
        if (!CompositionInput.isCodeString(code)) return emptyList()
        val first = code.firstOrNull() ?: return emptyList()
        return TwelveKeyPreedit.keyInitialUnits(first, isLast = code.length == 1).distinct()
    }

    fun matches(initial: String?, reading: String): Boolean =
        initial == null || TwelveKeyPreedit.parseComment(reading)?.firstOrNull()
            ?.let { TwelveKeyPreedit.initialUnit(it) == initial } == true
}
