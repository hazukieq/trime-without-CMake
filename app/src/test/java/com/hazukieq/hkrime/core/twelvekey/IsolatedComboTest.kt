package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 孤單聯想規則：多鍵、整串不是完整音節時，按首鍵字母列出可延伸單音節。 */
class IsolatedComboTest {

    @Test
    fun fullSyllableHasNoIsolatedRows() {
        assertTrue(TwelveKeyPreedit.isSingleSyllable("ea7"))
        assertTrue(TwelveKeyPreedit.isSingleSyllable("eai"))
        assertTrue(TwelveKeyPreedit.isolatedSyllables("ea7").isEmpty())
        assertTrue(TwelveKeyPreedit.isolatedSyllables("eai").isEmpty())
        // gia 不在主詞典但標準集有，碼 iia 已是完整音節 -> 不出孤單聯想
        assertTrue(TwelveKeyPreedit.hasFullSyllable("iia"))
        assertTrue(TwelveKeyPreedit.isolatedSyllables("iia").isEmpty())
        // 多音節但整串可完整切分（ma jun / ma jok）也不出孤立鍵
        assertTrue(TwelveKeyPreedit.hasFullSyllable("4a834"))
        assertTrue(TwelveKeyPreedit.isolatedSyllables("4a834").isEmpty())
        assertTrue(TwelveKeyPreedit.isolatedSyllables("4a845").isEmpty())
        // 單鍵不生成（音節組已經在）
        assertTrue(TwelveKeyPreedit.isolatedSyllables("e").isEmpty())
    }

    @Test
    fun multiKeyNonSyllableListsAllSyllablesByLetter() {
        // 55（kl kl）沒有完整音節：通配列出所有 k/l 開頭音節，不受前綴限制
        val groups = TwelveKeyPreedit.isolatedSyllables("55")
        assertEquals(listOf('k', 'l'), groups.map { it.first })
        groups.forEach { (letter, syllables) ->
            assertTrue(syllables.isNotEmpty())
            syllables.forEach { syllable -> assertEquals(letter, syllable.first()) }
        }
        assertTrue(groups[0].second.any { it == "ka" })
        assertTrue(groups[1].second.any { it.startsWith("la") })
    }

    @Test
    fun builderProjectsLetterRowsBeforeSingleRows() {
        val rows = TwelveKeySnapshotBuilder.isolatedRows("55")
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.segment == ISOLATED_SEGMENT })
        assertTrue(rows.all { RowSource.ISOLATED in it.sources })
        assertEquals("k", rows[0].reading)
        val group = rows[0].toSyllableGroup()
        assertEquals(ISOLATED_SEGMENT, group.segment)
        assertTrue(group.syllables.isNotEmpty())
        assertTrue(group.items.all { it.segment == ISOLATED_SEGMENT })
        assertTrue(group.items.all { it.text.isNotEmpty() })
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val counts = TwelveKeyPreedit.loadDictLines(
                File("src/main/assets/rime/hakka.dict.yaml").readLines().asSequence(),
            )
            runCatching {
                TwelveKeyPreedit.loadCharFreqLines(
                    File("src/main/assets/rime/hakka_char_freq.txt").readLines().asSequence(),
                )
            }
            TwelveKeyPreedit.loadStandardSyllableLines(
                File("src/main/assets/rime/hakka_syllables.txt").readLines().asSequence(),
            )
            TwelveKeyPreedit.finishLoading(counts)
        }
    }
}
