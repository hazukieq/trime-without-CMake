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

    /** 段碼 → 所有匹配音節（精確 + 簡拼前綴），供「分詞後按段選字」使用。 */
    private val codeToSyllables = HashMap<String, MutableList<Entry>>()

    /** 音節 → 該讀音的字（字典順序去重）。 */
    private val syllableToChars = LinkedHashMap<String, MutableList<String>>()

    /** 單字 → 音節（取字典裡第一次出現的讀音），用來在歧義時對齊第一個候選字。 */
    private val charTable = HashMap<String, String>()

    /**
     * 單字 → 通用頻度（由詞表統計生成，見 assets/rime/hakka_char_freq.txt）。
     *
     * 展開面板「音節 → 漢字」列表用來把常用字排前、生僻字排後；詞典本身沒有 weight，
     * 只能靠詞表統計的出現次數當代理指標。
     */
    private val charFreq = HashMap<String, Int>()

    /**
     * 單字 → 權威字頻（來自 rime-ice 的《通用規範漢字表》8105 字表語料權重，
     * 見 assets/rime/hakka_char_rank.txt）。
     *
     * 詞表統計在客家話詞表裡覆蓋不全，且同為生僻字時次序也不可靠；
     * 這裡用「已發布的高頻字表」當硬排序依據：不在 8105 字表裡的字權重為 0（視為生僻）。
     */
    private val charRank = HashMap<Int, Int>()

    /**
     * 單字 → 綜合通用頻度分（0..10000），見 [generalScore]。
     *
     * 由兩份表各自換算成百分位後取較大值：在**任一**領域裡常見的字都排在前面
     * （客家話詞表覆蓋不到的普通話常用字、8105 表覆蓋不到的方言字都不會被埋掉）。
     */
    private val generalScores = HashMap<Int, Int>()

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        runCatching { load(context) }
            .onFailure { Timber.w(it, "TwelveKeyPreedit: load dict failed") }
    }

    private fun load(context: Context) {
        val counts = context.assets.open("rime/hakka.dict.yaml")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines -> loadDictLines(lines) }
        // 通用字頻（可選資源，缺失不影響主流程）
        runCatching {
            context.assets.open("rime/hakka_char_freq.txt").bufferedReader(Charsets.UTF_8)
                .useLines { lines -> loadCharFreqLines(lines) }
            Timber.i("TwelveKeyPreedit: charFreq=%d", charFreq.size)
        }.onFailure { Timber.w(it, "TwelveKeyPreedit: load char freq failed") }

        // 權威字頻（可選資源；缺失時退回只用詞表統計）
        runCatching {
            context.assets.open("rime/hakka_char_rank.txt").bufferedReader(Charsets.UTF_8)
                .useLines { lines -> loadCharRankLines(lines) }
            Timber.i("TwelveKeyPreedit: charRank=%d", charRank.size)
        }.onFailure { Timber.w(it, "TwelveKeyPreedit: load char rank failed") }

        finishLoading(counts)
    }

    /**
     * 從詞典行（`字<TAB>音節`）建立字表/音節表，返回「音節 → 出現次數」。
     *
     * 抽出來是為了單測：JVM 測試讀 assets 文件的行直接餵進來，驗證的正是 App 實際用的邏輯。
     */
    internal fun loadDictLines(lines: Sequence<String>): HashMap<String, Int> {
        val counts = HashMap<String, Int>()
        lines.forEach { raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@forEach
            val tab = raw.indexOf('\t')
            if (tab < 0) return@forEach
            val ch = raw.substring(0, tab).trim()
            val syllable = raw.substring(tab + 1).trim()
            if (ch.length == 1) {
                charTable.putIfAbsent(ch, syllable)
                val chars = syllableToChars.getOrPut(syllable) { ArrayList() }
                if (!chars.contains(ch)) chars.add(ch)
            }
            if (syllable.isNotEmpty() && syllable.all { it in 'a'..'z' }) {
                counts[syllable] = (counts[syllable] ?: 0) + 1
            }
        }
        return counts
    }

    internal fun loadCharFreqLines(lines: Sequence<String>) {
        lines.forEach { raw ->
            if (raw.isEmpty() || raw.startsWith("#")) return@forEach
            val tab = raw.indexOf('\t')
            if (tab <= 0) return@forEach
            val ch = raw.substring(0, tab)
            val v = raw.substring(tab + 1).trim().toIntOrNull() ?: return@forEach
            if (ch.length == 1) charFreq[ch] = v
        }
    }

    internal fun loadCharRankLines(lines: Sequence<String>) {
        lines.forEach { raw ->
            if (raw.isEmpty() || raw.startsWith("#")) return@forEach
            val tab = raw.indexOf('\t')
            if (tab <= 0) return@forEach
            val ch = raw.substring(0, tab)
            val v = raw.substring(tab + 1).trim().toIntOrNull() ?: return@forEach
            // 8105 表含 205 個擴展 B 區字（UTF-16 佔兩個 char），按碼點存取
            if (ch.isNotEmpty() && Character.codePointCount(ch, 0, ch.length) == 1) {
                charRank[ch.codePointAt(0)] = v
            }
        }
    }

    /** 詞典行載入後統一收尾：音節次數 → code 表。 */
    internal fun finishLoading(counts: HashMap<String, Int>) {
        // 可重入：測試會在不同測試類別各自載入一次資源；這裡先把派生表清空再重建，
        // 避免同一個碼位的音節被重複 append（會讓「輪轉取 limit 個」變少）。
        table.clear()
        codeToSyllables.clear()
        counts.forEach { (syllable, freq) ->
            val code = codeOf(syllable)
            val old = table[code]
            if (old == null || old.freq < freq) table[code] = Entry(syllable, freq)
            codeToSyllables.getOrPut(code) { ArrayList() }.add(Entry(syllable, freq))
        }
        buildGeneralScores()
        Timber.i("TwelveKeyPreedit: syllables=%d codes=%d scores=%d", counts.size, table.size, generalScores.size)
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
     * 段碼 → 可能音節（例如 `5` → ke/le/ko…），精確碼優先，其次簡拼前綴；按字典頻次降序。
     * 給「分詞後按段選字」用（見 [com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder]）。
     */
    fun syllablesForSegment(code: String, limit: Int = 12): List<String> {
        if (code.isEmpty()) return emptyList()
        val exact = ArrayList<Entry>()
        val prefix = ArrayList<Entry>()
        codeToSyllables.forEach { (syllableCode, list) ->
            when {
                syllableCode == code -> exact.addAll(list)
                syllableCode.startsWith(code) -> prefix.addAll(list)
            }
        }
        val exactSorted = exact.sortedByDescending { it.freq }
        // 前綴命中要「按鍵覆蓋的字母」輪流取（i → g/h/i、5 → k/l、4 → m/n …），
        // 否則像 `i` 這種鍵會被字數最多的 g 系音節整批佔滿（g 系在詞典裡字最多），
        // 出現「只給 g 開頭、h/i 一個都沒有」的問題（用戶 2026-11-10 反饋）。
        val buckets = LinkedHashMap<Char, MutableList<Entry>>()
        prefix.forEach { e ->
            val key = e.syllable.firstOrNull() ?: '?'
            buckets.getOrPut(key) { ArrayList() }.add(e)
        }
        buckets.values.forEach { it.sortByDescending { e -> e.freq } }
        val order = buckets.entries.sortedByDescending { (_, list) -> list.first().freq }
            .map { it.value }
        val diversified = ArrayList<Entry>()
        var round = 0
        while (diversified.size < limit && order.any { round < it.size }) {
            order.forEach { list -> if (round < list.size) diversified.add(list[round]) }
            round++
        }
        return (exactSorted.map { it.syllable } + diversified.map { it.syllable })
            .distinct()
            .take(limit)
    }

    /**
     * 把碼串切成「第一個音節碼 + 其餘碼」，都必須能切出完整音節才返回（否則 null）。
     *
     * 用途：多音節輸入但引擎只給詞組候選時，展開面板要補一組「第一個音節的單字」；
     * 點單字上屏後把 [second] 重新餵回引擎繼續輸入。
     */
    fun splitFirstSyllable(codes: String): Pair<String, String>? {
        if (codes.length < 2) return null
        val total = decode(codes)?.split(' ')?.size ?: 0
        if (total == 0) {
            // 簡拼/首字母相拼（如 `nh`、`jkl`）：整串解不出完整音節，按「第一個碼位 + 其餘」切。
            // 面板左列由此得到「以該字母開頭的音節」組（n**、h**…）。
            return codes.substring(0, 1) to codes.substring(1)
        }
        if (total < 2) return null
        for (i in 1 until codes.length) {
            val head = codes.substring(0, i)
            val tail = codes.substring(i)
            val headSyllables = decodeSingle(head) ?: continue
            val tailSyllables = decode(tail)?.split(' ') ?: continue
            if (headSyllables.size == 1 && headSyllables.size + tailSyllables.size == total) {
                return head to tail
            }
        }
        return null
    }

    /** 音節 → 該讀音的字（字典順序；需要按常用度排時用 [rankChars]）。 */
    fun charsForSyllable(syllable: String, limit: Int = 60): List<String> =
        syllableToChars[syllable]?.take(limit) ?: emptyList()

    /** 單字的通用頻度（詞表統計；未收錄回 0）；多字詞取首字。 */
    fun charFrequency(text: String): Int =
        if (text.isEmpty()) 0 else charFreq[text.substring(0, 1)] ?: 0

    /**
     * 單字的綜合通用頻度分（0..10000）：詞表統計與權威字頻各取百分位，取較大值。
     *
     * 為什麼取 max：兩份表的「領域」不同 —— 客家話詞表覆蓋方言常用字（佢/冇/唔），
     * 權威字頻覆蓋普通話高頻字與生僻字分級（婼/姞 權重極低）；只要在任一表裡高頻，
     * 就不該被排到後面。
     */
    fun generalScore(text: String): Int =
        if (text.isEmpty()) 0 else generalScores[text.codePointAt(0)] ?: 0

    private fun buildGeneralScores() {
        fun percentile(values: List<Pair<Int, Int>>): Map<Int, Int> {
            if (values.isEmpty()) return emptyMap()
            val ordered = values.sortedByDescending { it.second }
            val size = ordered.size
            val out = HashMap<Int, Int>(size)
            ordered.forEachIndexed { rank, entry ->
                out[entry.first] = (size - rank) * 10000 / size
            }
            return out
        }

        val byFreq = percentile(charFreq.entries.map { it.key.codePointAt(0) to it.value })
        val byRank = percentile(charRank.entries.map { it.key to it.value })
        for ((cp, score) in byFreq) generalScores[cp] = score
        for ((cp, score) in byRank) {
            val old = generalScores[cp] ?: 0
            if (score > old) generalScores[cp] = score
        }
    }

    /**
     * 按「用戶關聯度 → 用戶常用字 → 通用字頻」排列同讀音的字，冷啟動時也有合理順序。
     *
     * @param anchor 剛上屏的前一個字（分詞逐段選字用，可為 null）
     */
    fun rankChars(chars: List<String>, anchor: Char?): List<String> =
        if (chars.size < 2) chars else chars.sortedWith(
            compareByDescending<String> {
                com.hazukieq.hkrime.core.twelvekey.SegmentMemory.bigramScore(anchor, it)
            }.thenByDescending { com.hazukieq.hkrime.core.twelvekey.SegmentMemory.charScore(it) }
                .thenByDescending { generalScore(it) },
        )

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

    /**
     * 改寫 librime 給的 preedit：**保留已選中的前綴**，只把尾部「未選中的鍵碼」換成拼音。
     *
     * librime 的 preedit 形如 `咧5e`（咧 = 已選字，5e = 剩餘碼）；之前把整串都當碼去 decode，
     * 前綴被丟掉 → 用戶選完第一個字後「字消失了」。這裡只切尾部連續 ASCII 當碼段。
     *
     * @return 改寫後文本；若沒有可改寫的碼段（例如已全部選完 `咧`）回傳 null，保持 librime 原樣
     */
    fun rewrite(preedit: String?, hint: String? = null): String? {
        if (preedit.isNullOrEmpty() || table.isEmpty()) return null
        var split = preedit.length
        while (split > 0 && preedit[split - 1].code < 128) split--
        if (split == preedit.length) return null          // 尾端沒有碼段：已選完
        val prefix = preedit.substring(0, split)
        val codes = preedit.substring(split)
        val decoded = decode(codes, hint) ?: return null
        return prefix + decoded
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
