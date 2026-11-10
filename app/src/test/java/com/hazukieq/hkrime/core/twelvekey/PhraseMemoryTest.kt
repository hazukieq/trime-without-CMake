package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App 側組詞記憶（docs/10 §15.64）與合成候選混排的純邏輯測試。 */
class PhraseMemoryTest {

    private val day = 24L * 3600 * 1000

    private fun entry(text: String, count: Int, ageDays: Long, readings: String = "a b") =
        PhraseMemory.Entry(
            text = text,
            readings = readings,
            count = count,
            lastUsedAt = 1000L * day - ageDays * day,
        )

    @Test
    fun `normalizeCode drops apostrophes and spaces and lowercases`() {
        assertEquals("8i8e4", PhraseMemory.normalizeCode("8i'8 e4"))
        assertEquals("5i34ie4ie9", PhraseMemory.normalizeCode("5i34i'e4'ie9"))
        assertEquals("", PhraseMemory.normalizeCode(" ' "))
    }

    @Test
    fun `first record is not visible second one is`() {
        val now = 2000L * day
        var entries = PhraseMemory.applyRecord(emptyList(), "以液難", "ji jek nan", now)
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].count)
        assertEquals(0, PhraseMemory.visibleEntries(entries, now).size)

        entries = PhraseMemory.applyRecord(entries, "以液難", "ji jek nan", now)
        assertEquals(2, entries[0].count)
        val visible = PhraseMemory.visibleEntries(entries, now)
        assertEquals(1, visible.size)
        assertEquals("以液難", visible[0].text)
    }

    @Test
    fun `single character records are ignored`() {
        val now = 2000L * day
        val entries = PhraseMemory.applyRecord(emptyList(), "以", "ji", now)
        assertEquals(0, entries.size)
    }

    @Test
    fun `score decays with age`() {
        val now = 2000L * day
        val fresh = PhraseMemory.score(entry("以液", 3, 0), now)
        val week = PhraseMemory.score(entry("以液", 3, 7), now)
        val month = PhraseMemory.score(entry("以液", 3, 30), now)
        assertTrue(fresh > week)
        assertTrue(week > month)
        // 半衰期 7 天：一週後大約剩一半
        assertTrue(week in (fresh * 0.45)..(fresh * 0.55))
    }

    @Test
    fun `same code keeps at most MAX_PER_CODE entries`() {
        val now = 2000L * day
        var entries = emptyList<PhraseMemory.Entry>()
        repeat(PhraseMemory.MAX_PER_CODE + 3) { i ->
            entries = PhraseMemory.applyRecord(entries, "詞$i", "ci", now + i)
        }
        assertTrue(entries.size <= PhraseMemory.MAX_PER_CODE)
    }

    @Test
    fun `encode and decode roundtrip and tolerate garbage`() {
        val now = 2000L * day
        val entries = listOf(entry("以液難", 3, 1, "ji jek nan"), entry("以液", 2, 2))
        val decoded = PhraseMemory.decode(PhraseMemory.encode(entries))
        assertEquals(entries, decoded)
        assertTrue(PhraseMemory.decode("garbage\n\n1\u0001\u0001x\u0001y").isEmpty())
    }

    // ---------- 合成候選混排 ----------

    private fun item(text: String, comment: String, index: Int) =
        CandidateItem(text = text, comment = comment, index = index)

    private fun synthetic(text: String, comment: String, code: String) =
        CandidateItem(
            text = text,
            comment = comment,
            index = -1,
            segment = SYNTHETIC_SEGMENT,
            restCode = code,
        )

    @Test
    fun `merge keeps engine order when there is no synthetic candidate`() {
        val ranked = listOf(item("醫院", "ji jen", 0), item("這種", "je zung", 1))
        assertEquals(ranked, CandidateRanking.mergePhraseCandidates(ranked, emptyList(), { 0.0 }, { 1.0 }))
    }

    @Test
    fun `high score synthetic candidate goes before engine candidates`() {
        val ranked = listOf(item("醫院", "ji jen", 0), item("這種", "je zung", 1), item("以", "ji", 2))
        val merged = CandidateRanking.mergePhraseCandidates(
            ranked = ranked,
            synthetic = listOf(synthetic("以液難", "ji jek nan", "8i8e4")),
            syntheticScore = { 5.0 },
            engineScore = { i -> 1.0 - i.toDouble() / ranked.size },
        )
        assertEquals("以液難", merged.first().text)
        assertEquals(4, merged.size)
    }

    @Test
    fun `low score synthetic candidate sinks behind engine candidates`() {
        val ranked = listOf(item("醫院", "ji jen", 0), item("這種", "je zung", 1))
        val merged = CandidateRanking.mergePhraseCandidates(
            ranked = ranked,
            synthetic = listOf(synthetic("以液難", "ji jek nan", "8i8e4")),
            syntheticScore = { 0.1 },
            engineScore = { i -> 1.0 - i.toDouble() / ranked.size },
        )
        // 0.1 < 引擎第 0 條(1.0)、第 1 條(0.5) → 排最後
        assertEquals(listOf("醫院", "這種", "以液難"), merged.map { it.text })
    }

    @Test
    fun `tie goes to engine candidate`() {
        val ranked = listOf(item("醫院", "ji jen", 0))
        val merged = CandidateRanking.mergePhraseCandidates(
            ranked = ranked,
            synthetic = listOf(synthetic("以液難", "ji jek nan", "8i8e4")),
            syntheticScore = { 1.0 },
            engineScore = { 1.0 },
        )
        assertEquals(listOf("醫院", "以液難"), merged.map { it.text })
    }

    @Test
    fun `synthetic candidates do not break the word slots of the bar`() {
        // 合成候選也是「詞條」：詞區仍然受 BAR_WORD_SLOTS 約束
        val words = listOf("醫院", "這種", "一樣", "由於", "作者", "預約")
        val ranked = words.mapIndexed { i, w -> item(w, "ci", i) }
        val synthetics = listOf("以液難", "以液", "液難").map { synthetic(it, "ji jek nan", "8i8e4") }
        val merged = CandidateRanking.mergePhraseCandidates(
            ranked = ranked,
            synthetic = synthetics,
            syntheticScore = { 9.0 },
            engineScore = { i -> 1.0 - i.toDouble() / ranked.size },
        )
        val bar = CandidateRanking.rankBar(merged + listOf(item("以", "ji", 99)), barSize = 12)
        assertEquals(TwelveKeyTuning.BAR_WORD_SLOTS, bar.count { it.text.length > 1 })
    }

    // ---------- 快照構造：記憶 → 合成候選 → 混排 ----------

    @Test
    fun `mergeLearnedPhrases returns ranked unchanged without learned entries`() {
        PhraseMemory.clear()
        val ranked = listOf(item("醫院", "ji jen", 0), item("這種", "je zung", 1))
        assertEquals(ranked, TwelveKeySnapshotBuilder.mergeLearnedPhrases(ranked, "8i8e4", 2000L * day))
    }

    @Test
    fun `mergeLearnedPhrases inserts learned phrase with its readings and code`() {
        PhraseMemory.clear()
        val now = 2000L * day
        PhraseMemory.putForTest(
            "8i8e4",
            listOf(PhraseMemory.Entry("以液難", "ji jek nan", 3, now)),
        )
        val ranked = listOf(item("醫院", "ji jen", 0), item("這種", "je zung", 1))
        val merged = TwelveKeySnapshotBuilder.mergeLearnedPhrases(ranked, "8i8e4", now)
        val syntheticItem = merged.first { it.segment == SYNTHETIC_SEGMENT }
        assertEquals("以液難", syntheticItem.text)
        assertEquals("ji jek nan", syntheticItem.comment)  // preedit 靠它
        assertEquals("8i8e4", syntheticItem.restCode)      // 點擊後靠它記學習
        assertEquals(-1, syntheticItem.index)
        // count=3、剛用過 → 學習分 2.0 > 引擎第 0 條 1.0
        assertEquals("以液難", merged.first().text)
        PhraseMemory.clear()
    }

    @Test
    fun `mergeLearnedPhrases hides entries below the count threshold`() {
        PhraseMemory.clear()
        val now = 2000L * day
        PhraseMemory.putForTest("8i8e4", listOf(PhraseMemory.Entry("以液難", "ji jek nan", 1, now)))
        val ranked = listOf(item("醫院", "ji jen", 0))
        assertEquals(ranked, TwelveKeySnapshotBuilder.mergeLearnedPhrases(ranked, "8i8e4", now))
        PhraseMemory.clear()
    }
}
