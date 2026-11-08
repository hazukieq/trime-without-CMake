package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.content.SharedPreferences

/**
 * 12 鍵「選過的詞往前排」的本地記憶。
 *
 * 排序鍵 = 選中次數 * 1000 + 最近一次選中的序號（遞增）：
 * - 次數多者靠前（點 5 次一定比點 1 次前）；
 * - 次數相同時，最近點的那個在前（點完立刻重打能看到它排第一）。
 *
 * 為甚麼不依賴 librime userdb：簡拼為效能跳過/限制了用戶詞庫查詢，
 * 引擎學到的詞在簡拼下不一定排前；這裡在 App 側記一份輕量、所見即所得的次序。
 */
object SelectionMemory {

    private const val PREFS = "twelvekey_selection"
    private const val KEY_SEQ = "~seq"
    private const val MAX_ENTRIES = 800
    private const val MAX_COUNT = 9

    private var prefs: SharedPreferences? = null
    private val scores = HashMap<String, Int>()
    private var seq = 0

    /** 每次 bump 自增：讓快照緩存鍵失效，重排才能立刻生效。 */
    var version: Int = 0
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.all.forEach { (k, v) ->
            when (v) {
                is Int -> if (k == KEY_SEQ) seq = v else scores[k] = v
                else -> Unit
            }
        }
    }

    private fun key(text: String, comment: String): String =
        if (comment.isBlank()) text else "$comment\u0001$text"

    /** 選中一次：次數 +1，並記住它是最新一次的選擇。 */
    fun bump(text: String, comment: String) {
        if (text.isBlank()) return
        val k = key(text, comment)
        val old = scores[k] ?: 0
        val count = ((old / 1000) + 1).coerceAtMost(MAX_COUNT)
        seq += 1
        scores[k] = count * 1000 + seq
        prefs?.edit()?.putInt(k, scores[k]!!)?.putInt(KEY_SEQ, seq)?.apply()
        version++
        if (scores.size > MAX_ENTRIES) {
            val drop = scores.entries.sortedBy { it.value }.take(scores.size - MAX_ENTRIES)
            val e = prefs?.edit()
            drop.forEach { scores.remove(it.key); e?.remove(it.key) }
            e?.apply()
        }
    }

    fun hasAny(): Boolean = scores.isNotEmpty()

    fun score(text: String, comment: String): Int = scores[key(text, comment)] ?: 0
}
