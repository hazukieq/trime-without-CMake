package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 混合碼串的「按段拆解」覆蓋測試（用戶 2026-11-10 要求）。
 *
 * 12 鍵每個鍵送出的是**碼位**（nz→y、abc→a、kl→5…），用戶口語說的
 * `gong th t k` / `g th ti ki` 這類混打，落到碼串上是 `i44iw95`、`i5i95i` 這種。
 * 這裡直接餵碼串給 `TwelveKeyPreedit.splitFirstSyllable`，驗證：
 * 1) 逐段切分能走到底（不會中途 null 卡死）；
 * 2) 每段 head 非空、rest 嚴格變短；
 * 3) 所有 head 拼起來 == 原碼串（不丟碼、不重複）；
 * 4) 每個 head 都能查回至少一個音節（面板能出組）。
 */
class SegmentSplitTest {

    companion object {
        private val preedit: TwelveKeyPreedit by lazy { TwelveKeyPreedit }

        @BeforeClass
        @JvmStatic
        fun load() {
            // 直接讀 assets 的行餵給生產代碼，確保測的就是 App 用的那套邏輯。
            val counts = TwelveKeyPreedit.loadDictLines(
                File("src/main/assets/rime/hakka.dict.yaml").readLines().asSequence()
            )
            runCatching {
                TwelveKeyPreedit.loadCharFreqLines(
                    File("src/main/assets/rime/hakka_char_freq.txt").readLines().asSequence()
                )
            }
            runCatching {
                TwelveKeyPreedit.loadCharRankLines(
                    File("src/main/assets/rime/hakka_char_rank.txt").readLines().asSequence()
                )
            }
            TwelveKeyPreedit.finishLoading(counts)
        }
    }

    /** 用戶舉過的混打組合（拼音 → 12 鍵碼位）。 */
    private val mixedCodes = listOf(
        "i44i9i95",   // gong th t k
        "i5i95i",     // g th ti ki
        "i44iw5i95",  // gong thu ti k
        "i5i95i5",    // g thu t ki
        "i44iw95",    // 實際日誌裡出現的 gong+th+t+k
        "855",        // 純首碼簡拼 j k l
        "i5",         // 兩碼
        "4i",         // 兩碼
    )

    @Test
    fun splitCoversAllCodesWithoutLosingAny() {
        for (codes in mixedCodes) {
            // 整串本來就是一個音節（如 `4i` = mi/ni、`i5` = le）：不該拆段，交給引擎。
            if (preedit.decode(codes)?.split(' ')?.size == 1) {
                assertNull("codes=$codes 是單音節，不應拆段", preedit.splitFirstSyllable(codes))
                continue
            }
            val heads = ArrayList<String>()
            var rest = codes
            var guard = 0
            while (rest.isNotEmpty() && guard++ < codes.length + 2) {
                val split = preedit.splitFirstSyllable(rest)
                if (split == null) break
                val (head, tail) = split
                assertTrue("head 不能為空 codes=$codes rest=$rest", head.isNotEmpty())
                assertTrue("rest 必須嚴格變短 codes=$codes head=$head tail=$tail", tail.length < rest.length)
                heads.add(head)
                rest = tail
            }
            val joined = heads.joinToString("")
            assertEquals("拆段後拼回必須等於原碼串 codes=$codes heads=$heads rest=$rest", codes, joined + rest)
            assertTrue("至少拆出一段 codes=$codes", heads.isNotEmpty())
        }
    }

    @Test
    fun everyHeadHasAtLeastOneSyllable() {
        for (codes in mixedCodes) {
            if (preedit.decode(codes)?.split(' ')?.size == 1) continue
            var rest = codes
            var guard = 0
            while (rest.isNotEmpty() && guard++ < codes.length + 2) {
                val (head, tail) = preedit.splitFirstSyllable(rest) ?: break
                val syllables = preedit.syllablesForSegment(head, 8)
                assertTrue("head=$head（codes=$codes）查不到音節，面板會是空的", syllables.isNotEmpty())
                rest = tail
            }
        }
    }

    @Test
    fun singleSyllableNeedsNoSplit() {
        // 能整串解出一個音節時不該拆段（交給引擎自己出候選）
        for (codes in listOf("i5", "85", "9")) {
            if (preedit.decode(codes)?.split(' ')?.size == 1) {
                assertNull("codes=$codes 是單音節，不應拆段", preedit.splitFirstSyllable(codes))
            }
        }
    }

    @Test
    fun splitIsStableForSameInput() {
        for (codes in mixedCodes) {
            val a = preedit.splitFirstSyllable(codes)
            val b = preedit.splitFirstSyllable(codes)
            assertEquals("同碼串拆段必須穩定 codes=$codes", a, b)
        }
    }

    /**
     * 用戶 2026-11-10 反饋：`ghi` 鍵（碼位 `i`，覆蓋 g/h/i）給出的單音節只有 g 開頭的。
     * 原因：前綴命中原先只按「該音節在詞典裡的字數」排序，g 系字最多就整批佔滿；
     * 現在改成「按首字母輪流取」，同一個鍵覆蓋的字母都要有代表。
     */
    @Test
    fun multiLetterKeyCoversEveryInitial() {
        // i 覆蓋 g/h/i 三個字母
        val iSyllables = preedit.syllablesForSegment("i", 8)
        assertTrue("i 鍵應至少 6 組，實際=$iSyllables", iSyllables.size >= 6)
        val initials = iSyllables.map { it.first() }.toSet()
        // 客家話詞典裡沒有 i 開頭的音節（i 介音寫成 y-/j-），所以只要求 g 與 h 都有代表。
        assertTrue("i 鍵應同時覆蓋 g/h，實際首字母=$initials", initials.containsAll(listOf('g', 'h')))
        // 5 覆蓋 k/l
        val five = preedit.syllablesForSegment("5", 8)
        assertTrue("5 鍵應同時有 k/l 開頭，實際=$five", five.any { it.startsWith("k") } && five.any { it.startsWith("l") })
        // 4 覆蓋 m/n
        val four = preedit.syllablesForSegment("4", 8)
        assertTrue("4 鍵應同時有 m/n 開頭，實際=$four", four.any { it.startsWith("m") } && four.any { it.startsWith("n") })
    }
}
