package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.content.SharedPreferences

/**
 * 12 鍵「選過的詞往前排」的本地記憶。
 *
 * 為甚麼不依賴 librime 的 userdb：簡拼為了效能跳過了用戶詞庫查詢（或用深度限制），
 * 引擎側學到的詞在簡拼輸入下不一定排到前面；這裡在 App 側記一份輕量計數，
 * 選過的候選（同讀音+同詞）在下次同輸入時直接排前，所見即所得。
 */
object SelectionMemory {

    private const val PREFS = "twelvekey_selection"
    private const val MAX_ENTRIES = 800

    private var prefs: SharedPreferences? = null
    private val scores = HashMap<String, Int>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.all.forEach { (k, v) -> (v as? Int)?.let { scores[k] = it } }
    }

    private fun key(text: String, comment: String): String =
        if (comment.isBlank()) text else "$comment\u0001$text"

    /** 選中一次：計數 +1（上限 9，避免一次手誤永久霸榜）。 */
    fun bump(text: String, comment: String) {
        if (text.isBlank()) return
        val k = key(text, comment)
        val next = ((scores[k] ?: 0) + 1).coerceAtMost(9)
        scores[k] = next
        prefs?.edit()?.putInt(k, next)?.apply()
        if (scores.size > MAX_ENTRIES) {
            // 簡單裁剪：清掉計數最低的一批
            val drop = scores.entries.sortedBy { it.value }.take(scores.size - MAX_ENTRIES)
            val e = prefs?.edit()
            drop.forEach { scores.remove(it.key); e?.remove(it.key) }
            e?.apply()
        }
    }

    fun hasAny(): Boolean = scores.isNotEmpty()

    fun score(text: String, comment: String): Int =
        (scores[key(text, comment)] ?: 0) * 2 + (scores[key(text, "")] ?: 0)
}
