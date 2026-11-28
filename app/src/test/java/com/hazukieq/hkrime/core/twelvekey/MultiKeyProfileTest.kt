package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiKeyProfileTest {

    @After
    fun tearDown() {
        TwelveKeyPreedit.updateTokenBindings(emptyMap())
        MultiKeyProfile.active = MultiKeyProfile.Hakka12Key
    }

    @Test
    fun mandarin9KeyPartialSelectionKeepsRemainingBaWithoutEarlyCommit() {
        MultiKeyProfile.active = MultiKeyProfile.Mandarin9Key
        val fullCode = "82453725322" // tai(824) ke(53) pa(72) le(53) ba(22)

        val end = CandidateInputBoundary.selectedInputEnd("tai ke pa le", fullCode)
        assertEquals(9, end)
        assertFalse(CompositionInput.coversFullCode(fullCode, end!!))
        assertEquals("22", CandidateInputBoundary.remainingCodeAfter("tai ke pa le", fullCode))

        // 確認完整串覆蓋
        val fullEnd = CandidateInputBoundary.selectedInputEnd("tai ke pa le ba", fullCode)
        assertEquals(11, fullEnd)
        assertTrue(CompositionInput.coversFullCode(fullCode, fullEnd!!))
    }

    @Test
    fun mandarin9KeyConfirmPhoneticAdvancesLeftRailStepByStep() {
        MultiKeyProfile.active = MultiKeyProfile.Mandarin9Key
        val fullCode = "82453725322"

        val step0 = TwelveKeySnapshotBuilder.confirmGroups(fullCode, emptyList()).map { it.reading }
        assertTrue("tai should appear in step 0: $step0", "tai" in step0)
        assertTrue("t should appear in step 0: $step0", "t" in step0)
        assertFalse("u must not appear as Mandarin initial", "u" in step0)

        val first = PhoneticSelection.resolve("tai", fullCode, initialOnly = false)!!
        assertEquals("824", first.code)
        assertEquals("53725322", PhoneticSelection.pending(fullCode, listOf(first)))

        val step1 = TwelveKeySnapshotBuilder.confirmGroups(fullCode, listOf(first)).map { it.reading }
        assertTrue("ke should appear after confirming tai: $step1", "ke" in step1)
        assertTrue("le should appear after confirming tai: $step1", "le" in step1)
        assertTrue("k should appear after confirming tai: $step1", "k" in step1)

        val second = PhoneticSelection.resolve("ke", "53725322", initialOnly = false)!!
        assertEquals("53", second.code)
        assertEquals("725322", PhoneticSelection.pending(fullCode, listOf(first, second)))

        val step2 = TwelveKeySnapshotBuilder.confirmGroups(fullCode, listOf(first, second)).map { it.reading }
        assertTrue("pa should appear after confirming tai+ke: $step2", "pa" in step2)
    }

    @Test
    fun mandarin9KeyWxyzMnoProducesOnlyValidSyllablesAndComboInitials() {
        MultiKeyProfile.active = MultiKeyProfile.Mandarin9Key
        val code = "96" // wxyz + mno

        assertEquals(listOf("wo", "yo"), MultiKeyProfile.Mandarin9Key.syllablesCompletingPrefix(code))
        assertEquals(listOf("w", "x", "y", "z"), InitialDisambiguation.choices(code))
        assertFalse(MultiKeyProfile.Mandarin9Key.allowLongCodeSynthesis)

        // 簡拼詞「我們」(wo men) 覆蓋完整 96
        assertEquals(2, CandidateInputBoundary.selectedInputEnd("wo men", code))
        assertEquals(2, CandidateInputBoundary.selectedInputEnd("zen me", code))

        // 確認首拼 w 後推進到 6（m/n/o）
        val w = PhoneticSelection.resolve("w", code, initialOnly = true)!!
        assertEquals("9", w.code)
        assertEquals("6", PhoneticSelection.pending(code, listOf(w)))
        assertEquals(listOf("m", "n", "o"), InitialDisambiguation.choices("6"))
        assertEquals("~w", PhoneticSelection.constraint(listOf(w)))
    }

    @Test
    fun hakka12KeyDynamicTokenBindingsDecoupleAppFromManualKeyEncodings() {
        OfflineDictionary.load()
        MultiKeyProfile.active = MultiKeyProfile.Hakka12Key
        // 模擬運行時從 librime Context::GetTokenBindings() 讀到的動態句柄（無需 'w'/'5'/'1' 佔位碼）
        TwelveKeyPreedit.updateTokenBindings(
            mapOf(
                'A' to listOf("th"),
                'B' to listOf("a", "b", "c"),
                'C' to listOf("m", "n", "o"),
                'D' to listOf("d", "e", "f"),
                'E' to listOf("v", "u", "r"),
            ),
        )
        val handles = "ABCDEC" // th-a-m d-u-n
        assertTrue(CompositionInput.isCodeString(handles))
        assertEquals("th", MultiKeyProfile.Hakka12Key.keyLetters('A'))
        assertEquals(listOf("th"), MultiKeyProfile.Hakka12Key.keyInitialUnits('A', isLast = false))
        assertEquals("ABC", TwelveKeyPreedit.codeOf("tham"))
        assertEquals("ABCDEC", TwelveKeyPreedit.codeOf("tham") + TwelveKeyPreedit.codeOf("dun"))
        assertEquals(listOf("tham", "than"), TwelveKeyPreedit.exactSyllables("ABC").filter { it == "than" || it == "tham" }.sorted())

        // 選擇「三」("tham") 後僅保留剩餘句柄 "DEC"，不依賴原始數字/字母占位
        val end = CandidateInputBoundary.selectedInputEnd("tham", handles)
        assertEquals(3, end)
        assertEquals("DEC", CandidateInputBoundary.remainingCodeAfter("tham", handles))

        // 首字簡拼首字母確認 ('A' -> ["th"]) 與長碼兜底字符解析
        assertEquals(listOf("th"), InitialDisambiguation.choices(handles))
        assertEquals("th", MultiKeyProfile.Hakka12Key.fallbackLetter('A'))
        assertEquals("u", MultiKeyProfile.Hakka12Key.fallbackLetter('E'))
    }

    @Test
    fun mandarin9KeyDynamicTokenBindingsDecoupleAppFromDigitEncodings() {
        MultiKeyProfile.active = MultiKeyProfile.Mandarin9Key
        TwelveKeyPreedit.updateTokenBindings(
            mapOf(
                'A' to listOf("t", "u", "v"),
                'B' to listOf("a", "b", "c"),
                'C' to listOf("g", "h", "i"),
                'D' to listOf("j", "k", "l"),
                'E' to listOf("d", "e", "f"),
            ),
        )
        val handles = "ABCDE" // t-a-i k-e
        assertTrue(CompositionInput.isCodeString(handles))
        assertEquals("tuv", MultiKeyProfile.Mandarin9Key.keyLetters('A'))
        assertEquals("ABC", MultiKeyProfile.Mandarin9Key.codeOf("tai"))
        assertEquals("ABCDE", MultiKeyProfile.Mandarin9Key.codeOf("tai") + MultiKeyProfile.Mandarin9Key.codeOf("ke"))
        assertTrue("tai" in MultiKeyProfile.Mandarin9Key.exactSyllables("ABC"))
        assertEquals(listOf("t"), InitialDisambiguation.choices(handles))

        val end = CandidateInputBoundary.selectedInputEnd("tai", handles)
        assertEquals(3, end)
        assertEquals("DE", CandidateInputBoundary.remainingCodeAfter("tai", handles))
        assertEquals(5, CandidateInputBoundary.selectedInputEnd("tai ke", handles))
    }
}
