package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.content.SharedPreferences

/**
 * App 本地組詞記憶，使用正規化碼串關聯文字/讀音及手拼、曝光、點選統計。
 * 達 MIN_COUNT 後參與候選混排，讀音索引同時供面板使用；不修改引擎候選的存儲。
 */
object PhraseMemory {

    private const val PREFS = "twelvekey_phrases"

    /** 全局詞條上限（LRU 淘汰時按分數低者先丟）。 */
    const val MAX_ENTRIES = 200

    /** 同一個碼串最多留幾條不同文字的詞條。 */
    const val MAX_PER_CODE = 8

    /**
     * 累計手拼和點選至少 2 次才參與合成候選。
     */
    const val MIN_COUNT = 2

    /** 次數上限（避免一個詞無限漲權）。 */
    const val MAX_COUNT = 9

    /** 時間衰減半衰期：7 天不用，分數減半。 */
    const val HALF_LIFE_MS = 7L * 24 * 3600 * 1000

    /** Beta 先驗（平滑 CTR）：α=1、β=2 → 從沒被選過的詞 CTR 先驗 ≈ 1/3。 */
    const val CTR_ALPHA = 1.0
    const val CTR_BETA = 2.0

    /** 曝光折扣：展示算「弱负信号」，按 0.5 折算（位置/可见性差异，見 Google US8938463 的思路）。 */
    const val EXPOSURE_DISCOUNT = 0.5

    /**
     * 只清理 App 合成記憶：曝光達門檻、從未點選且手拼不超門檻的創制詞。
     * 引擎候選不參與此清理。
     */
    const val PURGE_SHOWN = 8
    const val PURGE_MAX_TYPED = 2

    private const val SEP = '\u0001'
    private const val LINE = '\n'

    /** 一條學到的詞：文字 + 各段讀音（空格分隔）+ 次數 + 上次使用時間。 */
    data class Entry(
        val text: String,
        val readings: String,
        /** 用戶**手拼**組成這個詞的次數（強需求信號）。 */
        val typed: Int,
        val lastUsedAt: Long,
        /** 作為合成候選**顯示**過多少次（弱負信號，見 [score] 的曝光折扣）。 */
        val shown: Int = 0,
        /** 從合成候選**點選**上屏多少次（強正信號）。 */
        val picked: Int = 0,
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

    /**
     * 合成詞分數 = fresh × ctr × (1+ln(1+typed))。
     * fresh 為 7 天半衰期；ctr=(picked+1)/(picked+0.5×shown+3)。
     * 曝光未點降低分數，手拼與點選提高分數。
     */
    fun score(entry: Entry, now: Long): Double {
        val age = (now - entry.lastUsedAt).coerceAtLeast(0L)
        val fresh = Math.pow(0.5, age.toDouble() / HALF_LIFE_MS.toDouble())
        val denom = entry.picked + EXPOSURE_DISCOUNT * entry.shown + CTR_ALPHA + CTR_BETA
        val ctr = (entry.picked + CTR_ALPHA) / denom
        val demand = 1.0 + Math.log1p(entry.typed.toDouble())
        return fresh * ctr * demand
    }

    /**
     * 檢查低需求、無點選且曝光達門檻的 App 創制詞。
     */
    fun shouldPurge(entry: Entry): Boolean =
        entry.shown >= PURGE_SHOWN && entry.picked == 0 && entry.typed <= PURGE_MAX_TYPED

    /** 某碼串下「可以顯示」的詞條（次數達標），按分數降序。 */
    fun visibleEntries(entries: List<Entry>, now: Long): List<Entry> =
        entries.filter { (it.typed + it.picked) >= MIN_COUNT && it.text.length >= 2 && !shouldPurge(it) }
            .sortedWith(compareByDescending<Entry> { score(it, now) }.thenByDescending { it.lastUsedAt })

    /** 記錄一次「逐段拼出」：同一條詞手拼次數 +1，更新讀音與時間；超限淘汰。 */
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
                        typed = (e.typed + 1).coerceAtMost(MAX_COUNT),
                        lastUsedAt = now,
                        readings = readings.ifBlank { e.readings },
                    ),
                )
            } else {
                out.add(e)
            }
        }
        if (!merged) out.add(Entry(text, readings, typed = 1, lastUsedAt = now))
        // 同碼串只留分數最高的前 N 條
        return out.sortedWith(compareByDescending<Entry> { score(it, now) }.thenByDescending { it.lastUsedAt })
            .take(MAX_PER_CODE)
            .sortedBy { it.lastUsedAt }
    }

    /**
     * 每行編碼一條六欄記錄；decode 允許四欄輸入，缺 shown/picked 時補 0。
     */
    fun encode(entries: List<Entry>): String =
        entries.joinToString(LINE.toString()) { e ->
            listOf(
                e.text,
                e.readings,
                e.typed.toString(),
                e.lastUsedAt.toString(),
                e.shown.toString(),
                e.picked.toString(),
            ).joinToString(SEP.toString())
        }

    /** 解碼：容錯，壞行直接丟。 */
    fun decode(raw: String): List<Entry> =
        raw.split(LINE)
            .mapNotNull { line ->
                val parts = line.split(SEP)
                if (parts.size < 4) return@mapNotNull null
                val text = parts[0]
                if (text.isBlank()) return@mapNotNull null
                val typed = parts[2].toIntOrNull() ?: return@mapNotNull null
                val last = parts[3].toLongOrNull() ?: 0L
                val shown = parts.getOrNull(4)?.toIntOrNull() ?: 0
                val picked = parts.getOrNull(5)?.toIntOrNull() ?: 0
                Entry(
                    text = text,
                    readings = parts[1],
                    typed = typed.coerceIn(1, MAX_COUNT),
                    lastUsedAt = last,
                    shown = shown.coerceAtLeast(0),
                    picked = picked.coerceAtLeast(0),
                )
            }
            .take(MAX_PER_CODE)

    // ---------- 讀寫 ----------

    fun hasAny(): Boolean = byCode.isNotEmpty()

    /** 記一次「逐段拼出的整串」（手拼次數 +1）。 */
    fun record(code: String, text: String, readings: String) {
        val key = normalizeCode(code)
        if (key.isEmpty() || text.length < 2) return
        val now = System.currentTimeMillis()
        val updated = applyRecord(byCode[key]?.toList() ?: emptyList(), text, readings, now)
        write(key, updated, now)
    }

    /**
     * 面板詞點選記 App 學習：增加手拼和點選以達露面門檻，後續點選累加分數。
     * 僅收至少兩字的詞，與引擎自身學習分別維護。
     */
    fun learnPick(code: String, text: String, readings: String) {
        val key = normalizeCode(code)
        if (key.isEmpty() || text.length < 2) return
        val now = System.currentTimeMillis()
        val entries = byCode[key] ?: emptyList()
        val existing = entries.firstOrNull { it.text == text }
        if (existing != null) {
            val updated = entries.map { e ->
                if (e.text == text) {
                    e.copy(picked = (e.picked + 1).coerceAtMost(MAX_COUNT), lastUsedAt = now)
                } else {
                    e
                }
            }
            write(key, updated, now)
            return
        }
        val entry = Entry(
            text = text,
            readings = readings,
            typed = 1,
            lastUsedAt = now,
            picked = 1,
        )
        val updated = (entries + entry)
            .sortedWith(compareByDescending<Entry> { score(it, now) }.thenByDescending { it.lastUsedAt })
            .take(MAX_PER_CODE)
        write(key, updated, now)
    }

    /**
     * 點選合成候選後 picked 加 1。
     */
    fun bumpPicked(code: String, text: String) {
        val key = normalizeCode(code)
        if (key.isEmpty() || text.length < 2) return
        val now = System.currentTimeMillis()
        var hit = false
        val updated = (byCode[key] ?: return).map { e ->
            if (e.text == text) {
                hit = true
                e.copy(picked = (e.picked + 1).coerceAtMost(MAX_COUNT), lastUsedAt = now)
            } else {
                e
            }
        }
        if (!hit) return
        write(key, updated, now)
    }

    /**
     * 記一次合成候選曝光，作為平滑 CTR 的弱負信號。
     * 曝光只影響評分；達清理條件後才刪除 App 記憶。
     */
    fun noteShown(code: String, text: String) {
        val key = normalizeCode(code)
        if (key.isEmpty()) return
        val entries = byCode[key] ?: return
        if (entries.none { it.text == text }) return
        val updated = entries.map { e ->
            if (e.text == text) e.copy(shown = (e.shown + 1).coerceAtMost(MAX_COUNT * 4)) else e
        }.filterNot { shouldPurge(it) }
        write(key, updated, System.currentTimeMillis())
    }

    private fun write(key: String, updated: List<Entry>, now: Long) {
        if (updated.isEmpty()) {
            byCode.remove(key)
            prefs?.edit()?.remove(key)?.apply()
        } else {
            byCode[key] = updated.toMutableList()
            prefs?.edit()?.putString(key, encode(updated))?.apply()
        }
        version++
        trimGlobal(now)
    }

    /**
     * 全部學習詞（面板匹配用：面板要像候選條一樣吃同一份「詞→讀音」）。
     *
     * 條目上限 [MAX_ENTRIES]=200，直接攤平即可，不需要索引。
     */
    internal fun allEntries(): List<Entry> = byCode.values.flatten()

    /** 某碼串下可以作為合成候選的詞條（已按分數降序，次數未達標的被過濾）。 */
    fun candidatesFor(code: String, now: Long = System.currentTimeMillis()): List<Entry> {
        val key = normalizeCode(code)
        if (key.isEmpty()) return emptyList()
        return visibleEntries(byCode[key] ?: return emptyList(), now)
    }

    /** 單測注入：直接塞一批詞條（不碰 SharedPreferences）。 */
    internal fun putForTest(code: String, entries: List<Entry>) {
        val key = normalizeCode(code)
        if (key.isEmpty()) return
        byCode[key] = entries.toMutableList()
        version++
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
