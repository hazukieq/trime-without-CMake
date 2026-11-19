package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.RimeDictSource
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import java.io.File

/** 測試依生產 import_tables 及標準集過濾載入實際資料，不造替代詞典。 */
internal object OfflineDictionary {
    var runtimeSyllables: Set<String> = emptySet()
        private set
    fun load(root: File = File("src/main/assets/rime")) {
        val fallback = File("src/main/assets/rime")
        fun source(name: String) = File(root, name).takeIf { it.isFile } ?: File(fallback, name)
        val standard = source("hakka_syllables.txt")
        TwelveKeyPreedit.clearWordDataForTest()
        TwelveKeyPreedit.loadStandardSyllableLines(standard.readLines().asSequence())
        val allowed = TwelveKeyPreedit.standardSyllablesSnapshot()
        val names = LinkedHashSet<String>()
        fun collect(name: String, depth: Int) {
            if (depth > 4 || !names.add(name)) return
            val file = source("$name.dict.yaml")
            if (!file.isFile) return
            RimeDictSource.parseImports(file.readLines()).forEach { collect(it, depth + 1) }
        }
        collect("hakka", 0)
        val counts = TwelveKeyPreedit.loadDictLines(source("hakka.dict.yaml").readLines().asSequence())
        names.filter { it != "hakka" }.forEach { name ->
            val file = source("$name.dict.yaml")
            if (file.isFile) TwelveKeyPreedit.loadDictLines(file.readLines().asSequence()).forEach { (s, n) ->
                if (s in allowed) counts[s] = (counts[s] ?: 0) + n
            }
        }
        runtimeSyllables = counts.keys.toSet()
        TwelveKeyPreedit.finishLoading(counts)
        names.filter { it != "hakka" }.forEach { name ->
            val file = source("$name.dict.yaml")
            if (file.isFile) TwelveKeyPreedit.loadWordLines(file.readLines().asSequence())
        }
        TwelveKeyPreedit.finishWordIndex()
        PreeditPresentation.invalidate("")
    }
}
