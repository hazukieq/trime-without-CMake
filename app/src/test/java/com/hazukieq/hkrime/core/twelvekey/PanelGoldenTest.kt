package com.hazukieq.hkrime.core.twelvekey

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 面板五源 golden 對拍：App 實現 vs `tools/panel_probe.py --data app/src/main/assets/rime json`。
 *
 * 重構後面板選行規則（分桶輪轉、首字母組合、整串詞行）有兩份實現：
 * App（Kotlin）與腳本（Python）。這裡讀**同一份 assets 資料**產生的 golden JSON，
 * 逐源斷言「讀音集合 + 行內文字集合 + 行序」一致、merged 的讀音/檔位順序一致；
 * 任何一邊改規則都會在這裡先炸，而不是等真機面板漂移。
 *
 * 重新生成 golden（改規則/加碼串後必須重跑）：
 * ```bash
 * python3 tools/panel_probe.py --data app/src/main/assets/rime \
 *   json ia ia8 ia8i ia8i3 5i34i 5i34iwe 5i34iw 5i4a57 744ii we w e 4i 3a a3 \
 *   855 455 74ii ee5 5e5e 798ea 5i 7 8 9 1 3 4 5 i y 5i34iwa \
 *   > app/src/test/resources/panel-golden.json
 * ```
 */
class PanelGoldenTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadAssets() {
            TwelveKeyPreedit.clearWordDataForTest()
            val main = File("src/main/assets/rime/hakka.dict.yaml")
            val counts = TwelveKeyPreedit.loadDictLines(main.readLines().asSequence())
            TwelveKeyPreedit.loadStandardSyllableLines(
                File("src/main/assets/rime/hakka_syllables.txt").readLines().asSequence(),
            )
            val standard = TwelveKeyPreedit.standardSyllablesSnapshot()
            // 與生產 load() 同口徑：進口詞典的單字讀音只並入標準音集收錄的音節
            // （`渠 gia` 只在 zon；`bon/hop` 這類方案外拼寫不要）。
            listOf(
                "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords", "hakka_places",
                "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
            ).forEach { name ->
                val f = File("src/main/assets/rime/$name.dict.yaml")
                if (f.isFile) {
                    TwelveKeyPreedit.loadDictLines(f.readLines().asSequence()).forEach { (syllable, count) ->
                        if (syllable in standard) counts[syllable] = (counts[syllable] ?: 0) + count
                    }
                }
            }
            TwelveKeyPreedit.finishLoading(counts)
            listOf(
                "hakka_places", "hakka_sougou_words", "hakka_poems", "hakka_zon",
                "hakka_mobilewords", "hakka_oftenwords", "hakka_website", "hakka_cn_en",
            ).forEach { name ->
                val f = File("src/main/assets/rime/$name.dict.yaml")
                if (f.isFile) TwelveKeyPreedit.loadWordLines(f.readLines().asSequence())
            }
            TwelveKeyPreedit.finishWordIndex()
        }
    }

    private fun golden(): JsonObject =
        JsonParser.parseString(File("src/test/resources/panel-golden.json").readText()).asJsonObject

    private fun rowsOf(array: JsonArray): List<Pair<String, Set<String>>> =
        (0 until array.size()).map { i ->
            val row = array[i].asJsonObject
            val items = row.getAsJsonArray("items")
            row.get("reading").asString to
                (0 until items.size()).map { items[it].asString }.toSet()
        }

    private fun checkSource(
        code: String,
        source: String,
        goldenRows: List<Pair<String, Set<String>>>,
        actual: List<PanelRow>,
    ) {
        val expected = goldenRows.toMap()
        val got = actual.associate { it.reading to it.items.map { item -> item.text }.toSet() }
        assertEquals(
            "[$code/$source] 缺少行（腳本有、App 沒有）",
            emptySet<String>(),
            expected.keys - got.keys,
        )
        assertEquals(
            "[$code/$source] 多出行（App 多了腳本沒有的行）",
            emptySet<String>(),
            got.keys - expected.keys,
        )
        expected.forEach { (reading, items) ->
            assertEquals("[$code/$source] 行 `$reading` 的文字集合不一致", items, got[reading])
        }
        // 行序也必須一致（源函數輸出順序 = 選行順序，後面 mergeRows 依賴它）
        assertEquals(
            "[$code/$source] 行序不一致",
            goldenRows.map { it.first },
            actual.map { it.reading },
        )
    }

    @Test
    fun panelSourcesMatchScriptGolden() {
        val golden = golden()
        val codes = golden.keySet().sorted()
        assertEquals("golden 應含 32 個碼串", 32, codes.size)
        for (code in codes) {
            val entry = golden.getAsJsonObject(code)
            val sources = entry.getAsJsonObject("sources")
            val actual = TwelveKeySnapshotBuilder.debugPanelSources(code)
            checkSource(code, "synth", rowsOf(sources.getAsJsonArray("synth")), actual.synth)
            checkSource(code, "split", rowsOf(sources.getAsJsonArray("split")), actual.split)
            checkSource(code, "assoc", rowsOf(sources.getAsJsonArray("assoc")), actual.assoc)
            checkSource(code, "combo", rowsOf(sources.getAsJsonArray("combo")), actual.combo)
            checkSource(
                code, "whole_word", rowsOf(sources.getAsJsonArray("whole_word")), actual.wholeWord,
            )

            val merged = rowsOf(entry.getAsJsonArray("merged"))
            val actualMerged = actual.merged.map { row ->
                row.reading to row.items.map { it.text }.toSet()
            }
            assertEquals(
                "[$code/merged] 行序/讀音不一致",
                merged.map { it.first },
                actualMerged.map { it.first },
            )
            merged.zip(actualMerged).forEach { (exp, got) ->
                assertEquals("[$code/merged] 行 `${exp.first}` 文字集合不一致", exp.second, got.second)
            }
        }
    }
}
