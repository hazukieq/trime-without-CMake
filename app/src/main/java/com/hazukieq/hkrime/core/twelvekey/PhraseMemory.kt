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

    /** Beta 先驗（平滑 CTR）：α=1、β=2 → 從沒被選過的詞 CTR 先驗 ≈ 1/3。 */
    const val CTR_ALPHA = 1.0
    const val CTR_BETA = 2.0

    /** 曝光折扣：展示算「弱负信号」，按 0.5 折算（位置/可见性差异，見 Google US8938463 的思路）。 */
    const val EXPOSURE_DISCOUNT = 0.5

    /**
     * 自動清理：曝光 ≥ 8 次、一次沒被選、手拼 ≤ 2 次 → 判定為誤造詞，直接刪。
     *
     * 門檻必须是「手拼 ≤ 2」，不能是 1：詞條要（typed + picked）≥ [MIN_COUNT] = 2 才會露面，
     * 若這裡寫 1，`typed ≤ 1 && picked == 0` 的詞條永遠露不了面，清理規則**永遠不可能觸發**
     * （2026-11-11 自查發現：手拼 2 次、露面 10 次沒人選的創制詞刪不掉，權重也不降）。
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
        /**
         * 墓碑：被判定為誤造詞（曝光很多、一次沒選）後**不刪條目**，只打標記。
         *
         * 為什麼不能刪：引擎自己也可能學到了同一條詞（去重後候選條上顯示的是引擎那條），
         * 一旦 App 記憶被刪，「連引擎那條一起藏掉」就失去依據，詞條立刻復活且再無負反饋
         * （2026-11-11 自查實測：清理後 `半截碼` 又被引擎原樣頂回第一位）。
         * 墓碑同時是「這條被否決過」的記錄，用戶再手拼一次（[applyRecord]）會自動解除。
         */
        val purged: Boolean = false,
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
     * 推薦分 = 鮮度 × 平滑 CTR × 需求（用戶 2026-11-11 定案，參考 Beta-Binomial 平滑、
     * 曝光偏置建模與「未點擊降權」的成熟做法）：
     *
     * ```
     * ctr   = (picked + α) / (picked + γ·shown + α + β)     // α=1, β=2, γ=0.5
     * fresh = 0.5^(齡 / 7 天)                               // 時間衰減
     * score = fresh × ctr × (1 + ln(1 + typed))             // 手拼需求，邊際遞減
     * ```
     *
     * 直覺：反复展示卻沒人選 → `shown` 漲、`ctr` 掉 → 自動沉底（誤造詞）；
     * 手拼或點選過 → `ctr` 與需求項一起漲 → 排前面。
     */
    fun score(entry: Entry, now: Long): Double {
        val age = (now - entry.lastUsedAt).coerceAtLeast(0L)
        val fresh = Math.pow(0.5, age.toDouble() / HALF_LIFE_MS.toDouble())
        val denom = entry.picked + EXPOSURE_DISCOUNT * entry.shown + CTR_ALPHA + CTR_BETA
        val ctr = (entry.picked + CTR_ALPHA) / denom
        val demand = 1.0 + Math.log1p(entry.typed.toDouble())
        return fresh * ctr * demand
    }

    /** 誤造詞：反复展示、一次沒選、也不曾手拼過 → 應該被清掉。 */
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
                        // 用戶又手拼了一次 = 重新表達需求：解除墓碑、曝光歸零重新觀察。
                        purged = false,
                        shown = 0,
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

    /** 編碼：一行一條詞（6 欄；舊版只有 4 欄，decode 時 shown/picked 補 0）。 */
    fun encode(entries: List<Entry>): String =
        entries.joinToString(LINE.toString()) { e ->
            listOf(
                e.text,
                e.readings,
                e.typed.toString(),
                e.lastUsedAt.toString(),
                e.shown.toString(),
                e.picked.toString(),
                if (e.purged) "1" else "0",
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
                val purged = parts.getOrNull(6) == "1"
                Entry(
                    text = text,
                    readings = parts[1],
                    typed = typed.coerceIn(1, MAX_COUNT),
                    lastUsedAt = last,
                    shown = shown.coerceAtLeast(0),
                    picked = picked.coerceAtLeast(0),
                    purged = purged,
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

    /** 點合成候選上屏：點選計數 +1（用戶 2026-11-11：「重複組成/選擇時權重也要變化」）。 */
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
     * 合成候選「顯示了但本輪沒被選」：曝光 +1（弱負信號）。
     *
     * 這是誤造詞判定的唯一信息來源（用戶 2026-11-11：反复出現在候選條卻沒人選，可能就是誤造）。
     * 曝光本身不刪詞，只透過 [score] 的平滑 CTR 讓它沉底；到 [PURGE_SHOWN] 次且從沒被選才清掉。
     */
    fun noteShown(code: String, text: String) {
        val key = normalizeCode(code)
        if (key.isEmpty()) return
        val entries = byCode[key] ?: return
        if (entries.none { it.text == text }) return
        val updated = entries.map { e ->
            if (e.text != text) return@map e
            val bumped = e.copy(shown = (e.shown + 1).coerceAtMost(MAX_COUNT * 4))
            // 曝光累積到門檻 → 打墓碑（保留條目，見 Entry.purged 的說明），不再刪。
            if (shouldPurge(bumped)) bumped.copy(purged = true) else bumped
        }
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

    /** 某碼串下可以作為合成候選的詞條（已按分數降序，次數未達標的被過濾）。 */
    /** 某碼串下**全部**詞條（不過濾可見門檻）——曝光/清理需要看還沒露面的那些。 */
    internal fun entriesFor(code: String): List<Entry> {
        val key = normalizeCode(code)
        if (key.isEmpty()) return emptyList()
        return byCode[key]?.toList() ?: emptyList()
    }

    /** 這條詞是否已達「可以顯示」的門檻（累計 ≥ [MIN_COUNT]、文字 ≥2 字、沒被判誤造）。 */
    fun isVisible(entry: Entry): Boolean =
        entry.text.length >= 2 && !isBanned(entry) && (entry.typed + entry.picked) >= MIN_COUNT

    /** 被否決過的詞（墓碑 或 剛好命中清理規則）：連引擎那條也要一起藏掉。 */
    fun isBanned(entry: Entry): Boolean = entry.purged || shouldPurge(entry)

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
