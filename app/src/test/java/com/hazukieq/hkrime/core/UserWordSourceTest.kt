package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 用戶詞庫（userdb 同步快照）納入面板匹配（用戶 2026-11-11）。
 *
 * 回歸點：`aaa4`（簡拼 b + 完整 cam）面板應出現 `bi cam` 行；`B站` 只在用戶詞庫裡
 * （`bi cam B站`），資產詞表沒有，所以必須靠 [TwelveKeyPreedit.loadUserWordLines] 進來。
 */
class UserWordSourceTest {

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
            preedit.loadUserWordLines(
                sequenceOf(
                    "bi cam \tB站\tc=7 d=2.68 t=787137",
                    // 音節集合外（`xing`）整條丟棄，不能污染面板
                    "li xing \t例行\tc=1",
                    // 單音節用戶詞不進聯想索引（面板單字組由段組負責）
                    "bi \t碑\tc=1",
                ),
            )
            preedit.finishWordIndex()
        }
    }

    @Test
    fun userWordJoinsAssocIndex() {
        val bySecond = preedit.assocSecondMap("bi")
        assertTrue("用戶詞庫的 bi cam 應進聯想索引", bySecond["cam"]?.contains("B站") == true)
        assertTrue("音節集合外的讀音應被丟棄", bySecond.keys.none { it == "xing" })
    }

    @Test
    fun hybridComboLandsOnUserWordRow() {
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
        assertNotNull(
            "面板應有 bi cam 行，實際 ${snapshot.groups.map { it.reading }}",
            row,
        )
        assertTrue("bi cam 行應含 B站", row!!.items.any { it.text == "B站" })
        assertTrue(
            "推薦組也應帶上 B站",
            snapshot.recommend.any { it.text == "B站" },
        )
    }
}
