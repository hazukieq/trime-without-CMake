package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `import_tables` 解析（用戶 2026-11-09 在 dsh mobile 反饋「添加新條目到詞庫不生效」：
 * App 側只讀 APK 內置字典、不展開 import_tables，所以磁盤上加的詞表/詞條看不到）。
 */
class RimeDictSourceTest {

    @Test
    fun `bodyStart skips yaml header up to terminator`() {
        val lines = listOf("# c", "---", "name: hakka", "import_tables:", "  - a", "...", "一\tjit")
        assertEquals(6, RimeDictSource.bodyStart(lines))
        assertEquals("一\tjit", lines[RimeDictSource.bodyStart(lines)])
    }

    @Test
    fun `bodyStart is zero when no terminator`() {
        assertEquals(0, RimeDictSource.bodyStart(listOf("一\tjit", "乙\tjet")))
    }

    @Test
    fun `parseImports reads block list`() {
        val lines = listOf(
            "---",
            "name: hakka",
            "import_tables:   # 导入拓展词典",
            "    - hakka_places",
            "    - hakka_zon.dict.yaml",
            "...",
            "一\tjit",
        )
        assertEquals(listOf("hakka_places", "hakka_zon"), RimeDictSource.parseImports(lines))
    }

    @Test
    fun `parseImports reads inline list`() {
        val lines = listOf("---", "import_tables: [hakka_zon, hakka_cn_en]", "...", "一\tjit")
        assertEquals(listOf("hakka_zon", "hakka_cn_en"), RimeDictSource.parseImports(lines))
    }

    @Test
    fun `parseImports ignores body lines and comments`() {
        val lines = listOf("---", "# import_tables: [nope]", "...", "import_tables: [fake]", "詞\tci")
        assertEquals(emptyList<String>(), RimeDictSource.parseImports(lines))
    }

    @Test
    fun `parseImports dedupes`() {
        val lines = listOf("---", "import_tables:", "  - a", "  - a", "...")
        assertEquals(listOf("a"), RimeDictSource.parseImports(lines))
    }
}

/**
 * 詞表行解析（用戶 2026-11-11：「我輸入 li nak si 都沒見到 li nak si 的聯想詞組」）。
 *
 * 他在 `hakka_zon.dict.yaml` 第 3539-3540 行加了 `Linux`/`linux<TAB>li nak si`，
 * 而舊的 `loadWordLines` 只認「2~4 字全漢字、讀音靠 charTable 反推」的詞，
 * 非漢字詞、以及帶讀音列的詞全被丟掉。
 */
class WordLineParsingTest {

    @org.junit.Before
    fun setUp() {
        TwelveKeyPreedit.finishLoading(
            hashMapOf(
                "li" to 30, "nak" to 20, "si" to 10,
                "ngin" to 40, "mang" to 5, "kang" to 5,
            ),
        )
    }

    @org.junit.Test
    fun `non cjk word with reading column is indexed`() {
        TwelveKeyPreedit.loadWordLines(sequenceOf("Linux\tli nak si", "linux\tli nak si"))
        TwelveKeyPreedit.finishWordIndex()
        val got = TwelveKeyPreedit.associatedWords("li", 'n', 100)
        org.junit.Assert.assertTrue("li|n 應該有 Linux/linux，實際=$got", got.contains("Linux"))
        org.junit.Assert.assertTrue(got.contains("linux"))
    }

    @org.junit.Test
    fun `cjk word with reading column is indexed by its own reading`() {
        TwelveKeyPreedit.loadWordLines(sequenceOf("猛劥\tmang kang"))
        TwelveKeyPreedit.finishWordIndex()
        org.junit.Assert.assertTrue(
            TwelveKeyPreedit.associatedWords("mang", 'k', 100).contains("猛劥"),
        )
    }

    @org.junit.Test
    fun `text only list still falls back to char table readings`() {
        TwelveKeyPreedit.loadWordLines(sequenceOf("你人"))
        TwelveKeyPreedit.finishWordIndex()
        // 「你人」兩字不在 charTable 裡 → 這條被跳過（不崩、不亂配）
        org.junit.Assert.assertFalse(TwelveKeyPreedit.associatedWords("li", 'n', 100).contains("你人"))
    }
}
