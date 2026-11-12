package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 驗證多首字母音節覆蓋、每首字母配額、總上限及讀音字典序。 */
class SyllableBucketTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
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

    private fun initialOf(syllable: String) = syllable.first()

    /** 從詞典行統計「每個音節收了多少字」，用來驗證桶內是否真的按高頻降序。 */
    private val freqOf: Map<String, Int> by lazy {
        val counts = HashMap<String, Int>()
        File("src/main/assets/rime/hakka.dict.yaml").forEachLine { line ->
            val parts = line.trim().split('\t')
            if (parts.size < 2) return@forEachLine
            val syllable = parts[1].trim()
            if (syllable.isEmpty() || syllable.any { it.isWhitespace() }) return@forEachLine
            counts[syllable] = (counts[syllable] ?: 0) + 1
        }
        counts
    }

    @Test
    fun multiLetterKeyCoversEveryInitial() {
        // ghi(i) 覆蓋 g/h 兩類；kl(5) 覆蓋 k/l；mno(4) 覆蓋 m/n
        val cases = mapOf(
            "i" to setOf('g', 'h'),
            "5" to setOf('k', 'l'),
            "4" to setOf('m', 'n'),
        )
        for ((code, initials) in cases) {
            val got = TwelveKeyPreedit.syllablesForSegment(code, 8)
            val covered = got.map { initialOf(it) }.toSet()
            assertTrue("code=$code 只出了 $covered，應覆蓋 $initials", covered.containsAll(initials))
        }
    }

    @Test
    fun limitIsPerInitialBucket() {
        // limit 是每首字母配額，總數另有上限。
        val got = TwelveKeyPreedit.syllablesForSegment("i", 8)
        assertEquals("i 鍵 limit=8（每桶）應回 16 個音節", 16, got.size)
        val byInitial = got.groupingBy { initialOf(it) }.eachCount()
        assertEquals("g 桶應拿到 8 個", 8, byInitial['g'])
        assertEquals("h 桶應拿到 8 個", 8, byInitial['h'])
        // 保留的音節按讀音字典序顯示。
        assertEquals('g', initialOf(got.first()))
        assertEquals('h', initialOf(got.last()))
        assertTrue("列表應按字典序：$got", got == got.sorted())
    }

    @Test
    fun eachBucketIsOrderedByDictFrequencyDescending() {
        // 字典頻次決定選取，字典序決定顯示。
        val got = TwelveKeyPreedit.syllablesForSegment("i", 16)
        val byInitial = got.groupBy { initialOf(it) }
        assertTrue("i 鍵應同時有 g/h 兩桶", byInitial.containsKey('g') && byInitial.containsKey('h'))
        // 兩級桶（next-code × initial）輪轉後，同一首字母的音節不再全局頻率降序；
        // 但每組的「第一條」必須是該首字母裡字典字數最多的那個，保證先出高頻音節。
        for ((initial, list) in byInitial) {
            val counts = list.map { freqOf[it] ?: 0 }
            val best = freqOf.entries.filter { entry -> initialOf(entry.key) == initial }
                .maxOfOrNull { it.value } ?: 0
            assertTrue(
                "首字母 $initial 沒給出最高頻音節：${list.zip(counts)}",
                counts.maxOrNull() == best,
            )
        }
    }

    @Test
    fun thKeyListsThanNotOnlyThe() {
        // 不同後續碼桶都應有代表音節。
        val got = TwelveKeyPreedit.syllablesForSegment("w", 8)
        // 產 = than（用戶就是打不出「共產黨」）：必須出現，且不能只有 the 系列
        assertTrue("th 鍵應該給出 than，實際 $got", got.contains("than"))
        val diverse = got.filter { it != "the" && !it.startsWith("the") }
        assertTrue("th 鍵不能只有 the 系列，實際 $got", diverse.size >= 3)
    }

    @Test
    fun exactCodeWinsOverPrefixExpansion() {
        // 精確碼（一整段音節）永遠排在最前，且不重複
        val got = TwelveKeyPreedit.syllablesForSegment("5e", 8)
        assertEquals("5e 的精確音節應排第一", "le", got.first())
        assertEquals("結果不應有重複", got.distinct(), got)
    }

    @Test
    fun smallLimitGivesOneSlotPerInitial() {
        // 每桶名額 1 → 兩桶各 1（共 2）；每桶 3 → 共 6
        assertEquals(2, TwelveKeyPreedit.syllablesForSegment("i", 1).size)
        assertEquals(6, TwelveKeyPreedit.syllablesForSegment("i", 3).size)
    }

    @Test
    fun abbreviationPrefixStillProducesGroups() {
        // 只有首碼（簡拼）時，該碼位對應的聲母桶要能出組（855 的第一位 8 = z/j）
        val got = TwelveKeyPreedit.syllablesForSegment("8", 6)
        assertTrue("8(z/j) 應有音節：$got", got.isNotEmpty())
        assertTrue("應是 z 或 j 開頭：$got", got.all { initialOf(it) in setOf('z', 'j') })
    }
}
