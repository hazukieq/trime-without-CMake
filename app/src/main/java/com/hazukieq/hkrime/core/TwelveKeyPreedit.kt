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

    /** 逐鍵的簡拼 preedit：每個碼字顯示聲母；有 hint 時用候選字的聲母對齊。 */
    private fun abbrevPreedit(input: String, hint: String?): String {
        val chars = input.filter { it.isLetterOrDigit() }
        if (chars.isEmpty()) return input
        val hintOk = !hint.isNullOrEmpty() && hint.length == chars.length
        val parts = ArrayList<String>(chars.length)
        for (i in chars.indices) {
            val hintedSyllable = if (hintOk) charTable[hint!!.substring(i, i + 1)] else null
            parts.add(if (hintedSyllable != null) initialOf(hintedSyllable) else keyLetters(chars[i]))
        }
        return parts.joinToString(" ")
    }

    /** 音節的聲母部分：th/nz/ng 取兩字母，其餘取首字母。 */
    private fun initialOf(syllable: String): String = when {
        syllable.startsWith("th") -> "th"
        syllable.startsWith("nz") -> "nz"
        syllable.startsWith("ng") -> "ng"
        syllable.isEmpty() -> syllable
        else -> syllable.substring(0, 1)
    }

    /** 把碼串切成音節串；切不出來回傳 null（保持原 preedit）。 */
    fun decode(preedit: String?, hint: String? = null): String? {
        if (preedit.isNullOrBlank() || table.isEmpty()) return null
        val input = preedit.filter { !it.isWhitespace() && it != '\'' && it.code < 128 }
        if (input.isEmpty()) return null
        val n = input.length
        // 先比「音節數量少」，再比「詞頻高」：避免被 ngh/mi 這類高頻短音節切碎
        val bestSeg = IntArray(n + 1) { Int.MAX_VALUE }
        val bestFreq = IntArray(n + 1) { 0 }
        val prev = IntArray(n + 1) { -1 }
        // 每個轉移消耗的碼長，用於判斷「單字符碼」→ preedit 只顯示聲母
        val stepLen = IntArray(n + 1) { 0 }
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
                    stepLen[i + len] = len
                }
            }
        }
        // 整串切不出完整音節：多半是「只按了聲母鍵」的簡拼輸入（如 kl → "5"、kl kl → "55"）。
        // 這種情況 preedit 只顯示聲母字母，並用第一個候選字對齊（和第一個字保持一致）。
        if (bestSeg[n] == Int.MAX_VALUE) {
            return abbrevPreedit(input, hint)
        }
        val out = ArrayList<String>()
        val lens = ArrayList<Int>()
        var p = n
        while (p > 0) {
            val q = prev[p]
            if (q < 0) return null
            out.add(pick[p] ?: return null)
            lens.add(stepLen[p])
            p = q
        }
        out.reverse()
        lens.reverse()
        // 單字符碼（只按了一個鍵，如 kl→5）只能確定聲母：preedit 顯示聲母字母，
        // 且與第一個候選字對齊（hint 對得上就用它的聲母），不再假裝拼出完整音節。
        for (i in out.indices) {
            if (lens.getOrNull(i) != 1) continue
            val hinted = hint?.takeIf { hint.length == out.size }?.let { h ->
                charTable[h.substring(i, i + 1)]
            }
            out[i] = initialOf(hinted ?: out[i])
        }
        // 歧義時對齊「第一個候選字」的讀音：凝好 → ngin hau，而不是 ngin gau
        if (!hint.isNullOrEmpty() && hint.length == out.size) {
            for (i in out.indices) {
                val s = charTable[hint.substring(i, i + 1)] ?: continue
                if (codeOf(s) == codeOf(out[i])) out[i] = s
            }
        }
        return out.joinToString(" ")
    }
}
