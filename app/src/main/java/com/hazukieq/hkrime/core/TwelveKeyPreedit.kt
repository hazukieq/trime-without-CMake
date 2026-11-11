package com.hazukieq.hkrime.core

import android.content.Context
import com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning
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
    /**
     * App 側「已選但還沒上屏」的字（面板段組字在引擎候選裡找不到時走兜底，先累積在這裡）。
     *
     * 用戶 2026-11-11：「用戶都沒選完時不應該只在 preedit 嗎？怎麼能突然 commit」——
     * 於是兜底不再立刻 `commitText`，而是把字暫存進來、由 [Rime.getComposition] 拼在 preedit 前面，
     * 等整串選完（或引擎 commit）再一次性上屏。
     */
    @Volatile
    @JvmField
    var pendingPrefix: String = ""


    private class Entry(val syllable: String, val freq: Int)

    private val table = HashMap<String, Entry>()

    /** 段碼 → 所有匹配音節（精確 + 簡拼前綴），供「分詞後按段選字」使用。 */
    private val codeToSyllables = HashMap<String, MutableList<Entry>>()

    /** 音節 → 該讀音的字（字典順序去重）。 */
    private val syllableToChars = LinkedHashMap<String, MutableList<String>>()

    /** 單字 → 音節（取字典裡第一次出現的讀音），用來在歧義時對齊第一個候選字。 */
    private val charTable = HashMap<String, String>()
    /**
     * 兩字詞的「聯想索引」：key = (第一字音節, 第二字音節首字母)，value = 詞 → 出現次數。
     *
     * 詞表（hakka_sougou_words / mobilewords / oftenwords / places / poems / website / zon / cn_en）
     * 只存詞條、沒有讀音，所以用 [charTable]（hakka.dict.yaml 的 `字<TAB>音節`）反推：
     * 這樣才能回答「song 開頭、下一個音節是 g 開頭」有哪些詞 —— 也就是用戶要的 `song g**`。
     */
    private val assocWords = HashMap<String, LinkedHashMap<String, Int>>()

    /**
     * 詞條的整串碼：`Linux` → tokens `[li, nak, si]` → `5i4a57i`。
     *
     * 聯想索引（assocByFirst）只按「前兩個音節」建 key，三音節以上的詞（`Linux` = `li nak si`）
     * 打到第三節就查不到（用戶 2026-11-11：`li nak s` 面板沒有 Linux）。這裡額外保一份
     * 「整串碼 + 讀音 + 詞面」的表，查詢端直接做碼串前綴匹配，天然支援任意音節數與半截碼。
     */
    private data class WordCodeEntry(
        val tokens: List<String>,
        val word: String,
        val code: String,
    )

    private val wordCodeEntries = ArrayList<WordCodeEntry>()

    /**
     * 只能出現在**詞尾**的音節（用戶 2026-11-11：「关于 r，这是一个儿后缀的读音。
     * 所以它默认不做首字母拼，只能出现在后面，这是特殊规则」）。
     *
     * 規則：`r` 不參與首字母簡拼展開、不能當第一段/中間段；只有「該段後面已經沒有碼」
     * （即它是這次輸入的最後一個音節）時才允許出現。
     */
    internal val SUFFIX_ONLY_SYLLABLES = setOf("r")

    internal fun isSuffixOnlySyllable(syllable: String): Boolean = syllable in SUFFIX_ONLY_SYLLABLES

    /** 客家話音節集合（由主詞典「字→讀音」建立）；詞表解析只認集合內的音節。 */
    private val syllableSet = HashSet<String>()

    /**
     * 已發布的整串碼索引（**不可變**，[finishWordIndex] 時整體替換）。
     *
     * 不能在查詢端直接遍歷 [wordCodeEntries]：詞表在後台線程邊讀邊加，按鍵線程同時遍歷會
     * `ConcurrentModificationException` —— 那會讓整輪面板組裝（`buildPanelRows`）拋異常、所有組
     * 一起消失（用戶 2026-11-11：「連單音節和聯想詞分組全部消失了」）。
     */
    @Volatile
    private var wordCodeIndex: List<WordCodeEntry> = emptyList()

    /**
     * 聯想詞的**查詢索引**：第一音節 → (第二音節 → 詞列表)。
     *
     * 面板每次按鍵都會重建快照，如果查詢時還要遍歷所有 8 千多個「第一音節|第二音節」鍵
     * 並 split 字符串，按鍵線程會明顯卡頓（用戶 2026-11-10 反饋「補聯想詞後來卡了」）。
     * 這裡在詞表載入完成後一次性建好，查詢只做兩次 HashMap 查找。
     */
    @Volatile
    private var assocByFirst: Map<String, Map<String, List<String>>> = emptyMap()

    /**
     * 「讀音首字母序列」→ [(reading, words)] 的索引（面板 `INITIAL_COMBO` 源用）。
     *
     * `ia8` 兩個鍵分別能起 g/h 與 z/j → 查 `gz`/`gj`/`hz`/`hj` 四組真實詞條，
     * 行標題仍是具體讀音（`ga zi`、`han ziu`…）。索引同時收「兩音節聯想詞」與
     * 「帶讀音的多音節詞」（三音節以上 `Linux` 這類），不依賴詞表是否有讀音列。
     */
    @Volatile
    /**
     * 音節首字母 → 該首字母下的全部音節（字典序）。
     *
     * 聯想行（`ASSOC` 源）改按**首字母序列**匹配後用它：碼只約束每個音節的首字母，
     * 元音完全自由（用戶 2026-11-11：「必須走首字母序列匹配，不是碼前綴」）。
     */
    private var syllablesByInitial: Map<Char, List<String>> = emptyMap()

    private var initialPatternIndex: Map<String, List<Pair<String, List<String>>>> = emptyMap()

    /**
     * 碼位 → 該鍵能作音節首字母的字母（**非末鍵**版，已排除元音與後綴 `r`）。
     *
     * 給面板 `INITIAL_COMBO` 源用：`ia8` 每鍵各取一個首字母組成「g·b / h·c…」通配組合，
     * 再按組合去詞表找具體讀音（行標題仍是 `gang bung` 這種真音節）。
     * 用 [TwelveKeyTuning.INITIAL_COMBO_PATTERN_LIMIT] 封頂組合數，避免 2^k 爆炸。
     */
    @Volatile
    private var initialLettersByCode: Map<Char, List<Char>> = emptyMap()

    /** 同 [initialLettersByCode]，但**允許末鍵**貢獻後綴音節首字母（`r`）。 */
    @Volatile
    private var initialLettersByCodeLast: Map<Char, List<Char>> = emptyMap()

    // ---- 只讀診斷（性能門禁 PerfGate / 單測用）----
    fun debugSyllableCount(): Int = table.size
    fun debugCodeCount(): Int = codeToSyllables.size
    fun debugCharFreqCount(): Int = charFreq.size
    fun debugCharRankCount(): Int = charRank.size
    fun debugAssocReady(): Boolean = assocReady
    fun debugAssocCount(): Int = assocByFirst.size

    /** 詞表索引是否已就緒（詞表在後台線程載入，沒就緒時面板只是暫時沒有聯想詞行）。 */
    @Volatile
    private var assocReady = false

    /** 聯想詞索引版本：後台載入完成後 +1，輸入法用它讓候選快照緩存失效。 */
    @Volatile
    var assocGeneration: Int = 0
        private set

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

    /** 單次 `syllablesForSegment` 的默認總上限（集中在 [TwelveKeyTuning.MAX_SYLLABLES_TOTAL]）。 */
    private const val MAX_SYLLABLES_TOTAL = TwelveKeyTuning.MAX_SYLLABLES_TOTAL

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        runCatching { load(context) }
            .onFailure { Timber.w(it, "TwelveKeyPreedit: load dict failed") }
    }

    private fun load(context: Context) {
        // 共享目錄（/sdcard/hkrime）優先、assets 兜底；並展開 import_tables（見 [RimeDictSource]）。
        // 用戶 2026-11-09 反饋「往詞庫加新條目不生效」，根因就是這裡只讀 APK 內置字典。
        val t0 = System.nanoTime()
        var counts = loadDictLines(RimeDictSource.body(context, MAIN_DICT).asSequence())
        if (counts.isEmpty()) {
            // 共享目錄那份讀到了卻解析不出音節（文件半截/格式壞了）：退回 APK 內置字典，
            // 否則段組/讀音/聯想詞會整片消失，比「看不到新詞」糟得多。
            Timber.e("TwelveKeyPreedit: shared dict unusable, falling back to assets")
            runCatching {
                context.assets.open("rime/$MAIN_DICT.dict.yaml").bufferedReader(Charsets.UTF_8)
                    .use { r -> counts = loadDictLines(r.readLines().asSequence()) }
            }.onFailure { Timber.w(it, "TwelveKeyPreedit: assets dict fallback failed") }
        }
        Timber.i(
            "TwelveKeyPreedit: dict=%s lines=%d load=%dms",
            RimeDictSource.describe(context, MAIN_DICT),
            counts.size,
            (System.nanoTime() - t0) / 1_000_000,
        )
        // 通用字頻（可選資源，缺失不影響主流程）
        runCatching {
            RimeDictSource.textLines(context, "hakka_char_freq.txt")?.let { lines ->
                loadCharFreqLines(lines.asSequence())
            }
            Timber.i("TwelveKeyPreedit: charFreq=%d", charFreq.size)
        }.onFailure { Timber.w(it, "TwelveKeyPreedit: load char freq failed") }

        // 權威字頻（可選資源；缺失時退回只用詞表統計）
        runCatching {
            RimeDictSource.textLines(context, "hakka_char_rank.txt")?.let { lines ->
                loadCharRankLines(lines.asSequence())
            }
            Timber.i("TwelveKeyPreedit: charRank=%d", charRank.size)
        }.onFailure { Timber.w(it, "TwelveKeyPreedit: load char rank failed") }

        finishLoading(counts)

        // 兩字詞聯想索引（song gong 這類聯想行）——詞表只有詞條、沒有讀音，
        // 依賴上面 finishLoading 建好的 charTable 反推，所以必須放在它後面。
        // 8 個詞表合計 2MB+，同步讀會讓第一次按鍵卡住（用戶 2026-11-10 反饋），
        // 因此丟到後台線程：面板在索引就緒前只是暫時沒有聯想詞行。
        val appContext = context.applicationContext
        Thread({
            val t0 = System.nanoTime()
            runCatching {
                // 詞表清單 = 主詞典 `import_tables` 展開後的其餘詞典（共享目錄優先），
                // 讀不到就退回內置的 [DEFAULT_WORD_LISTS]——這樣用戶新建詞表掛進
                // import_tables 也能被聯想索引看到，不必改代碼。
                val names = RimeDictSource.namesWithImports(appContext, MAIN_DICT)
                    .filter { it != MAIN_DICT }
                    .ifEmpty { DEFAULT_WORD_LISTS }
                names.forEach { name ->
                    loadWordLines(RimeDictSource.body(appContext, name).asSequence())
                }
                finishWordIndex()
                Timber.i(
                    "TwelveKeyPreedit: assoc keys=%d byFirst=%d lists=%s load=%dms",
                    assocWords.size, assocByFirst.size, names.joinToString(","),
                    (System.nanoTime() - t0) / 1_000_000,
                )
            }.onFailure { Timber.w(it, "TwelveKeyPreedit: load word lists failed") }
        }, "twelvekey-assoc").apply {
            isDaemon = true
            start()
        }
    }

    /** 主詞典名（`hakka.dict.yaml`）；它自己的 `import_tables` 決定聯想詞要讀哪些詞表。 */
    private const val MAIN_DICT = "hakka"

    /** 讀不到共享目錄基準詞典時的內置詞表清單（發佈版原樣行為）。 */
    private val DEFAULT_WORD_LISTS = listOf(
        "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords", "hakka_places",
        "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
    )

    /**
     * 從詞典行（`字<TAB>音節`）建立字表/音節表，返回「音節 → 出現次數」。
     *
     * 抽出來是為了單測：JVM 測試讀 assets 文件的行直接餵進來，驗證的正是 App 實際用的邏輯。
     */
    /**
     * 音節集合**只從主詞典取**（`finishLoading(counts)` 的 counts.keys）。
     *
     * 用戶 2026-11-11 定調：「實際上 hakka 字典沒有的音節，注定不會有字，所以為了減少性能消耗，
     * 默認獲取 hakka 字典的音節集合」——
     * - 詞表解析（聯想/整串詞行）用它做校驗：讀音含集合外音節（`li xing` 的 `xing`）整條丟棄；
     * - 不再讀 `assets/rime/hakka_syllables.txt`：那份是**標準集定稿（841 個）**，用途是
     *   **約束 `tools/panel_probe.py` / `RealCorpusTest` 等腳本與測試**，不是 App 運行時資料源；
     * - 這樣少一次 asset 讀取+解析，也天然保證「有字 ⇒ 有音節 ⇒ 可用」。
     */
    internal fun applySyllableSet(text: String): Int {
        val parsed = LinkedHashSet<String>()
        text.lineSequence().forEach { line ->
            line.substringBefore('#').trim().split('\u0020', '\t')
                .filter { it.isNotEmpty() && it.all(Char::isLetter) }
                .forEach { parsed.add(it) }
        }
        if (parsed.isEmpty()) return 0
        syllableSet.clear()
        syllableSet.addAll(parsed)
        return parsed.size
    }

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

    /**
     * 詞表行 → 兩字詞聯想索引（測試可單獨餵 fixture）。
     *
     * 只收「長度 2、全 CJK」的詞；讀音取 [charTable] 的單字首讀音，缺失就跳過。
     */
    internal fun loadWordLines(lines: Sequence<String>) {
        lines.forEach { raw ->
            val line = raw.trim().removeSuffix("\r")
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val tab = line.indexOf('\t')
            val word = (if (tab >= 0) line.substring(0, tab) else line).trim()
            if (word.isEmpty()) return@forEach
            // 詞表有兩種格式：
            //  1) `詞<TAB>讀音`（hakka.dict / hakka_zon 這種）：讀音列直接可用，
            //     不必拿 charTable 反推——`Linux<TAB>li nak si` 這種**非漢字詞**也只能靠它；
            //  2) 純詞表（sougou/places/poems…）：只有詞面，用 charTable 反推前兩字讀音。
            // 第 2 欄是讀音，第 3 欄起是權重/頻次（`Linux\tli nak si\t1000`）——只取第 2 欄。
            // 否則最後一節會變成 `si\t1000`，codeOf 出來的整串碼對不上輸入（用戶 2026-11-11：
            // `li nak s` 面板沒有 Linux，根因正是權重欄污染了讀音）。
            val reading = if (tab >= 0) line.substring(tab + 1).split('\t')[0].trim() else ""
            val syllables = if (reading.isNotEmpty()) {
                reading.split(' ', '\u3000')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.all(Char::isLetter) }
            } else {
                if (word.length < 2) return@forEach
                val r1 = charTable[word.substring(0, 1)] ?: return@forEach
                val r2 = charTable[word.substring(1, 2)] ?: return@forEach
                listOf(r1, r2)
            }
            // 這是「聯想索引只收雙音節以上詞」的詞長判斷，不是面板分檔（docs/19 §9.1 禁的是分檔）。
            if (syllables.size < 2) return@forEach
            // 只認**客家話音節集合**裡的音節：目錄裡可能還有 luna_pinyin 這類別的方案詞表
            // （`例行 → li xing`），把它們當音節用會讓面板長出根本不是客家話的聯想行。
            // 主詞典沒載入（syllableSet 為空，例如單測只餵詞表）時跳過校驗。
            if (syllableSet.isNotEmpty() && syllables.any { it !in syllableSet }) return@forEach
            val r1 = syllables[0]
            val r2 = syllables[1]
            if (r1.isEmpty() || r2.isEmpty()) return@forEach
            // 詞長：漢字詞 2~4 字（三/四字詞整詞上屏更有用，用戶 2026-11-10）；
            // 非漢字詞（Linux、iPhone…）放寬——它們通常只有 1 個「詞」，靠讀音定位。
            val cjkOnly = word.all { isCjk(it) }
            if (cjkOnly && word.length !in 2..4) return@forEach
            if (!cjkOnly && word.length !in 1..16) return@forEach
            val key = "$r1|$r2"
            val bucket = assocWords.getOrPut(key) { LinkedHashMap() }
            bucket[word] = (bucket[word] ?: 0) + 1
            // 整串碼索引（三音節以上的詞只能靠它）；簡拼/半截由查詢端做前綴匹配。
            //
            // **只收「詞表自帶讀音」的詞條**（第 2 欄）。純詞表（sougou/places…）是用
            // `charTable` 反推前兩字讀音的，本來就是兩音節、聯想索引已覆蓋，再存一份只會
            // 把条目数从 2 千撑到 17 萬（真机上詞表加载 1s → 18s、内存暴涨，用戶 2026-11-11
            // 撞到的「面板分组全消失」就是加载线程与按键线程抢这份大表）。
            if (reading.isNotEmpty()) {
                val fullCode = syllables.joinToString("") { codeOf(it) }
                if (fullCode.isNotEmpty()) {
                    wordCodeEntries.add(WordCodeEntry(syllables, word, fullCode))
                }
            }
        }
    }

    /** 診斷：整串碼表裡到底有沒有某個詞（真機排查「面板查不到」用；數量小，開銷可忽略）。 */
    fun debugWordCodeEntries(probeWord: String = "Linux"): String {
        val entries = wordCodeIndex
        val hits = entries.filter { it.word.contains(probeWord, ignoreCase = true) }
        return "wordCodes=${entries.size} " +
            "hits=" + hits.take(4).joinToString(";") { "${it.code}<-${it.tokens.joinToString(" ")}" }
    }

    /**
     * 單測用：清空已載入的詞表資料（聯想/整串碼/首字母索引）。
     *
     * 多個測試類別共用這個 object，`loadWordLines` 是累加的（會重複計數、影響
     * 「取哪幾條」的排序）。golden 對拍要求乾淨詞表，所以測試載入前先呼叫這個。
     * 正式執行路徑只有在初始化時載入一次，不需要它。
     */
    internal fun clearWordDataForTest() {
        assocWords.clear()
        wordCodeEntries.clear()
        wordCodeIndex = emptyList()
        assocByFirst = emptyMap()
        initialPatternIndex = emptyMap()
        assocReady = false
    }

    /** 詞表載入完後把索引整理成「按熱度降序」的列表，避免每次查詢都排序。 */
    internal fun finishWordIndex() {
        assocWords.values.forEach { bucket ->
            // 索引維持「熱度降序」：它決定「取哪幾條」（selection）。
            // 顯示時再對著截斷後的小列表按字母序排（display），兩件事分開（用戶 2026-11-10）。
            val sorted = bucket.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            bucket.clear()
            sorted.forEachIndexed { i, word -> bucket[word] = sorted.size - i }
        }
        // 建查詢索引：第一音節 → (第二音節 → 已排序詞列表)；先建好再整體替換，
        // 避免後台線程寫、按鍵線程讀同一個 Map 造成併發修改。
        val index = HashMap<String, LinkedHashMap<String, List<String>>>()
        assocWords.forEach { (key, bucket) ->
            val sep = key.indexOf('|')
            if (sep <= 0 || sep >= key.length - 1) return@forEach
            val first = key.substring(0, sep)
            val second = key.substring(sep + 1)
            if (second.isEmpty()) return@forEach
            index.getOrPut(first) { LinkedHashMap() }[second] = bucket.keys.toList()
        }
        assocByFirst = index
        assocReady = index.isNotEmpty()
        // 整體替換（讀者看到的是舊或新的完整列表，不會看到半截）
        wordCodeIndex = ArrayList(wordCodeEntries)
        initialPatternIndex = buildInitialPatternIndex(wordCodeEntries)
        assocGeneration++
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF
    }

    /**
     * 「以 [syllable] 為第一段、第二段以 [initial] 開頭」的聯想兩字詞，按熱度降序取前 [limit] 條。
     *
     * 用戶 2026-11-10：面板要列 `song g**` / `song h**` 這種聯想詞，而不是另一種切法標籤。
     */
    /**
     * 依「輸入碼前綴」查整詞（任意音節數，容許最後一節半截）：
     * `5i4a57` → 讀音 `li nak si` → `[Linux, linux]`。
     *
     * 只回傳音節數 ≥ 2 的詞（單字由段組負責）。行內按詞面字典序、行之間按行內詞數降序取前 [maxReadings]，
     * 與其它聯想行同構，面板可照同樣方式渲染。
     */
    fun wordsByCodePrefix(
        codes: String,
        maxReadings: Int = 4,
        perReading: Int = 6,
    ): List<Pair<String, List<String>>> {
        val prefix = codes.replace("'", "").replace(" ", "")
        val entries = wordCodeIndex
        if (prefix.isEmpty() || entries.isEmpty()) return emptyList()
        val rows = LinkedHashMap<String, LinkedHashSet<String>>()
        for (entry in entries) {
            if (entry.tokens.size < 2) continue
            // 第一個音節必須**打全**：詞行的第一段是「已完成的音節」，後面的碼才算第二段前綴。
            // 否則打 `ia`（= ga）時會把 `gam se`（= ia4|7e）這類「第一段還沒打完」的詞也列出來
            // （用戶 2026-11-11：`ia8i3` 面板只看到 `ga j/z*`，腳本卻列出 gam se）。
            val firstCode = codeOf(entry.tokens[0])
            if (firstCode.isEmpty() || !prefix.startsWith(firstCode)) continue
            if (!entry.code.startsWith(prefix)) continue
            rows.getOrPut(entry.tokens.joinToString(" ")) { LinkedHashSet() }.add(entry.word)
        }
        if (rows.isEmpty()) return emptyList()
        return rows.entries
            .sortedWith(compareByDescending<Map.Entry<String, LinkedHashSet<String>>> { it.value.size })
            .take(maxReadings)
            .map { it.key to it.value.sorted().take(perReading) }
            .sortedBy { it.first }
    }

    fun associatedWords(syllable: String, initial: Char, limit: Int = 6): List<String> {
        if (!assocReady) return emptyList()
        val bySecond = assocByFirst[syllable] ?: return emptyList()
        val merged = LinkedHashSet<String>()
        outer@ for ((second, words) in bySecond) {
            if (second.isEmpty() || second[0] != initial) continue
            for (word in words) {          // words 已按熱度降序
                merged.add(word)
                if (merged.size >= limit) break@outer    // 取哪幾條由熱度決定
            }
        }
        // 顯示按詞面字典序（用戶 2026-11-10：聯想詞也要按字母排）
        return merged.sorted()
    }

    /**
     * 以 [syllable] 為第一段、**第二段是確切音節**的聯想詞（用戶 2026-11-10：
     * 行標題要跟引擎一樣是完整讀音，不是 `h**` 這種通配）。
     *
     * 只保留第二音節首字母在 [initials] 裡的組合；每個讀音最多 [limitPerReading] 條，
     * 讀音行最多 [maxReadings] 個（按整桶熱度降序）。
     */
    fun associatedByReading(
        syllable: String,
        initials: Collection<Char>,
        limitPerReading: Int = 6,
        maxReadings: Int = 4,
    ): List<Pair<String, List<String>>> {
        if (initials.isEmpty() || !assocReady) return emptyList()
        val bySecond = assocByFirst[syllable] ?: return emptyList()
        val rows = ArrayList<Pair<String, List<String>>>(bySecond.size)
        bySecond.forEach { (second, words) ->
            if (second.isEmpty() || second[0] !in initials) return@forEach
            // 先按熱度取前 N 條，再按詞面字典序顯示
            rows.add(second to words.take(limitPerReading).sorted())
        }
        // selection：按「行內候選詞總量」熱度取前 maxReadings 行；
        // display：選出來的行再按讀音字典序排（用戶 2026-11-10）。
        // 注意順序：必須先截斷再排序，否則高熱度行會被字母序擠掉（`than` 就這麼丟過）。
        val chosen = rows.sortedByDescending { it.second.size }.take(maxReadings)
        return chosen.sortedBy { it.first }
    }

    /**
     * 「第一段確切音節 × 第二段以 [secondCode] 開頭的**確切音節**」的聯想行。
     *
     * 行標題一律是兩個完整讀音（`than dung`），不用 `d**` 這種通配——索引本來就是
     * (第一音節, 第二音節) 的哈希，精確查才用得上它（用戶 2026-11-10）。
     * 第二段候選音節從 [syllablesForSegment] 取（與面板同一份），每行按熱度取 [perRow] 條，
     * 行數封頂 [maxRows]（先按熱度選行，選完再按讀音字典序顯示）。
     */
    fun associatedBySegmentCode(
        syllable: String,
        secondCode: String,
        perRow: Int = 6,
        maxRows: Int = 2,
    ): List<Pair<String, List<String>>> {
        if (secondCode.isEmpty() || !assocReady) return emptyList()
        val bySecond = assocByFirst[syllable] ?: return emptyList()
        val seconds = syllablesForSegment(secondCode, 64)
        val rows = ArrayList<Pair<String, List<String>>>()
        for (second in seconds) {
            val words = bySecond[second] ?: continue
            if (words.isEmpty()) continue
            rows.add("$syllable $second" to words.take(perRow).sorted())
        }
        if (rows.isEmpty()) return emptyList()
        val chosen = rows.sortedByDescending { it.second.size }.take(maxRows)
        return chosen.sortedBy { it.first }
    }

    /**
     * 該碼位能覆蓋多少個音節（精確碼 + 前綴命中），面板用它決定「這個鍵音節構成是否豐富」：
     * 豐富的鍵放 24 個音節組，普通鍵放 16 個（用戶 2026-11-10）。
     */
    fun syllableCountForCode(code: String): Int {
        if (code.isEmpty()) return 0
        var n = 0
        codeToSyllables.forEach { (syllableCode, list) ->
            if (syllableCode == code || syllableCode.startsWith(code)) n += list.size
        }
        return n
    }

    /** 碼段的所有可能「下一個音節首字母」（去重、保持輪轉順序），用於決定列哪幾行聯想詞。 */
    fun initialsForPrefix(code: String, limit: Int = 2): List<Char> {
        if (code.isEmpty()) return emptyList()
        // 按「音節首字母」聚合前綴匹配到的音節（按詞典字數加權排序）；不要從
        // syllablesForSegment 前 N 條裡找——那種取法可能前 8 條全是同一首字母。
        val weightByInitial = LinkedHashMap<Char, Int>()
        codeToSyllables.forEach { (syllableCode, list) ->
            // 注意：`==` 也要收——像 `r` 這種「整碼就是一個音節」的鍵，
            // 它的首字母只會出現在精確碼裡，漏掉就會讓聯想詞只剩另一邊（用戶 2026-11-10 的 v/r 就是這個坑）。
            if (syllableCode.length < code.length || !syllableCode.startsWith(code)) return@forEach
            list.forEach { entry ->
                val initial = entry.syllable.firstOrNull() ?: return@forEach
                weightByInitial[initial] = (weightByInitial[initial] ?: 0) + entry.freq
            }
        }
        return weightByInitial.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
    }

    /** 由音節集合重建「碼位 → 可作首字母的字母」索引（[finishLoading] 後呼叫一次）。 */
    private fun rebuildInitialLetters() {
        val normal = HashMap<Char, MutableSet<Char>>()
        val last = HashMap<Char, MutableSet<Char>>()
        val vowels = TwelveKeyTuning.VOWEL_LETTERS
        syllableSet.forEach { syllable ->
            val initial = syllable.firstOrNull() ?: return@forEach
            if (initial in vowels) return@forEach
            val suffixOnly = isSuffixOnlySyllable(syllable)
            TwelveKeyTuning.KEY_LETTERS.forEach { (code, letters) ->
                if (initial !in letters) return@forEach
                if (!suffixOnly) normal.getOrPut(code) { LinkedHashSet() }.add(initial)
                last.getOrPut(code) { LinkedHashSet() }.add(initial)
            }
        }
        initialLettersByCode = normal.mapValues { it.value.sorted() }
        initialLettersByCodeLast = last.mapValues { it.value.sorted() }
        // 聯想行用的「首字母 → 全部音節」索引：只收字典音節集合，按字典序固定，
        // 兩份實現（App/腳本）才能逐行對拍（docs/19 §9.1）。
        syllablesByInitial = syllableSet
            .filter { it.isNotEmpty() }
            .groupBy { it.first() }
            .mapValues { (_, list) -> list.sorted() }
    }

    /**
     * 首字母集合 → 這些字母打頭的全部音節（字典序、去重）。
     *
     * 面板聯想行（`ASSOC`）用：每個音節只由「該音節首字母」約束，元音不固定。
     * 與 `tools/panel_probe.py` 的 `syllables_by_initials()` 同口徑。
     */
    internal fun syllablesByInitials(initials: Collection<Char>): List<String> {
        if (initials.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        initials.forEach { initial ->
            syllablesByInitial[initial]?.forEach { syllable ->
                if (seen.add(syllable)) out.add(syllable)
            }
        }
        return out
    }

    /**
     * 某個碼位能作音節首字母的字母（排除元音；後綴 `r` 只在 [isLast] 時出現）。
     *
     * 與 `tools/panel_probe.py` 的 `key_initial_letters()` 同口徑；首字母集合取自
     * 客家話音節集合（主詞典），不讀任何外部資產。
     */
    internal fun keyInitialLetters(codeChar: Char, isLast: Boolean): List<Char> =
        (if (isLast) initialLettersByCodeLast else initialLettersByCode)[codeChar] ?: emptyList()

    /**
     * 每鍵取一個音節首字母的笛卡爾積（`ia8` → `(g|h)(z|j)` 4 組）。
     *
     * [TwelveKeyTuning.INITIAL_COMBO_PATTERN_LIMIT] 是**組合數**上限，不是鍵數上限：
     * `5i4a57` 6 鍵恰好 64 組仍生成，7 鍵以上通常超限 → 整組不生成。
     * 任一鍵沒有任何可用首字母（純元音鍵、`0` 等）也整組不生成。
     */
    internal fun initialCombos(codes: String): List<List<Char>> {
        val clean = codes.replace("'", "")
        if (clean.isEmpty()) return emptyList()
        var combos: List<List<Char>> = listOf(emptyList())
        for ((index, ch) in clean.withIndex()) {
            val letters = keyInitialLetters(ch, isLast = index == clean.lastIndex)
            if (letters.isEmpty()) return emptyList()
            combos = combos.flatMap { prefix -> letters.map { prefix + it } }
            if (combos.size > TwelveKeyTuning.INITIAL_COMBO_PATTERN_LIMIT) return emptyList()
        }
        return combos
    }

    /**
     * 按「讀音首字母序列」查詞表：`[g, b]` → `gang bung`、`gau boi`…
     *
     * 只回傳**音節數與 [pattern] 相同、且每個音節首字母都對上**的詞條（對應
     * `panel_probe.py` 的 `by_initials`）。行內按讀音字典序分組，供面板取前幾條。
     */
    /** 由「聯想詞索引 + 整串碼索引」建 [initialPatternIndex]（[finishWordIndex] 時整體替換）。 */
    private fun buildInitialPatternIndex(
        entries: List<WordCodeEntry>,
    ): Map<String, List<Pair<String, List<String>>>> {
        // key = 各音節首字母拼接；value = reading → 詞列表（保持字典序，面板取前幾條要穩定）
        val byPattern = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        fun add(tokens: List<String>, word: String) {
            if (tokens.size < 2) return
            val key = tokens.joinToString("") { it.firstOrNull()?.toString() ?: "" }
            if (key.length != tokens.size) return
            val reading = tokens.joinToString(" ")
            byPattern.getOrPut(key) { LinkedHashMap() }
                .getOrPut(reading) { LinkedHashSet() }
                .add(word)
        }
        // 兩音節詞（聯想索引）——純詞表反推出來的也在這裡，是面板最常用的來源。
        assocWords.forEach { (key, bucket) ->
            val sep = key.indexOf('|')
            if (sep <= 0 || sep >= key.length - 1) return@forEach
            val tokens = listOf(key.substring(0, sep), key.substring(sep + 1))
            bucket.keys.forEach { word -> add(tokens, word) }
        }
        // 三音節以上的詞（帶讀音的詞表才有）。
        entries.forEach { entry -> add(entry.tokens, entry.word) }
        return byPattern.mapValues { (_, rows) ->
            rows.entries.sortedBy { it.key }.map { it.key to it.value.toList() }
        }
    }

    /**
     * 按「讀音首字母序列」查詞表：`[g, b]` → `gang bung`、`gau boi`…
     *
     * 只回傳**音節數與 [pattern] 相同、且每個音節首字母都對上**的詞條
     * （對應 `panel_probe.py` 的 `by_initials`）。行內按讀音字典序。
     */
    internal fun wordsByInitialPattern(pattern: List<Char>): List<Pair<String, List<String>>> {
        if (pattern.size < 2) return emptyList()
        return initialPatternIndex[pattern.joinToString("")] ?: emptyList()
    }

    /**
     * 第一音節 → (第二音節 → 詞列表) 的原始聯想索引（面板 `ASSOC` 源分桶用）。
     *
     * 返回的是**已按熱度降序**的詞列表；沒有聯想詞回空 map。
     */
    internal fun assocSecondMap(first: String): Map<String, List<String>> = assocByFirst[first] ?: emptyMap()

    /** 詞典行載入後統一收尾：音節次數 → code 表。 */
    internal fun finishLoading(counts: HashMap<String, Int>) {
        // 可重入：測試會在不同測試類別各自載入一次資源；這裡先把派生表清空再重建，
        // 避免同一個碼位的音節被重複 append（會讓「輪轉取 limit 個」變少）。
        table.clear()
        codeToSyllables.clear()
        // 音節集合 = 主詞典自帶音節（用戶 2026-11-11：「字典沒有的音節注定不會有字，
        // 為減少性能消耗，默認取 hakka 字典的音節集合」）。標準集資產只約束腳本/測試。
        syllableSet.clear()
        syllableSet.addAll(counts.keys)
        counts.forEach { (syllable, freq) ->
            val code = codeOf(syllable)
            val old = table[code]
            if (old == null || old.freq < freq) table[code] = Entry(syllable, freq)
            codeToSyllables.getOrPut(code) { ArrayList() }.add(Entry(syllable, freq))
        }
        buildGeneralScores()
        rebuildInitialLetters()
        Timber.i("TwelveKeyPreedit: syllables=%d codes=%d scores=%d", counts.size, table.size, generalScores.size)
    }

    /** 拼音 → 12 鍵碼（對應 alphabet 的 abbrev/xform/xlit）。internal 供單測做鍵位覆蓋校驗。 */
    internal fun codeOf(syllable: String): String {
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
    fun syllablesForSegment(
        code: String,
        limit: Int = 12,
        exactOnly: Boolean = false,
        totalLimit: Int = MAX_SYLLABLES_TOTAL,
        allowSuffixOnly: Boolean = false,
    ): List<String> {
        if (code.isEmpty()) return emptyList()
        // 後綴音節（`r`）只在「這一段就是輸入的結尾」時才允許；見 [SUFFIX_ONLY_SYLLABLES]。
        fun allowed(e: Entry): Boolean = allowSuffixOnly || !isSuffixOnlySyllable(e.syllable)
        val exact = ArrayList<Entry>()
        // 前綴命中記住「code 之後的下一個碼位」：分桶時用它，`w`（th 鍵）才能同時給出
        // than（下一個 a）與 the（下一個 e）；只按音節首字母分桶時 th* 全是 t、the 系列會擠掉 than。
        val prefix = ArrayList<Pair<Char, Entry>>()
        codeToSyllables.forEach { (syllableCode, list) ->
            when {
                syllableCode == code -> exact.addAll(list.filter(::allowed))
                !exactOnly && syllableCode.length > code.length && syllableCode.startsWith(code) ->
                    list.filter(::allowed).forEach { prefix.add(syllableCode[code.length] to it) }
            }
        }
        // 同頻時按音節字典序（不能用插入順序，HashMap 的迭代順序不可依賴；
        // 腳本 `panel_probe.py` 用同一 key，golden 才能逐行一致）。
        val exactSorted = exact.sortedWith(compareByDescending<Entry> { it.freq }.thenBy { it.syllable })
        // 前綴命中要同時滿足兩件事：
        // 1) 「下一個碼位」多樣：`w`（th 鍵）要能給 than（下一個 a）/ the（下一個 e）…
        // 2) 「音節首字母」多樣：`i`（ghi 鍵）要 g/h/i 交替，不能整批 g 系。
        // 所以做兩級桶：外層 next-code，內層 syllable-initial，兩層輪轉。
        val outer = LinkedHashMap<Char, LinkedHashMap<Char, MutableList<Entry>>>()
        prefix.forEach { (nextCode, e) ->
            val initial = e.syllable.firstOrNull() ?: '?'
            outer.getOrPut(nextCode) { LinkedHashMap() }
                .getOrPut(initial) { ArrayList() }
                .add(e)
        }
        outer.values.forEach { inner ->
            inner.values.forEach { list ->
                list.sortWith(compareByDescending<Entry> { it.freq }.thenBy { it.syllable })
            }
        }
        // 桶順序必須**完全確定**（不能依賴 HashMap 的迭代順序）：外層按「桶內最高頻」降序、
        // 同頻按 next-code 碼位；內層按音節首字母。腳本 `panel_probe.py` 用同一 key 排序，
        // golden 對拍才能逐行一致（docs/19 §6「純函數可測」）。
        val order = outer.entries
            .sortedWith(
                compareByDescending<Map.Entry<Char, LinkedHashMap<Char, MutableList<Entry>>>> { entry ->
                    entry.value.values.maxOfOrNull { it.first().freq } ?: 0
                }.thenBy { it.key }
            )
            .map { it.value }
        // limit = **每個首字母**最多取幾個（用戶 2026-11-10：常數應對應「各取一份」而不是平分）；
        // 總數另有上限 MAX_SYLLABLES_TOTAL，避免一個鍵覆蓋 3 個聲母時左列過長。
        val distinctExact = exactSorted.map { it.syllable }.distinct()
        val totalCap = maxOf(limit, minOf(limit * order.size, totalLimit))
        val diversified = ArrayList<Entry>()
        val perInitialSeen = HashMap<Char, Int>()
        // 每個首字母的名額：至少 limit，但當一個鍵只覆蓋一個首字母時（`w` = th*，全是 t），
        // 名額不能還是 8，否則 the 系列佔滿、than 之外的其他 next-code 桶全被截掉。
        val initialCount = order.sumOf { it.size }.coerceAtLeast(1)
        val perInitialCap = maxOf(limit, totalCap / initialCount)
        var round = 0
        while (diversified.size + distinctExact.size < totalCap &&
            order.any { inner -> inner.values.any { it.size > round } }
        ) {
            order.forEach { inner ->
                inner.entries.sortedBy { it.key }.forEach { (initial, list) ->
                    if (round < list.size && (perInitialSeen[initial] ?: 0) < perInitialCap &&
                        diversified.size + distinctExact.size < totalCap
                    ) {
                        diversified.add(list[round])
                        perInitialSeen[initial] = (perInitialSeen[initial] ?: 0) + 1
                    }
                }
            }
            round++
        }
        // 排序規則（用戶 2026-11-10）：先按首字母、再按次字母……即讀音字典序，
        // 這樣哪怕高頻音節少、也能靠字母位置快速找到；精確碼（整段音節）仍固定排第一。
        return (distinctExact + diversified.map { it.syllable }
            .distinct()
            .sorted())
            .distinct()
            .take(maxOf(limit, totalCap))
    }

    /**
     * 把碼串切成「第一個音節碼 + 其餘碼」，都必須能切出完整音節才返回（否則 null）。
     *
     * 用途：多音節輸入但引擎只給詞組候選時，展開面板要補一組「第一個音節的單字」；
     * 點單字上屏後把 [second] 重新餵回引擎繼續輸入。
     */
    /**
     * 一個碼串可能有多種「合法第一段」切法：`744ii` 可以是 `744|ii`（son+gi，引擎給的那種），
     * 也可以是 `744i|i`（song + 以 g/h 開頭的下一段）。只列引擎挑中的那種，用戶就看不到
     * 「song g / song h」這些組合（用戶 2026-11-10）。
     *
     * 規則：由長到短找「head 恰好是一個完整音節」的切法（長音節更具體，避免 `74`=po 搶先）；
     * 一個都切不出（純首碼簡拼，如 `855`）時退回「第一個碼位 + 其餘碼」；
     * [limit] 控制最多幾種切法。
     */
    /** 整串碼剛好是一個音節（`we` = the、`nz` = ngin…）：這種情況不該再拆段。 */
    /** 單字/詞的首字讀音，用於「沒有 comment」的候選組按字母序兜底排序。 */
    fun pinyinOfText(text: String): String {
        val first = text.firstOrNull() ?: return ""
        return charTable[first.toString()] ?: ""
    }

    fun isSingleSyllable(codes: String): Boolean {
        if (codes.isEmpty()) return false
        val decoded = decode(codes) ?: return false
        return decoded.split(' ').size == 1
    }

    fun splitHeads(codes: String, limit: Int = 2): List<Pair<String, String>> {
        if (codes.length < 2) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val maxHead = minOf(codes.length - 1, 5)
        for (k in maxHead downTo 1) {
            val head = codes.substring(0, k)
            val single = decodeSingle(head) ?: continue
            if (single.size == 1) {
                out.add(head to codes.substring(k))
                if (out.size >= limit) break
            }
        }
        if (out.isEmpty()) out.add(codes.substring(0, 1) to codes.substring(1))
        return out
    }

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

    /**
     * 音節 → 該讀音的字（字典順序；需要按常用度排時用 [rankChars]）。
     *
     * [limit] 由調用方決定：面板段組要「列完整」，傳
     * [com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning.SEGMENT_CHARS_LIMIT]；
     * 別再用「60 字」這種小預設值截斷（用戶 2026-11-11 報「字沒列完整」）。
     */
    fun charsForSyllable(
        syllable: String,
        limit: Int = com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning.SEGMENT_CHARS_LIMIT,
    ): List<String> =
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

    // ---- 候選條第一條的發布快照（docs/14）----
    //
    // preedit 讀音要對齊「用戶看到的第一條候選」。候選條順序是 App 排過的（詞組優先 /
    // SegmentMemory 記憶），`Rime.mContext.getCandidates()[0]` 是引擎 menu 順序，兩者可能不一致，
    // 所以由快照發布點（TextInputManager.onTwelveKeySnapshot）把候選條第一條寫到這裡。
    // 空快照時寫 null，自然退回「本地解碼」的舊行為。

    /** 候選條第一條候選的文本；preedit 用它對齊讀音（歧義時對齊第一個字）。 */
    @Volatile
    @JvmField
    var preferredText: String? = null

    /** 候選條第一條候選的 comment = 引擎給的精確拼音（spelling_hints）。 */
    @Volatile
    @JvmField
    var preferredComment: String? = null

    /**
     * 改寫 librime 給的 preedit：**保留已選中的前綴**，只把尾部「未選中的鍵碼」換成拼音。
     *
     * librime 的 preedit 形如 `咧5e`（咧 = 已選字，5e = 剩餘碼）；之前把整串都當碼去 decode，
     * 前綴被丟掉 → 用戶選完第一個字後「字消失了」。這裡只切尾部連續 ASCII 當碼段。
     *
     * 讀音對齊（docs/14）：候選條第一條的 comment 是引擎給的**精確拼音**，
     * 本地 DP 只會切出「音節數最少」的猜測（`5i34i w` → `kiung the`，而首條「共產」讀 `kiung than`）。
     *
     * 12 鍵是多對多的（同一串碼可對應多種音節切法），所以**沒有唯一正確解碼**：
     * [preferred] 一律優先，只有它為空時才用本地解碼兜底。
     *
     * @param hint 首條候選文本（歧義時對齊第一個字的讀音）
     * @param preferred 首條候選的 comment（引擎精確拼音，可為 null）
     * @return 改寫後文本；若沒有可改寫的碼段（例如已全部選完 `咧`）回傳 null，保持 librime 原樣
     */
    /**
     * **preedit 的唯一入口**（Rime.getComposition() 只調用它，不再自己拼 hint/前綴）。
     *
     * 把「候選條第一條（App 排序）優先、引擎 menu 第一條兜底、App 暫存前綴、已選前綴、
     * 尾部原始碼補齊」這幾件事收在一處，避免規則散落在 Java/Kotlin 兩邊漂移
     * （用戶 2026-11-11：preedit 與候選區的關聯代碼維護性很差）。
     *
     * @param rawPreedit librime 原始 preedit（形如 `以8‸`）
     * @param engineText 引擎 menu 第一條的文本（沒有 App 快照時的兜底）
     * @param engineComment 引擎 menu 第一條的 comment
     */
    fun render(
        rawPreedit: String?,
        engineText: String? = null,
        engineComment: String? = null,
    ): String? {
        if (rawPreedit.isNullOrEmpty() || table.isEmpty()) return null
        // 1) 讀音來源：App 快照發布的「候選條第一條」優先（所見即所讀），否則退回引擎 menu 第一條。
        val hasBarHint = !preferredText.isNullOrEmpty()
        val hint = if (hasBarHint) preferredText else engineText
        val preferred = if (hasBarHint) preferredComment else engineComment
        // 2) 改寫（已選前綴保留、尾部碼段轉拼音、comment 不夠覆蓋時補原始碼）。
        val rewritten = rewrite(rawPreedit, hint, preferred)
        lastRenderUsedRawCode = rewritten == null
        val decoded = rewritten ?: rawPreedit
        // 3) App 側暫存（兜底逐段選字，引擎候選裡沒有那些字）顯示在最前面。
        val pending = pendingPrefix
        val rendered = (if (pending.isNullOrEmpty()) "" else pending) + decoded
        if (com.hazukieq.hkrime.BuildConfig.DEBUG) {
            Timber.d(
                "dbgpre: raw=[%s] hint=[%s] preferred=[%s] pending=[%s] decoded=[%s]",
                rawPreedit, hint, preferred, pending, rendered,
            )
        }
        return rendered
    }

    fun rewrite(preedit: String?, hint: String? = null, preferred: String? = null): String? {
        if (preedit.isNullOrEmpty() || table.isEmpty()) return null
        // `‸`(U+2038) 是 librime 的光標標記，不屬於前綴也不屬於碼段，先整串剝掉再切。
        val clean = preedit.replace(CARET_MARK, "")
        if (clean.isEmpty()) return null
        var split = clean.length
        while (split > 0 && clean[split - 1].code < 128) split--
        if (split == clean.length) return null          // 尾端沒有碼段：已選完
        val prefix = clean.substring(0, split)
        val codes = clean.substring(split)
        // 去掉 librime 的分段分隔符（`5i34i wa4`、`5i34i'w`）：那些不是用戶按下的鍵。
        val cleanCodes = codes.replace(CODE_SEPARATORS, "")
        // 本地解碼：逐段 decode，半截碼（只剩聲母，如 `w`=th）翻成鍵盤字母。
        // 它的音節數 = 這串碼「能讀出幾節」，用來判斷「首條候選的讀音夠不夠長」。
        val own = decodeWithPartialTail(codes, hint)
            ?.split(' ')?.filter { it.isNotEmpty() }
            ?.map { if (it.all { ch -> ch in CODE_LETTERS }) codeLetters(it) else it }
            ?.takeIf { it.isNotEmpty() }
        val engine = parseComment(preferred)
        // 本地解碼的「字元跨度」：每個音節吃掉幾個碼位 —— 只用來算「comment 覆蓋到哪裡」，
        // 不用它的音節內容去拼湊顯示（用戶 2026-11-10：「不要試圖去拼湊」）。
        // 但它切不出完整音節時（`5i4a57` 尾端 `7` 只是聲母鍵）會整串回 null，覆蓋長度就算不出來，
        // 尾碼就丟了（用戶 2026-11-11 報 `ki mak` 少了 `7`）。所以先用 comment 自己的音節碼
        // 逐節吃掉輸入碼位（[coveredCodeLength]）——那是「comment 承諾覆蓋的碼」的客觀長度，
        // 跟本地切法無關；對不上才退回舊的 spans 算法。
        // 尾碼邊界：**用 comment 音節的「標準碼長」之和**，不用本地 DP。
        //
        // 12 鍵是同碼多音節（`5i`→ki|li、`ia9`→gap|kiap…），本地 DP 的切法與 comment
        // 給的讀音根本不是同一個世界：`ii4yia44`（gin nziamm）本地能切 3 節，於是誤補 `a44`；
        // 而 `5i4a57`（ki mak + 半截 `7`）本地又切不出來，尾巴整段丟掉。
        // comment 的音節碼長度是確定的（ki→5i 兩位、mak→4a5 三位），加起來就是它承諾吃掉的碼位：
        //   - 和 < 輸入長 → 剩下的原樣顯示（`ki mak` + `7`）；
        //   - 和 >= 輸入長 → 簡拼/半截（`8 8` = ji jen、`5i34i w`），comment 已解釋整串，不補尾碼。
        // 用戶 2026-11-11：「按下的鍵必須全部顯示」+ 不要重複/多餘的碼。
        val commentCodeLen = engine?.sumOf { syl ->
            runCatching { codeOf(syl).length }.getOrDefault(0)
        } ?: 0
        // 尾碼邊界：優先「按 comment 的音節碼逐節吃掉輸入的前綴」——這樣才能處理
        // 「打 `9999999`、首條補全 6 個 `qa`」這種**簡拼吃掉每個碼位元**的情況：
        // 逐節匹配時 `qa` 對不上 `99…`，退一步只吃 1 個碼位 → 剩下第 7 個 `9` 原樣顯示
        // （用戶 2026-11-11：「再打一個 q 居然不顯示了」＝尾碼被吞）。
        // 對不上（第一節就匹配失敗）才退回「碼長之和」的舊算法。
        val covered = coveredCodeLength(cleanCodes, engine)
        val consumed = when {
            engine == null -> cleanCodes.length
            covered != null -> covered.coerceIn(0, cleanCodes.length)
            commentCodeLen in 1..cleanCodes.length -> commentCodeLen
            else -> cleanCodes.length
        }
        val rawTail = if (consumed in 1 until cleanCodes.length) {
            cleanCodes.substring(consumed)
        } else {
            ""
        }
        val shown = when {
            engine == null -> own
            rawTail.isEmpty() -> engine
            else -> engine + rawTail
        } ?: return null
        return prefix + shown.joinToString(" ")
    }

    /** 上一次 [render] 是否連本地解碼都拿不出讀音、直接回了原始碼（需要在快照到達後補畫）。 */
    @Volatile
    var lastRenderUsedRawCode: Boolean = false
        private set

    /**
     * comment 的音節依次吃掉輸入碼的多少碼位（前綴匹配）：
     * 先按完整碼（[codeOf]）匹配，匹配不上再按「簡拼只吃一個碼位」退一步。
     * 第一節就無法匹配 → 回 null（不猜，退回舊的長度算法）。
     */
    private fun coveredCodeLength(codes: String, engine: List<String>?): Int? {
        if (engine.isNullOrEmpty() || codes.isEmpty()) return null
        var pos = 0
        for (syllable in engine) {
            val full = runCatching { codeOf(syllable) }.getOrNull().orEmpty()
            if (full.isEmpty()) return null
            when {
                codes.startsWith(full, pos) -> pos += full.length
                pos < codes.length && codes[pos] == full[0] -> pos += 1
                else -> return if (pos == 0) null else pos
            }
            if (pos > codes.length) return codes.length
        }
        return pos
    }

    /** librime preedit 的光標標記 `‸`(U+2038)。 */
    private const val CARET_MARK = "\u2038"

    /** preedit 裡的分段分隔符（librime 的 `5i34i wa4`、分詞鍵產生的 `'`）——不是用戶按下的鍵。 */
    private val CODE_SEPARATORS = Regex("[\\s']")

    /**
     * 碼位 → 拼音字母（與 `TwelveKeySnapshotBuilder.FALLBACK_LETTERS` 同一套；
     * 那份是 `private`，這裡保留一份並由 `rewriteMapsPartialTailCodeToLetters` 用例釘住語義）。
     */
    private val CODE_LETTERS = mapOf(
        '3' to "u", '1' to "ng", 'w' to "th", 'y' to "nz",
        '4' to "m", '5' to "k", '7' to "p", '8' to "z", '9' to "q",
    )

    private fun codeLetters(code: String) = buildString {
        for (ch in code) append(CODE_LETTERS[ch] ?: ch.toString())
    }

    /**
     * 碼串 → 拼音（本地兜底）。librime 的 preedit 會用空格分隔「已消費前綴 / 半截碼」（`5i34i w`），
     * 所以逐段解碼；某一段切不出完整音節時退一步：找「最長可解碼頭部」，
     * 剩下半截碼逐位翻成鍵盤字母（`5i34i w` → `kiung th`），而不是回落原始碼字元。
     */
    private fun decodeWithPartialTail(codes: String, hint: String?): String? {
        val parts = codes.trim().split(MULTI_DELIMITER).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ") { part -> decodeOne(part, hint) ?: part }
    }

    private fun decodeOne(code: String, hint: String?): String? {
        decode(code, hint)?.let { return it }
        for (cut in code.length - 1 downTo 1) {
            val head = decode(code.substring(0, cut), hint) ?: continue
            return "$head ${code.substring(cut)}"
        }
        return null
    }

    /** 候選 comment → 音節列表（剝掉 `［…］` 包裝；空/無 comment 回 null）。 */
    /**
     * librime preedit 裡的「已選中前綴」（`已選字 + 碼串 + ‸` 裡的那些漢字）。
     *
     * 舊寫法是「從尾部跳過 ASCII，剩下的就是前綴」——但末尾的光標 `‸`(U+2038) 本身就是非 ASCII，
     * 於是 `8 8‸` 這種「一格都沒選」的 preedit 會被整串當成前綴，嵌入式預覽把它寫進輸入框，
     * 上屏時就出現 `88˄以` 這種把原始碼串當文字提交的怪相（用戶 2026-11-11 報）。
     * 碼串一定是 ASCII、光標一定是 `‸`，所以直接「只留非 ASCII 且非光標的字」最穩。
     */
    fun compositionPrefix(preedit: String?): String {
        if (preedit.isNullOrEmpty()) return ""
        val out = StringBuilder(preedit.length)
        for (ch in preedit) {
            if (ch.code >= 128 && ch != CARET_MARK[0]) out.append(ch)
        }
        return out.toString()
    }

    internal fun parseComment(comment: String?): List<String>? {
        val text = comment?.replace("\uFF3B", "")?.replace("\uFF3D", "")?.trim()
        if (text.isNullOrEmpty()) return null
        return text.split(' ').filter { it.isNotEmpty() }.ifEmpty { null }
    }

    private val MULTI_DELIMITER = Regex("[\u0027\\s]+")

    /** 單段碼串 → 音節列表；切不出完整音節回傳 null。 */
    internal fun decodeSingle(input: String): List<String>? = decodeSpans(input)?.map { it.second }

    /**
     * 單段碼串 → [(佔用的碼串, 音節)]；切不出完整音節回傳 null。
     *
     * 只給 [rewrite] 判斷「comment 覆蓋到第幾個碼位元」用：沒被覆蓋的尾部要原樣顯示碼，
     * 不能拿這裡的音節去拼（用戶 2026-11-10）。
     */
    private fun decodeSpans(input: String): List<Pair<String, String>>? {
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
                // 兒化後綴 `r` 只能落在整串的最後（見 [SUFFIX_ONLY_SYLLABLES]）。
                if (isSuffixOnlySyllable(entry.syllable) && i + len != n) continue
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
        val out = ArrayList<Pair<String, String>>()
        var p = n
        while (p > 0) {
            val q = prev[p]
            if (q < 0) return null
            out.add(input.substring(q, p) to (pick[p] ?: return null))
            p = q
        }
        out.reverse()
        return out
    }
}
