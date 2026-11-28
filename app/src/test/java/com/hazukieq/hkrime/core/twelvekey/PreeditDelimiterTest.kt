package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun composingCodeCapBlocksExtraAppendsAboveLimitWhileAllowingControlKeys() {
        val under = "a".repeat(CompositionInput.MAX_CODE_LENGTH - 1)
        val atLimit = "a".repeat(CompositionInput.MAX_CODE_LENGTH)
        assertFalse(CompositionInput.isCodeLimitReached(under))
        assertTrue(CompositionInput.isCodeLimitReached(atLimit))
        assertFalse(CompositionInput.shouldBlockOverLimit(under, 'b'.code, allowAppend = true))
        assertTrue(CompositionInput.shouldBlockOverLimit(atLimit, 'b'.code, allowAppend = true))
        assertTrue(CompositionInput.shouldBlockOverLimit(atLimit, '\''.code, allowAppend = true))
        // 退格、回車、空格及控制組合鍵不受編碼上限阻斷
        assertFalse(CompositionInput.shouldBlockOverLimit(atLimit, 0xff08, allowAppend = true))
        assertFalse(CompositionInput.shouldBlockOverLimit(atLimit, ' '.code, allowAppend = true))
        assertFalse(CompositionInput.shouldBlockOverLimit(atLimit, 'b'.code, allowAppend = false))
    }

    @Test
    fun tokenSetRewriteDecodesUncoveredTailWithoutLeakingRawHandles() {
        val bindings = mapOf(
            'A' to listOf("g", "h", "i"),
            'B' to listOf("m", "n", "o"),
            'C' to listOf("k", "l"),
            'D' to listOf("ng"),
            'E' to listOf("a", "b", "c"),
            'F' to listOf("d", "e", "f"),
        )
        TwelveKeyPreedit.updateTokenBindings(bindings)
        try {
            // ABCBA -> go loi (Candidate 0 = 过来), tail DEFAB -> nga di m (not raw "DEFAB")
            val rendered = TwelveKeyPreedit.rewrite(
                preedit = "go loiDEFAB‸",
                hint = "过来",
                preferred = "go loi",
                rawInput = "ABCBADEFAB",
            )!!
            assertTrue("Expected go loi prefix: $rendered", rendered.startsWith("go loi "))
            assertFalse("Raw handle letters must not leak into preedit: $rendered", rendered.any { it in 'A'..'Z' })
            assertTrue("Decoded tail should contain decoded syllable/letter: $rendered", rendered.contains("nga"))
        } finally {
            TwelveKeyPreedit.updateTokenBindings(emptyMap())
        }
    }
}
