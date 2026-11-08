package com.hazukieq.hkrime.ime.text

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 音節 → 候選詞 的「快速查找」索引，直接由 assets/rime/hakka.dict.yaml 建立。
 *
 * 用途：展開面板左列點某個音節時，右側不經過 librime，直接列出字典裡這個音節的字/詞。
 * 字典格式：`詞<TAB>音節`（多音節詞的音節以空格分隔，本索引只用單音節條目）。
 */
object SyllableLexicon {

    private var index: Map<String, List<String>>? = null

    /** 音節表（由字典的編碼列得到），用於 fallback 貪心切分。 */
    fun syllables(context: Context): Set<String> {
        val idx = index ?: build(context).also { index = it }
        return idx.keys
    }

    /**
     * 用字典音節表貪心切分（最長匹配）；librime 的光標分段退化成一字一段時作為兜底。
     * 返回每段的 (text, startByte) —— startByte 是 raw 的 UTF-8 字節偏移。
     */
    fun split(context: Context, raw: String): List<Pair<String, Int>> {
        if (raw.isEmpty()) return emptyList()
        val set = syllables(context)
        val out = ArrayList<Pair<String, Int>>()
        val bytes = raw.toByteArray(Charsets.UTF_8)
        var bytePos = 0
        var i = 0
        while (i < raw.length) {
            // 跳過空格/分隔符
            if (raw[i] == ' ' || raw[i] == '\'') {
                i++
                bytePos += raw[i - 1].toString().toByteArray(Charsets.UTF_8).size
                continue
            }
            var matched = ""
            var len = Math.min(8, raw.length - i)
            while (len > 0) {
                val cand = raw.substring(i, i + len)
                if (cand in set) {
                    matched = cand
                    break
                }
                len--
            }
            val piece = if (matched.isNotEmpty()) matched else raw.substring(i, i + 1)
            out.add(piece to bytePos)
            i += piece.length
            bytePos += piece.toByteArray(Charsets.UTF_8).size
        }
        return out
    }

    fun lookup(context: Context, syllable: String): List<String> {
        val s = syllable.trim().trim('\'', ' ')
        if (s.isEmpty()) return emptyList()
        val idx = index ?: build(context).also { index = it }
        return idx[s] ?: emptyList()
    }

    private fun build(context: Context): Map<String, List<String>> {
        val map = HashMap<String, MutableList<String>>()
        try {
            context.assets.open("rime/hakka.dict.yaml").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    var started = false
                    var line = reader.readLine()
                    while (line != null) {
                        val t = line.trimEnd()
                        if (!started) {
                            if (t == "...") started = true
                            line = reader.readLine()
                            continue
                        }
                        if (t.isNotEmpty() && t[0] != '#') {
                            val parts = t.split('\t')
                            if (parts.size >= 2) {
                                val word = parts[0].trim()
                                val codes = parts[1].trim().split(' ')
                                if (word.isNotEmpty() && codes.size == 1) {
                                    map.getOrPut(codes[0]) { ArrayList() }.add(word)
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        // 單字優先，然後按字數、字串排序，去重
        return map.mapValues { (_, list) ->
            list.distinct()
                .sortedWith(compareBy({ it.length }, { it }))
        }
    }
}
