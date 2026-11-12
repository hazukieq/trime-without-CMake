package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 四鍵首字母組合要能命中純詞表裡的多字詞（`8a58 -> j·c·k·z -> 远程控制`）。 */
class ComboWordTest {

    @Test
    fun fourKeyComboIncludesMultiCharPureWord() {
        val rows = TwelveKeySnapshotBuilder.initialComboRows("8a58")
        val hit = rows.firstOrNull { row -> row.items.any { it.text == "远程控制" } }
        assertTrue("8a58 的四鍵組合應包含 远程控制，實際 rows=${rows.map { it.reading }}", hit != null)
        assertTrue(hit!!.reading == "jen cin kung ze")
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            TwelveKeyPreedit.clearWordDataForTest()
            val counts = TwelveKeyPreedit.loadDictLines(
                File("src/main/assets/rime/hakka.dict.yaml").readLines().asSequence(),
            )
            TwelveKeyPreedit.loadStandardSyllableLines(
                File("src/main/assets/rime/hakka_syllables.txt").readLines().asSequence(),
            )
            listOf(
                "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords", "hakka_places",
                "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
            ).forEach { name ->
                val f = File("src/main/assets/rime/$name.dict.yaml")
                if (f.isFile) {
                    TwelveKeyPreedit.loadDictLines(f.readLines().asSequence()).forEach { (syllable, count) ->
                        counts[syllable] = (counts[syllable] ?: 0) + count
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
}
