package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 面板「聯想詞組」行（`song g**` / `song h**`）的數據層測試（用戶 2026-11-10 要求）。
 *
 * 詞表只有詞條、沒有讀音，讀音靠 `hakka.dict.yaml` 的單字首讀音反推；
 * 這裡直接用 assets 的同一份數據驗證索引與查詢語義，避免真機上才發現「一行詞都沒有」。
 */
class AssociatedWordsTest {

    companion object {
        private val preedit: TwelveKeyPreedit by lazy { TwelveKeyPreedit }

        @BeforeClass
        @JvmStatic
        fun load() {
            val counts = TwelveKeyPreedit.loadDictLines(
                File("src/main/assets/rime/hakka.dict.yaml").readLines().asSequence()
            )
            TwelveKeyPreedit.finishLoading(counts)
            listOf(
                "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords",
                "hakka_places", "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
            ).forEach { name ->
                TwelveKeyPreedit.loadWordLines(
                    File("src/main/assets/rime/$name.dict.yaml").readLines().asSequence()
                )
            }
            TwelveKeyPreedit.finishWordIndex()
        }
    }

    @Test
    fun songFollowedByGInitialHasWords() {
        val words = preedit.associatedWords("song", 'g')
        assertTrue("song g** 應該有聯想詞，實際 ${words.size}", words.isNotEmpty())
        assertTrue("每行最多 6 條，實際 ${words.size}", words.size <= 6)
        assertTrue("必須是兩字詞：$words", words.all { it.length == 2 })
    }

    @Test
    fun songFollowedByHInitialHasWords() {
        val words = preedit.associatedWords("song", 'h')
        assertTrue("song h** 應該有聯想詞，實際 ${words.size}", words.isNotEmpty())
        assertTrue("每行最多 6 條", words.size <= 6)
    }

    @Test
    fun limitIsRespected() {
        assertEquals(2, preedit.associatedWords("song", 'g', limit = 2).size.coerceAtMost(2))
        assertTrue(preedit.associatedWords("song", 'g', limit = 1).size <= 1)
    }

    @Test
    fun unknownCombinationReturnsEmpty() {
        assertTrue(preedit.associatedWords("", 'g').isEmpty())
        // 本詞典沒有 x 開頭的音節，所以「song + x**」必然是空
        assertTrue(preedit.associatedWords("song", 'x').isEmpty())
    }

    @Test
    fun initialsForPrefixEnumeratesDistinctInitials() {
        // 碼段 `i` = g/h/i 開頭的音節 → 至少要能看到 g 與 h（用戶 2026-11-10 報過只有 g）。
        val initials = preedit.initialsForPrefix("i", limit = 2)
        assertEquals("i 碼應展開出兩個首字母：$initials", 2, initials.size)
        assertTrue("應包含 g：$initials", initials.contains('g'))
        assertTrue("應包含 h：$initials", initials.contains('h'))
    }

    @Test
    fun initialsRespectLimit() {
        assertEquals(1, preedit.initialsForPrefix("i", limit = 1).size)
        assertFalse(preedit.initialsForPrefix("", limit = 2).isNotEmpty())
    }
}
