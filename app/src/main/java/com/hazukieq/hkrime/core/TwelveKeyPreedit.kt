package com.hazukieq.hkrime.core

import android.content.Context
import timber.log.Timber
import java.util.HashMap

/**
 * 12 鍵（hakka_jiugong）的 preedit 還原。
 *
 * 這套方案的輸入是「12 鍵碼」（a/e/i/5/4/7/3/8/9/1/y/w），一個碼對應多個字母，
 * 所以 schema 裡的 preedit_format 沒法可靠地反推拼音（a 可能是 a/b/c…）。
 * 這裡改成：用 hakka.dict.yaml 的 700 個音節反推「碼 → 音節」表，再把打進來的碼串
 * 切成最可能的音節串；切不出來就回傳 null，維持原本的 preedit。
 */
object TwelveKeyPreedit {

    private class Entry(val syllable: String, val freq: Int)

    private val table = HashMap<String, Entry>()

    /** 單字 → 音節（取字典裡第一次出現的讀音），用來在歧義時對齊第一個候選字。 */
    private val charTable = HashMap<String, String>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        runCatching { load(context) }
            .onFailure { Timber.w(it, "TwelveKeyPreedit: load dict failed") }
    }

    private fun load(context: Context) {
        val counts = HashMap<String, Int>()
        context.assets.open("rime/hakka.dict.yaml").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                if (raw.isBlank() || raw.startsWith("#")) return@forEach
                val tab = raw.indexOf('\t')
                if (tab < 0) return@forEach
                val ch = raw.substring(0, tab).trim()
                val syllable = raw.substring(tab + 1).trim()
                if (ch.length == 1) charTable.putIfAbsent(ch, syllable)
                if (syllable.isNotEmpty() && syllable.all { it in 'a'..'z' }) {
                    counts[syllable] = (counts[syllable] ?: 0) + 1
                }
            }
        }
        counts.forEach { (syllable, freq) ->
            val code = codeOf(syllable)
            val old = table[code]
            if (old == null || old.freq < freq) table[code] = Entry(syllable, freq)
        }
        Timber.i("TwelveKeyPreedit: syllables=%d codes=%d", counts.size, table.size)
    }

    /** 拼音 → 12 鍵碼（對應 alphabet 的 abbrev/xform/xlit）。 */
    private fun codeOf(syllable: String): String {
        var s = syllable
        s = when {
            s.startsWith("nz") -> "y" + s.substring(2)
            s.startsWith("th") -> "w" + s.substring(2)
            s.startsWith("ng") -> "1" + s.substring(2)
            else -> s
        }
        s = s.replace(Regex("(\\w)o"), "$14").replace(Regex("(\\w)u"), "$13")
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    'a', 'b', 'c' -> 'a'
                    'd', 'e', 'f' -> 'e'
                    'g', 'h', 'i' -> 'i'
                    'k', 'l' -> '5'
                    'm', 'n' -> '4'
                    'p', 's' -> '7'
                    'v', 'r' -> '3'
                    'z', 'j' -> '8'
                    'q', 't' -> '9'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** 單鍵碼 → 該鍵覆蓋的字母（與鍵盤 12 鍵的分組一致）。 */
    private fun keyLetters(c: Char): String = when (c) {
        'a' -> "abc"
        'e' -> "def"
        'i' -> "ghi"
        '5' -> "kl"
        '4' -> "mn"
        '7' -> "ps"
        '9' -> "qt"
        '3' -> "vur"
        '8' -> "zj"
        '1' -> "ng"
        'w' -> "th"
        'y' -> "nz"
        else -> c.toString()
    }

    /**
     * 把碼串切成音節串；切不出來回傳 null（保持原 preedit）。
     *
     * 用戶按下「分詞」鍵（theme 的 `composing: delimiter`）會在碼串裡插入 `'`：
     * 那是用戶**明確表達的音節邊界**，不能像以前那樣直接濾掉再重新合併
     * （否則 `5'e` 會顯示成 `le`，看起來像分隔沒生效）。這裡按 `'`/空白分段，
     * 每段各自切音節，切不出來的段保留原碼（如 `5`）。
     */
    fun decode(preedit: String?, hint: String? = null): String? {
        if (preedit.isNullOrBlank() || table.isEmpty()) return null
        val raw = preedit.filter { it.code < 128 }
        if (raw.any { it == '\'' || it.isWhitespace() }) {
            val parts = raw.split(MULTI_DELIMITER).filter { it.isNotEmpty() }
            if (parts.size <= 1) return null
            val segs = ArrayList<String>()
            for (part in parts) {
                val decoded = decodeSingle(part)
                if (decoded != null) segs.addAll(decoded) else segs.add(part)
            }
            return if (segs.isEmpty()) null else segs.joinToString(" ")
        }
        val single = decodeSingle(raw) ?: return null
        // 歧義時對齊「第一條候選字」的讀音：凝好 → ngin hau，而不是 ngin gau
        if (hint.isNullOrEmpty() || hint.length != single.size) return single.joinToString(" ")
        val aligned = single.toMutableList()
        for (i in aligned.indices) {
            val s = charTable[hint.substring(i, i + 1)] ?: continue
            if (codeOf(s) == codeOf(aligned[i])) aligned[i] = s
        }
        return aligned.joinToString(" ")
    }

    private val MULTI_DELIMITER = Regex("[\u0027\\s]+")

    /** 單段碼串 → 音節列表；切不出完整音節回傳 null。 */
    private fun decodeSingle(input: String): List<String>? {
        if (input.isEmpty()) return null
        val n = input.length
        // 先比「音節數量少」，再比「詞頻高」：避免被 ngh/mi 這類高頻短音節切碎
        val bestSeg = IntArray(n + 1) { Int.MAX_VALUE }
        val bestFreq = IntArray(n + 1) { 0 }
        val prev = IntArray(n + 1) { -1 }
        val pick = arrayOfNulls<String>(n + 1)
        bestSeg[0] = 0
        for (i in 0 until n) {
            if (bestSeg[i] == Int.MAX_VALUE) continue
            val maxLen = minOf(6, n - i)
            for (len in 1..maxLen) {
                val entry = table[input.substring(i, i + len)] ?: continue
                val seg = bestSeg[i] + 1
                val freq = bestFreq[i] + entry.freq
                val better = seg < bestSeg[i + len] ||
                    (seg == bestSeg[i + len] && freq > bestFreq[i + len])
                if (better) {
                    bestSeg[i + len] = seg
                    bestFreq[i + len] = freq
                    prev[i + len] = i
                    pick[i + len] = entry.syllable
                }
            }
        }
        // 整串切不出完整音節（只按了聲母鍵的簡拼，如 kl → "5"）：
        // 不回寫，保持 librime 給的原編碼 preedit。
        if (bestSeg[n] == Int.MAX_VALUE) {
            return null
        }
        val out = ArrayList<String>()
        var p = n
        while (p > 0) {
            val q = prev[p]
            if (q < 0) return null
            out.add(pick[p] ?: return null)
            p = q
        }
        out.reverse()
        return out
    }
}
