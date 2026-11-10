package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.content.SharedPreferences

/**
 * App 側「逐段拼出來的組詞」記憶（見 docs/10 §15.64）。
 *
 * 為什麼要 App 自己記：`enable_sentence: false`，librime 不會把「逐段選出的多字串」裝配成一條詞；
 * 引擎 userdb 只學「選中的那條候選」（單字粒度）。而用戶逐段拼出來的詞（如 `以液難`）下次再打同一串碼時
 * 引擎依然給不出來 —— 這份記憶正是補這個缺口：第 2 次起把它作為**合成候選**塞回候選條。
 *
 * 存儲：SharedPreferences `twelvekey_phrases`
 * - key   = 正規化碼串（去掉 `'` 與空白、小寫），如 `8i8e4`
 * - value = 多條詞條編碼成一行行：`文字\u0001讀音\u0001次數\u0001上次使用時間(ms)`，行間 `\n`
 *
 * 只在 12 鍵（hakka_jiugong）使用；26 鍵不參與（調用方 schemaId 門控）。
 */
object PhraseMemory {

    private const val PREFS = "twelvekey_phrases"

    /** 全局詞條上限（LRU 淘汰時按分數低者先丟）。 */
    const val MAX_ENTRIES = 200

    /** 同一個碼串最多留幾條不同文字的詞條。 */
    const val MAX_PER_CODE = 8

    /** 累計多少次才生效（用戶 2026-11-11：「累計 2 次生效」）。 */
    const val MIN_COUNT = 2

    /** 次數上限（避免一個詞無限漲權）。 */
    const val MAX_COUNT = 9

    /** 時間衰減半衰期：7 天不用，分數減半。 */
    const val HALF_LIFE_MS = 7L * 24 * 3600 * 1000

    private const val SEP = '\u0001'
    private const val LINE = '\n'

    /** 一條學到的詞：文字 + 各段讀音（空格分隔）+ 次數 + 上次使用時間。 */
    data class Entry(
        val text: String,
        val readings: String,
        val count: Int,
        val lastUsedAt: Long,
    )

    private var prefs: SharedPreferences? = null
    private val byCode = HashMap<String, MutableList<Entry>>()

    /** 每次記錄變化自增：讓快照緩存失效（同 [SelectionMemory.version]）。 */
    var version: Int = 0
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.all.forEach { (code, value) ->
            val raw = value as? String ?: return@forEach
            val list = decode(raw)
            if (list.isNotEmpty()) byCode[code] = list.toMutableList()
        }
    }

    // ---------- 純邏輯（可單測） ----------

    /** 碼串正規化：去 `'` 與空白、統一大小寫。合成候選的 key 一律走這裡。 */
    fun normalizeCode(code: String): String =
        buildString(code.length) {
            code.forEach { c ->
                if (c != '\'' && !c.isWhitespace()) append(c.lowercaseChar())
            }
        }

    /** 學習分：次數（≥2 起算）× 時間衰減；分數越高越該排在前面。 */
    fun score(entry: Entry, now: Long): Double {
        val age = (now - entry.lastUsedAt).coerceAtLeast(0L)
        val decay = Math.pow(0.5, age.toDouble() / HALF_LIFE_MS.toDouble())
        return (entry.count - 1).coerceAtLeast(0) * decay
    }

    /** 某碼串下「可以顯示」的詞條（次數達標），按分數降序。 */
    fun visibleEntries(entries: List<Entry>, now: Long): List<Entry> =
        entries.filter { it.count >= MIN_COUNT && it.text.length >= 2 }
            .sortedWith(compareByDescending<Entry> { score(it, now) }.thenByDescending { it.lastUsedAt })

    /** 記錄一次「逐段拼出」：同碼串同文字 +1，更新讀音與時間；超限淘汰。 */
    fun applyRecord(
        entries: List<Entry>,
        text: String,
        readings: String,
        now: Long,
    ): List<Entry> {
        if (text.length < 2) return entries
        val out = ArrayList<Entry>(entries.size + 1)
        var merged = false
        entries.forEach { e ->
            if (!merged && e.text == text) {
                merged = true
                out.add(
                    e.copy(
                        count = (e.count + 1).coerceAtMost(MAX_COUNT),
                        lastUsedAt = now,
                        readings = readings.ifBlank { e.readings },
                    ),
                )
            } else {
                out.add(e)
            }
        }
        if (!merged) out.add(Entry(text, readings, 1, now))
        // 同碼串只留分數最高的前 N 條
        return out.sortedWith(compareByDescending<Entry> { score(it, now) }.thenByDescending { it.lastUsedAt })
            .take(MAX_PER_CODE)
            .sortedBy { it.lastUsedAt }
    }

    /** 編碼：一行一條詞。 */
    fun encode(entries: List<Entry>): String =
        entries.joinToString(LINE.toString()) { e ->
            listOf(e.text, e.readings, e.count.toString(), e.lastUsedAt.toString())
                .joinToString(SEP.toString())
        }

    /** 解碼：容錯，壞行直接丟。 */
    fun decode(raw: String): List<Entry> =
        raw.split(LINE)
            .mapNotNull { line ->
                val parts = line.split(SEP)
                if (parts.size < 4) return@mapNotNull null
                val text = parts[0]
                if (text.isBlank()) return@mapNotNull null
                val count = parts[2].toIntOrNull() ?: return@mapNotNull null
                val last = parts[3].toLongOrNull() ?: 0L
                Entry(text, parts[1], count.coerceIn(1, MAX_COUNT), last)
            }
            .take(MAX_PER_CODE)

    // ---------- 讀寫 ----------

    fun hasAny(): Boolean = byCode.isNotEmpty()

    /** 記一次「逐段拼出的整串」。 */
    fun record(code: String, text: String, readings: String) {
        val key = normalizeCode(code)
        if (key.isEmpty() || text.length < 2) return
        val now = System.currentTimeMillis()
        val updated = applyRecord(byCode[key]?.toList() ?: emptyList(), text, readings, now)
        byCode[key] = updated.toMutableList()
        prefs?.edit()?.putString(key, encode(updated))?.apply()
        version++
        trimGlobal(now)
    }

    /** 點合成候選上屏：同樣 +1（用戶 2026-11-11：「重複組成/選擇時權重也要變化」）。 */
    fun bump(code: String, text: String, readings: String) = record(code, text, readings)

    /** 某碼串下可以作為合成候選的詞條（已按分數降序，次數未達標的被過濾）。 */
    fun candidatesFor(code: String, now: Long = System.currentTimeMillis()): List<Entry> {
        val key = normalizeCode(code)
        if (key.isEmpty()) return emptyList()
        return visibleEntries(byCode[key] ?: return emptyList(), now)
    }

    /** 清空（調試/單測用）。 */
    fun clear() {
        byCode.clear()
        prefs?.edit()?.clear()?.apply()
        version++
    }

    private fun trimGlobal(now: Long) {
        val total = byCode.values.sumOf { it.size }
        if (total <= MAX_ENTRIES) return
        // 全局超限：按「分數最低」淘汰，直到回到上限。
        var drop = total - MAX_ENTRIES
        val flat: List<Pair<String, Entry>> = byCode.entries.flatMap { (code, list) ->
            list.map { entry -> code to entry }
        }.sortedWith(
            compareBy<Pair<String, Entry>> { score(it.second, now) }.thenBy { it.second.lastUsedAt },
        )
        val e = prefs?.edit()
        flat.forEach { (code, entry) ->
            if (drop <= 0) return@forEach
            val list = byCode[code] ?: return@forEach
            if (list.remove(entry)) {
                drop--
                if (list.isEmpty()) byCode.remove(code)
                if (list.isEmpty()) e?.remove(code) else e?.putString(code, encode(list))
            }
        }
        e?.apply()
        if (drop < total) version++
    }
}
