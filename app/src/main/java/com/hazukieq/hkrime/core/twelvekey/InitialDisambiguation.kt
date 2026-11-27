package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 待確認首鍵的合法首拼與底層讀音篩選入口。 */
object InitialDisambiguation {
    const val PROPERTY = "hkrime_initial"

    /** 待確認碼首鍵的合法首拼單元（12 鍵含 th/ng/nz 組合聲母，9 鍵為合法普通話首字母）。 */
    fun choices(code: String): List<String> {
        if (!CompositionInput.isCodeString(code)) return emptyList()
        val effective = code.trim { it == '\'' || it.isWhitespace() }
        val first = effective.firstOrNull() ?: return emptyList()
        return MultiKeyProfile.active.keyInitialUnits(first, isLast = effective.length == 1).distinct()
    }

    fun matches(initial: String?, reading: String): Boolean =
        initial == null || TwelveKeyPreedit.parseComment(reading)?.firstOrNull()
            ?.let { MultiKeyProfile.active.initialUnit(it) == initial } == true
}
