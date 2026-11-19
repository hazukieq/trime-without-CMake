package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class CandidateSelectionTest {
    companion object { @JvmStatic @BeforeClass fun load() { OfflineDictionary.load() } }
    @Test fun allOriginsShareTheSamePartialBoundary() {
        CompositionSession.Origin.values().filter { it != CompositionSession.Origin.FALLBACK }.forEach { origin ->
            val item = CandidateItem("太", "tai", 0)
            assertEquals(3, CandidateSelection.consumedEnd(item, "9aii48i5i73", origin))
        }
    }
    @Test fun unknownReadingIsUnresolvedRatherThanComplete() {
        assertNull(CandidateSelection.consumedEnd(CandidateItem("未知", "", 0), "55", CompositionSession.Origin.BAR))
        assertNull(CandidateSelection.consumedEnd(CandidateItem("未知", "???", -1, segment = 1), "i4", CompositionSession.Origin.PANEL))
        assertFalse(CandidateSelection.isCurrent("", ""))
        assertFalse(CandidateSelection.isCurrent("old", "new"))
        val before = CandidateSelection.contextKey("same input", 1, 0)
        assertFalse(CandidateSelection.isCurrent(before, CandidateSelection.contextKey("same input", 2, 0)))
        assertFalse(CandidateSelection.isCurrent(before, CandidateSelection.contextKey("same input", 1, 1)))
    }
    @Test fun explicitDictionarySplitAndIsolatedConsumeOnlyTheirOwnSpan() {
        val dictionary = CandidateItem("哥", "go", -1, segment = 0, restCode = "8i5i73")
        assertEquals(2, CandidateSelection.consumedEnd(dictionary, "i48i5i73", CompositionSession.Origin.PANEL))
        assertNull(CandidateSelection.consumedEnd(dictionary.copy(restCode = "wrong"), "i48i5i73", CompositionSession.Origin.PANEL))
        val isolated = CandidateItem("客", "ka", -1, segment = ISOLATED_CHAR_SEGMENT)
        assertEquals(1, CandidateSelection.consumedEnd(isolated, "55", CompositionSession.Origin.ISOLATED))
    }
    @Test fun abbreviationAliasesAndPartialTailKeepUnselectedCode() {
        assertEquals(1, TwelveKeyPreedit.selectedInputEnd("ka", "55"))
        assertEquals(3, TwelveKeyPreedit.selectedInputEnd("tai", "9ai55"))
        assertEquals(12, TwelveKeyPreedit.selectedInputEnd("lin gi jit dung", "5i4ii8i9934i54iei9"))
        assertEquals(3, TwelveKeyPreedit.selectedInputEnd("tai", "9ai'i4"))
    }
    @Test fun everyRuntimeEncodedPairKeepsItsUnselectedSecondCode() {
        val readings = OfflineDictionary.runtimeSyllables.filter { it != "r" }
            .associateBy { TwelveKeyPreedit.codeOf(it) }
        val lastCodes = OfflineDictionary.runtimeSyllables.map { TwelveKeyPreedit.codeOf(it) }.toSet()
        var checked = 0
        readings.forEach { (first, reading) ->
            lastCodes.forEach { last ->
                assertEquals("$reading / $last", first.length,
                    TwelveKeyPreedit.selectedInputEnd(reading, first + last))
                checked++
            }
        }
        println("SELECTION_PAIR_BOUNDARIES=$checked")
        assertTrue(checked > 180000)
    }
    @Test fun fallbackRequiresAnExplicitConfirmationOfTheEntireCode() {
        val original = CandidateItem("klkl", "", -1, restCode = "55")
        assertEquals(2, CandidateSelection.consumedEnd(original, "55", CompositionSession.Origin.FALLBACK))
        assertNull(CandidateSelection.consumedEnd(original, "555", CompositionSession.Origin.FALLBACK))
    }

    @Test fun punctuationInputIsRoutedToTheEngineNotTheCodeJournal() {
        // 12 鍵的 "/" 在引擎裡是標點候選（、/／/÷），沒有可證實的音節碼位；
        // 這種輸入必須交引擎直選提交，不能套用 selectedInputEnd 的碼位解析。
        assertTrue(CompositionInput.isCodeString("9aii48i5i73"))
        assertTrue(CompositionInput.isCodeString("9ai' i4"))
        for (input in listOf("/", "、", "，", "÷", "", "9ai/")) {
            assertFalse("input=$input", CompositionInput.isCodeString(input))
        }
        assertNull(CandidateSelection.consumedEnd(CandidateItem("、", "全角", 0), "/", CompositionSession.Origin.BAR))
        assertNull(CandidateSelection.consumedEnd(CandidateItem("/", "半角", 2), "/", CompositionSession.Origin.BAR))
    }
}
