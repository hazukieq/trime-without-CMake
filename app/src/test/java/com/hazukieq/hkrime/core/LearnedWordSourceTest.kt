package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.PhraseMemory
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 驗證 PhraseMemory 學習詞同時供候選條與面板使用。 */
class LearnedWordSourceTest {

    companion object {
        private lateinit var preedit: TwelveKeyPreedit

        @BeforeClass
        @JvmStatic
        fun load() {
            preedit = TwelveKeyPreedit
            preedit.clearWordDataForTest()
            val dict = File("src/main/assets/rime/hakka.dict.yaml").readLines()
            val counts = HashMap<String, Int>()
            dict.forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[1].isNotBlank()) {
                    counts[parts[1]] = (counts[parts[1]] ?: 0) + 1
                }
            }
            preedit.loadDictLines(dict.asSequence().filter { it.contains('\t') })
            preedit.finishLoading(counts)
            listOf(
                "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords",
                "hakka_places", "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
            ).forEach { name ->
                preedit.loadWordLines(
                    File("src/main/assets/rime/$name.dict.yaml").readLines().asSequence()
                )
            }
            preedit.finishWordIndex()
            // 模擬用戶點選過 B站 之後的 PhraseMemory（typed=1 + picked=1 ≥ MIN_COUNT=2）
            PhraseMemory.putForTest(
                "aaa4",
                listOf(
                    PhraseMemory.Entry(
                        text = "B站",
                        readings = "bi cam",
                        typed = 1,
                        lastUsedAt = System.currentTimeMillis(),
                        shown = 0,
                        picked = 1,
                    ),
                ),
            )
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            PhraseMemory.clear()
        }
    }

    @Test
    fun learnedWordJoinsAssocIndex() {
        val bySecond = preedit.assocSecondMap("bi")
        assertTrue("學習詞的 bi cam 應進聯想索引", bySecond["cam"]?.contains("B站") == true)
    }

    @Test
    fun learnedWordAppearsInPanelRow() {
        // `aaa4` = abc abc abc mno = 簡拼 b + 完整音節 cam（cam/ban/can 都是 aa4）
        val local = TwelveKeySnapshotBuilder.Local(
            input = "aaa4",
            consumedEnd = 0,
            remaining = "aaa4",
            segmentsKey = "",
        )
        val snapshot = TwelveKeySnapshotBuilder.build(
            local = local,
            applyLearning = false,
            wantGroups = true,
            candidatesOverride = emptyList(),
        )
        val row = snapshot.groups.firstOrNull { it.reading == "bi cam" }
        assertNotNull("面板應有 bi cam 行，實際 ${snapshot.groups.map { it.reading }}", row)
        assertTrue("bi cam 行應含 B站", row!!.items.any { it.text == "B站" })
        assertTrue("推薦組也應帶上 B站", snapshot.recommend.any { it.text == "B站" })
    }
}
