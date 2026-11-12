package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendQuotaTest {
    private fun cjk(index: Int): String = (0x4E00 + index).toChar().toString()

    private fun word(len: Int, index: Int): CandidateItem {
        val text = when (len) {
            2 -> "字" + cjk(index)
            3 -> "字" + cjk(index) + "词"
            else -> "字" + cjk(index) + cjk(index + 200) + "词"
        }
        return CandidateItem(text = text, comment = "", index = index)
    }

    private fun words(len: Int, count: Int, base: Int): List<CandidateItem> =
        (0 until count).map { word(len, base + it) }

    @Test
    fun recommendedWordsSplitEvenlyBetweenTwoThreeAndFourPlus() {
        val all = words(2, 15, 0) + words(3, 15, 100) + words(4, 15, 200)
        val picked = TwelveKeySnapshotBuilder.recommendWords(all, emptyList())

        assertEquals(30, picked.size)
        assertEquals(10, picked.count { it.text.length == 2 })
        assertEquals(10, picked.count { it.text.length == 3 })
        assertEquals(10, picked.count { it.text.length == 4 })
    }

    @Test
    fun shortBucketDeficitIsFilledByOtherBuckets() {
        val all = words(2, 5, 0) + words(3, 15, 100) + words(4, 15, 200)
        val picked = TwelveKeySnapshotBuilder.recommendWords(all, emptyList())

        assertEquals(30, picked.size)
        assertEquals(5, picked.count { it.text.length == 2 })
        assertEquals(15, picked.count { it.text.length == 3 })
        assertEquals(10, picked.count { it.text.length == 4 })
    }
}
