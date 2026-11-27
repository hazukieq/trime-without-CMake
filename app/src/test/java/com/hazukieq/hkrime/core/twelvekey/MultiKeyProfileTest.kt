package com.hazukieq.hkrime.core.twelvekey

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiKeyProfileTest {

    @After
    fun tearDown() {
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
}
