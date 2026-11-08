package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * 用戶自己的用字記憶（固定候選區排序用）。
 *
 * 「按段選字」的候選條**不該**用靜態詞表排 —— 用戶要的是「我常用哪些字」和
 * 「我最近用哪些字組成詞」。這裡在本機跨會話記兩份統計：
 *
 * - [charFreq]：每個字被上屏的次數 = 用戶常用字頻率；
 * - [bigramFreq]：相鄰兩字一起上屏的次數 = 用戶自己的組詞關聯度（含近期用詞習慣）。
 *
 * 排序分兩階段：先比「與前一個字的關聯度」（bigram），同分再比「字頻」，
 * 都沒有記憶時保持字典順序。
 */
object SegmentMemory {

    private const val PREFS_NAME = "segment_memory"
    private const val KEY_CHARS = "char_freq"
    private const val KEY_BIGRAMS = "bigram_freq"
    private const val MAX_CHARS = 400
    private const val MAX_BIGRAMS = 3000

    private val charFreq = HashMap<Char, Int>()
    private val bigramFreq = HashMap<String, Int>()
    private var prefs: SharedPreferences? = null

    /** 上一次上屏的最後一個漢字（跨 submit 保存），用來算組詞關聯度。 */
    private var lastChar: Char? = null

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            p.getString(KEY_CHARS, null)?.split(';')?.forEach { part ->
                val idx = part.indexOf(':')
                if (idx == 1) {
                    charFreq[part[0]] = part.substring(idx + 1).toIntOrNull() ?: 0
                }
            }
            p.getString(KEY_BIGRAMS, null)?.split(';')?.forEach { part ->
                val idx = part.indexOf(':')
                if (idx == 2) {
                    bigramFreq[part.substring(0, idx)] = part.substring(idx + 1).toIntOrNull() ?: 0
                }
            }
        }.onFailure { Timber.w(it, "SegmentMemory: load failed") }
    }

    /** 記錄一次上屏：更新字頻、與上一字的 bigram，並把 [lastChar] 推到最後一個漢字。 */
    fun bump(committed: CharSequence) {
        if (committed.isEmpty()) return
        var dirty = false
        for (ch in committed) {
            if (ch !in '\u4E00'..'\u9FFF') {
                // 標點/ASCII 會打斷組詞關聯，但不算用字
                lastChar = null
                continue
            }
            charFreq[ch] = (charFreq[ch] ?: 0) + 1
            lastChar?.let { prev ->
                val key = "" + prev + ch
                bigramFreq[key] = (bigramFreq[key] ?: 0) + 1
            }
            lastChar = ch
            dirty = true
        }
        if (!dirty) return
        trim()
        save()
    }

    /** 字的歷史使用次數。 */
    fun charScore(text: String): Int = if (text.isEmpty()) 0 else charFreq[text[0]] ?: 0

    /** 與前一字的組詞關聯度（用戶自己打過的相鄰組合）。 */
    fun bigramScore(prev: Char?, text: String): Int =
        if (prev == null || text.isEmpty()) 0 else bigramFreq["" + prev + text[0]] ?: 0

    /** 兩階段排序：先關聯度、後字頻；無記憶時保持原順序。 */
    fun rank(chars: List<String>, prev: Char?): List<String> {
        if (chars.size < 2) return chars
        return chars.sortedWith(
            compareByDescending<String> { bigramScore(prev, it) }
                .thenByDescending { charScore(it) },
        )
    }

    private fun trim() {
        if (charFreq.size > MAX_CHARS) {
            charFreq.entries.sortedByDescending { it.value }.drop(MAX_CHARS).forEach {
                charFreq.remove(it.key)
            }
        }
        if (bigramFreq.size > MAX_BIGRAMS) {
            bigramFreq.entries.sortedByDescending { it.value }.drop(MAX_BIGRAMS).forEach {
                bigramFreq.remove(it.key)
            }
        }
    }

    private fun save() {
        val p = prefs ?: return
        runCatching {
            p.edit()
                .putString(KEY_CHARS, charFreq.entries.joinToString(";") { "${it.key}:${it.value}" })
                .putString(
                    KEY_BIGRAMS,
                    bigramFreq.entries.joinToString(";") { "${it.key}:${it.value}" },
                )
                .apply()
        }.onFailure { Timber.w(it, "SegmentMemory: save failed") }
    }
}
