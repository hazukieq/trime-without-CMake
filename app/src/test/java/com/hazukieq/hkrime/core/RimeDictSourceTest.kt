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
