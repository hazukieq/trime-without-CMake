package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue("必須是 2~4 字詞：$words", words.all { it.length in 2..4 })
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
    fun mixedCodeMatrixKeepsAssociationRows() {
        // 12 鍵覆蓋矩陣（用戶 2026-11-10：「你的測試用例還有問題，12鍵根本沒有覆蓋完全」）：
        // 每個混合碼都要能出「第一段音節組 + 至少一行聯想詞」。
        val cases = mapOf(
            "5i34iwe" to "kiung ",     // kiung + th + d：th/def 合成 the
            "744ii" to "song ",        // song + i：引擎會切成 son + gi
            "5i34iw" to "kiung ",
            "i44yi" to null,           // gong + h：只剩前綴，不能崩
        )
        for ((codes, prefix) in cases) {
            val groups = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
                .debugSegmentGroups(codes)
            val readings = groups.map { it.reading }
            println("PROBE $codes -> $readings")
            assertTrue("[$codes] 應至少有單字組，實際 $readings", groups.any { it.segment == 0 })
            if (prefix != null) {
                assertTrue(
                    "[$codes] 應有以「$prefix」開頭的聯想詞行，實際 $readings",
                    readings.any { it.startsWith(prefix) && it != prefix.trim() },
                )
                val row = groups.first { it.reading.startsWith(prefix) && it.reading != prefix.trim() }
                assertTrue("[$codes] 聯想詞行不能是空的", row.items.isNotEmpty())
            }
        }
    }

    @Test
    fun associatedBySegmentCodeUsesExactSyllables() {
        // 用戶 2026-11-10：行標題必須是兩個完整讀音（`than dung`），不能是 `than d`。
        val rows = preedit.associatedBySegmentCode("than", "e", perRow = 6, maxRows = 2)
        assertTrue("應有行，實際 $rows", rows.isNotEmpty())
        for ((reading, words) in rows) {
            val second = reading.substringAfter(' ')
            assertTrue("第二段必須是完整音節（不是單字母）：$reading", second.length >= 2)
            assertTrue("詞數應 ≤6，實際 ${words.size}", words.size <= 6)
        }
        assertTrue("行數應 ≤2，實際 ${rows.size}", rows.size <= 2)
    }

    @Test
    fun kiungPlusTheStillEmitsAssociationRows() {
        // 用戶 2026-11-10：「kiung th d —— th 和 def 合成 the，導致什麼聯想詞都沒有」。
        // 剩餘碼 `we` 剛好是一個完整音節（洗=the），面板仍必須出「kiung than」這類聯想詞行。
        val groups = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
            .debugSegmentGroups("5i34iwe")
        val readings = groups.map { it.reading }
        println("PROBE groups=$readings")
        assertTrue("應有 kiung than 行，實際 $readings", readings.any { it == "kiung than" })
        val thanGroup = groups.first { it.reading == "kiung than" }
        assertTrue(
            "kiung than 行應含共產黨，實際 ${thanGroup.items.map { it.text }}",
            thanGroup.items.any { it.text.contains("共产党") },
        )
    }

    @Test
    fun kiungFollowedByThanListsCommunistParty() {
        // 用戶 2026-11-10：打 kiung + th 想選「共產黨」三個字，但面板既沒有 than 音節、
        // 也沒有 kiung than 這行聯想詞。這裡釘住：索引要收 3~4 字詞，並按 (第一音節, 第二音節) 命中。
        val rows = preedit.associatedByReading("kiung", setOf('t'), limitPerReading = 6, maxReadings = 6)
        val thanRow = rows.firstOrNull { it.first == "than" }
        assertTrue("應有 kiung than 行，實際 ${rows.map { it.first }}", thanRow != null)
        val words = thanRow!!.second
        assertTrue("kiung than 應含共產黨，實際 $words", words.any { it.contains("共产党") || it.contains("共產黨") })
        // 行截斷必須按熱度、不能按字母序：僅取 2 行時也要保留 than（3 字詞都在這裡）
        val top2 = preedit.associatedByReading("kiung", setOf('t'), 6, 2)
        assertTrue("取 2 行時應保留 than，實際 ${top2.map { it.first }}", top2.any { it.first == "than" })
    }

    @Test
    fun associatedByReadingUsesExactSecondSyllable() {
        val rows = preedit.associatedByReading("song", setOf('h'), limitPerReading = 6, maxReadings = 4)
        assertTrue("song + h 開頭應有讀音行，實際 ${rows.size}", rows.isNotEmpty())
        assertTrue("最多 4 行，實際 ${rows.size}", rows.size <= 4)
        for ((second, words) in rows) {
            assertTrue("第二音節必須是 h 開頭：$second", second.startsWith("h"))
            assertTrue("每行最多 6 條", words.size <= 6)
            assertTrue("必須是 2~4 字詞：$words", words.all { it.length in 2..4 })
        }
    }

    @Test
    fun associatedByReadingHonoursInitialFilter() {
        val onlyG = preedit.associatedByReading("song", setOf('g'), maxReadings = 8)
        assertTrue("song + g 應有讀音行", onlyG.isNotEmpty())
        assertTrue("只保留 g 開頭", onlyG.all { it.first.startsWith("g") })
        assertTrue("空 initials 應返回空", preedit.associatedByReading("song", emptySet()).isEmpty())
    }

    @Test
    fun initialsRespectLimit() {
        assertEquals(1, preedit.initialsForPrefix("i", limit = 1).size)
        assertFalse(preedit.initialsForPrefix("", limit = 2).isNotEmpty())
    }

    // ---- preedit 讀音對齊（docs/14）----

    /** 候選條第一條有 comment 時，preedit 必須用它的精確讀音（`kiung than` 不是 `kiung the`）。 */
    @Test
    fun rewriteUsesFirstCandidateReading() {
        assertEquals("kiung than", preedit.rewrite("5i34i w\u2038", "共产", "kiung than"))
    }

    /**
     * 首條是**多字詞**、讀音段數與本地解碼不同：`5i34i` 本地切出 1 段 kiung，
     * 但首條「理會」讀 `li voi`（2 段，5i|34i 正好吃完 5i34i）—— preedit 要顯示 `li voi`
     * （用戶 2026-11-10 指出：只看段數相等的舊規則會把這種最常見的情況擋掉）。
     */
    @Test
    fun rewriteUsesWordReadingWhenFirstCandidateIsAWord() {
        assertEquals("li voi", preedit.rewrite("5i34i\u2038", "理会", "li voi"))
    }

    /** 同上但碼短一段：`5i34` 的首條「梨渦」讀 `li vo`。 */
    @Test
    fun rewriteUsesWordReadingForShorterCode() {
        assertEquals("li vo", preedit.rewrite("5i34\u2038", "梨涡", "li vo"))
    }

    /** 首條是**單字**時讀音 1 段，同樣要對齊。 */
    @Test
    fun rewriteUsesSingleCharReading() {
        assertEquals("kiung", preedit.rewrite("5i34i\u2038", "共", "kiung"))
    }

    /** 首條是 3 字詞、讀音 3 段（共產黨 = kiung than dong 正好吃完 5i34iwe）。 */
    @Test
    fun rewriteUsesThreeSyllableWordReading() {
        assertEquals("kiung than dong", preedit.rewrite("5i34iwe", "共產黨", "kiung than dong"))
    }

    /** librime 的 `‸`(U+2038) 光標標記不能被當成「已選前綴」，要剝掉再切碼段。 */
    @Test
    fun rewriteStripsCaretMarker() {
        assertEquals("kiung th", preedit.rewrite("5i34iw\u2038", "共产", null))
    }

    /** 半截碼（尾端 `w` 還不是完整音節）也要翻成鍵盤字母；空格分隔的半截碼同理。 */
    @Test
    fun rewriteMapsPartialTailCodeToLetters() {
        assertEquals("kiung th", preedit.rewrite("5i34i w", "共产", null))
    }

    /**
     * 12 鍵多對多、沒有唯一解碼：comment 一律優先（它與候選條同源，是「用戶看到的第一條」的讀音）。
     * 這裡用一串「本地解碼完全對不上」的 comment 釘住契約：不做本地校驗，直接顯示。
     */
    @Test
    fun rewriteTrustsFirstCandidateCommentAsIs() {
        assertEquals("li voi", preedit.rewrite("5i34iw", "理會", "li voi"))
    }

    /** 引擎沒給 comment（方案沒開 spelling_hints）時退回本地解碼。 */
    @Test
    fun rewriteFallsBackToOwnDecodeWithoutComment() {
        assertEquals("kiung th", preedit.rewrite("5i34iw", "共产", null))
    }

    /** comment 可能被包成 `［…］`（comment_format 的 xform），要剝掉再顯示。 */
    @Test
    fun rewriteStripsBracketWrappedComment() {
        assertEquals("kiung than", preedit.rewrite("5i34i w\u2038", "共产", "［kiung than］"))
    }

    /** 已選中的前綴（咧）必須保留，只改寫尾部碼段。 */
    @Test
    fun rewriteKeepsSelectedPrefix() {
        assertEquals("咧le", preedit.rewrite("咧5e", "咧", null))
    }

    /** 尾端沒有碼段（已全部選完）時回傳 null，保持 librime 原樣。 */
    @Test
    fun rewriteReturnsNullWhenNoCode() {
        assertNull(preedit.rewrite("咧", null, null))
    }
}
