package com.hazukieq.hkrime.core.twelvekey

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 六個 App 來源與 merged 的讀音、文字集合及行序對拍；重新生成命令見 docs/19。 */
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
        org.junit.Assert.assertTrue("golden 必須包含選太後的各段及標準集切分",
            codes.containsAll(listOf("9aii48i5i73", "i48i5i73", "8i5i73", "5i73", "73", "ea144", "55")))
        for (code in codes) {
            val entry = golden.getAsJsonObject(code)
            val sources = entry.getAsJsonObject("sources")
            val actual = TwelveKeySnapshotBuilder.debugPanelSources(code)
            checkSource(code, "isolated", rowsOf(sources.getAsJsonArray("isolated")), actual.isolated)
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
    @Test
    fun disambiguatedSourcesMatchScriptBeforeQuotaAndMerge() {
        val golden = JsonParser.parseString(File("src/test/resources/panel-initial-golden.json").readText()).asJsonObject
        for ((initial, cases) in golden.entrySet()) {
            for ((code, entry) in cases.asJsonObject.entrySet()) {
                val sources = entry.asJsonObject.getAsJsonObject("sources")
                val actual = TwelveKeySnapshotBuilder.debugPanelSources(code, initial)
                for ((name, rows) in listOf("isolated" to actual.isolated, "synth" to actual.synth,
                    "split" to actual.split, "assoc" to actual.assoc, "combo" to actual.combo,
                    "whole_word" to actual.wholeWord)) {
                    checkSource("$code/$initial", name, rowsOf(sources.getAsJsonArray(name)), rows)
                }
                checkSource("$code/$initial", "merged", rowsOf(entry.asJsonObject.getAsJsonArray("merged")), actual.merged)
            }
        }
    }

}
