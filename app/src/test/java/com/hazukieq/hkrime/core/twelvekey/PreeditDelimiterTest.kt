package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class PreeditDelimiterTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadDict() {
            OfflineDictionary.load()
        }
    }

    @Test
    fun twelveKeyPreservesExplicitApostropheInPreedit() {
        // 尾部剛按下分詞鍵：顯示 `sin'` 而非 `sin` 或 `sin `
        assertEquals("sin'", TwelveKeyPreedit.rewrite("7i4 ‸", "升", "sin", rawInput = "7i4'"))
        // 分詞鍵後繼續輸入首碼：`sin'k`
        assertEquals("sin'k", TwelveKeyPreedit.rewrite("7i4 5‸", "升起", "sin ki", rawInput = "7i4'5"))
        // 分詞鍵後輸入完整音節：`sin'ki`
        assertEquals("sin'ki", TwelveKeyPreedit.rewrite("7i4 5i‸", "升起", "sin ki", rawInput = "7i4'5i"))
        // 未按分詞鍵時維持空格：`sin ki`
        assertEquals("sin ki", TwelveKeyPreedit.rewrite("7i4 5i‸", "升起", "sin ki", rawInput = "7i45i"))
    }

    @Test
    fun qwertyRestoresExplicitApostropheWithoutTouchingAutoSpaces() {
        assertEquals("an'", TwelveKeyPreedit.restoreDelimiters("an ‸", "an'"))
        assertEquals("an'ki", TwelveKeyPreedit.restoreDelimiters("an ki‸", "an'ki"))
        assertEquals("an ki", TwelveKeyPreedit.restoreDelimiters("an ki", "anki"))
    }

    @Test
    fun confirmedPhoneticPreeditPreservesTrailingAndInfixApostrophe() {
        val tai = listOf(PhoneticSelection("tai", "9ai", false))
        PreeditPresentation.invalidate("", tai)
        assertEquals("tai'", PreeditPresentation.render("9ai ‸", "9ai'"))
        assertEquals("tai'go", PreeditPresentation.render("9ai i4‸", "9ai'i4", "太哥", "tai go"))
        PreeditPresentation.invalidate("")
    }
}
