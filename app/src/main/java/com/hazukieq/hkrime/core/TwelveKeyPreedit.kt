package com.hazukieq.hkrime.core

import android.content.Context
import com.hazukieq.hkrime.core.twelvekey.PhraseMemory
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
     * App 已選未提交前綴，由 TextInputManager 更新；組合未完成時僅留在 preedit。
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
     * 原始聯想計數以首音節與第二音節首字母關聯詞面。
     * 帶讀音條目直接解析；無讀音詞表可用 charTable 反推。面板對外標題仍用具體完整音節。
     */
    private val assocWords = HashMap<String, LinkedHashMap<String, Int>>()

    /**
     * 純詞表（無讀音）的多字詞首字母索引：`jckz -> (jen cin kung ze -> {远程控制})`。
     *
     * `assocWords` 只反推前兩字讀音；三字以上的組合行需要逐字反推，收在這一張輕量表裡，
     * 由 [finishWordIndex] 併入 [initialPatternIndex]。上限只按 CJK 3~6 字，避免 17 萬條全量。
     */
    private val multiInitialWords = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()

    // 學習詞讀音索引來自 PhraseMemory，與候選條共享文字和讀音，不讀 userdb 快照。

    private var learnedIndexVersion = -1
    private var learnedAssoc: Map<String, Map<String, List<String>>> = emptyMap()
    private var learnedInitial: Map<String, List<Pair<String, List<String>>>> = emptyMap()
    private var learnedHybrid: Map<String, List<Pair<String, List<String>>>> = emptyMap()
    private var learnedWords: Set<String> = emptySet()

    /**
     * 整串碼前綴索引收錄自帶完整讀音的詞條，用於多音節詞行輕量查詢。
     */
    private data class WordCodeEntry(
        val tokens: List<String>,
        val word: String,
        val code: String,
    )

    private val wordCodeEntries = ArrayList<WordCodeEntry>()

    /**
     * 後綴音節僅允許詞尾，不能作第一段或中間段簡拼。
     */
    internal val SUFFIX_ONLY_SYLLABLES = setOf("r")

    internal fun isSuffixOnlySyllable(syllable: String): Boolean = syllable in SUFFIX_ONLY_SYLLABLES

    /** 客家話音節集合（由主詞典「字→讀音」建立）；詞表解析只認集合內的音節。 */
    private val syllableSet = HashSet<String>()

    /** 音節 → 主詞典詞頻（孤單聯想/音節排序用）。 */
    private val syllableFreq = HashMap<String, Int>()

    /** 標準音節集（assets/rime/hakka_syllables.txt）；只用於「整串是否完整音節」判定。 */
    private val standardSyllables = LinkedHashSet<String>()

    /** 標準音節集的碼 → 完整音節（`gia` 不在主詞典但標準集有，`iia` 必須視為完整音節）。 */
    private val standardSyllableCodes = HashSet<String>()

    /**
     * 發布不可變整串碼索引，完成構建後整體替換；查詢不遍歷可變載入列表。
     */
    @Volatile
    private var wordCodeIndex: List<WordCodeEntry> = emptyList()

    /**
     * 按第一音節和第二音節建立精確聯想索引，避免每鍵全表掃描或拆分字串。
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
     * 首字母序列索引只約束每個音節的首字母，元音自由。
     */
    private var syllablesByInitial: Map<Char, List<String>> = emptyMap()

    private var initialPatternIndex: Map<String, List<Pair<String, List<String>>>> = emptyMap()

    /**
     * 簡拼+完整末音節的模式索引，如 b|can；末節按完整音節匹配。
     */
    private var hybridPatternIndex: Map<String, List<Pair<String, List<String>>>> = emptyMap()

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
        // 主詞典優先從實際用戶目錄讀取，缺失時回退 assets。
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

        // 標準音節集（可選資源）：只參與「孤單聯想」的完整音節判定與通配列表，
        // 不擴大運行時搜索集合（主詞典 694 仍是段組/聯想的口徑）。
        runCatching {
            RimeDictSource.textLines(context, "hakka_syllables.txt")?.let { lines ->
                loadStandardSyllableLines(lines.asSequence())
            }
            Timber.i("TwelveKeyPreedit: standardSyllables=%d", standardSyllables.size)
        }.onFailure { Timber.w(it, "TwelveKeyPreedit: load standard syllables failed") }

        // 進口詞典（尤其 hakka_zon）的單字讀音並入運行時音節/字表：方言用字（`渠 gia`）
        // 只在 zon 收錄，主詞典沒有；不併會讓標準集音節有字卻無法在面板顯示。
        runCatching {
            val names = RimeDictSource.namesWithImports(context, MAIN_DICT).filter { it != MAIN_DICT }
            val allowed = standardSyllables
            names.forEach { name ->
                val extra = loadDictLines(RimeDictSource.body(context, name).asSequence())
                extra.forEach { (syllable, count) ->
                    // 只並入標準音集收錄的音節；進口詞典可能混有方案外的拼寫（bon/hop）。
                    if (allowed.isEmpty() || syllable in allowed) {
                        counts[syllable] = (counts[syllable] ?: 0) + count
                    }
                }
            }
            Timber.i("TwelveKeyPreedit: merged imports=%d", names.size)
        }.onFailure { Timber.w(it, "TwelveKeyPreedit: merge import dicts failed") }
        finishLoading(counts)

        // 詞表與聯想索引在後台載入，完成後發布不可變索引。
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
     * 標準音節資產僅供測試與腳本約束；App 運行時集合來自主詞典 counts.keys。
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
            // 只取讀音欄：詞條第三欄是權重（`char\tsyl\tweight`），整段拿去當音節會漏字。
            val syllable = raw.substring(tab + 1).split('\t')[0].trim()
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
            // 帶權重詞條僅第二欄是讀音，避免把第三欄權重拼入音節。
            val reading = if (tab >= 0) line.substring(tab + 1).split('\t')[0].trim() else ""
            // 純詞表的多字詞（3~6 個 CJK）逐字反推首讀音，建首字母組合索引。
            // 兩字詞仍走 assocWords 的聯想索引；這裡只補組合行需要的長詞。
            if (reading.isEmpty() && word.length in 3..6 && word.all { isCjk(it) }) {
                val syllables = ArrayList<String>(word.length)
                var complete = true
                for (ch in word) {
                    val syllable = charTable[ch.toString()]
                    if (syllable == null || syllable.isEmpty() || !syllable.all { it in 'a'..'z' }) {
                        complete = false
                        break
                    }
                    syllables.add(syllable)
                }
                if (complete && (syllableSet.isEmpty() || syllables.all { it in syllableSet })) {
                    val key = syllables.joinToString("") { it.first().toString() }
                    if (key.length == word.length) {
                        multiInitialWords
                            .getOrPut(key) { LinkedHashMap() }
                            .getOrPut(syllables.joinToString(" ")) { LinkedHashSet() }
                            .add(word)
                    }
                }
            }
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
            // 此處按詞長過濾至少兩音節條目；面板分檔另以 reading token 數判斷。
            if (syllables.size < 2) return@forEach
            // 只認**客家話音節集合**裡的音節：目錄裡可能還有 luna_pinyin 這類別的方案詞表
            // （`例行 → li xing`），把它們當音節用會讓面板長出根本不是客家話的聯想行。
            // 主詞典沒載入（syllableSet 為空，例如單測只餵詞表）時跳過校驗。
            if (syllableSet.isNotEmpty() && syllables.any { it !in syllableSet }) return@forEach
            val r1 = syllables[0]
            val r2 = syllables[1]
            if (r1.isEmpty() || r2.isEmpty()) return@forEach
            // 漢字詞收 2~4 字；其它詞條依含字元與顯式讀音條件過濾。
            val cjkOnly = word.all { isCjk(it) }
            if (cjkOnly && word.length !in 2..4) return@forEach
            if (!cjkOnly && word.length !in 1..16) return@forEach
            val key = "$r1|$r2"
            val bucket = assocWords.getOrPut(key) { LinkedHashMap() }
            bucket[word] = (bucket[word] ?: 0) + 1
            // 整串碼索引只加入自帶讀音的條目；純詞表僅用於聯想，避免索引膨脹。
            if (reading.isNotEmpty()) {
                val fullCode = syllables.joinToString("") { codeOf(it) }
                if (fullCode.isNotEmpty()) {
                    wordCodeEntries.add(WordCodeEntry(syllables, word, fullCode))
                }
            }
        }
    }

    /**
     * 讀音 → 完整碼串（`lin gi jit dung` → `5i4ii8i9e34i`）。
     *
     * 上屏 App 側詞條時用來判斷「這條詞只覆蓋了輸入的前幾個音節，後面還有剩餘碼」。
     */
    internal fun codeOfReading(reading: String): String {
        val tokens = reading.split(' ', '\u3000').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val sb = StringBuilder()
        tokens.forEach { token ->
            val code = codeOf(token)
            if (code.isEmpty()) return ""
            sb.append(code)
        }
        return sb.toString()
    }

    /**
     * 按實際輸入解碼的前 N 個音節扣除已選讀音的音節數，返回剩餘碼。
     * 不將候選讀音重新編碼作字串前綴扣除；無可靠解碼時不猜剩餘。
     */
    internal fun remainingCodeAfter(wordReading: String, input: String): String {
        if (wordReading.isEmpty() || input.isEmpty()) return ""
        val tokens = wordReading.split(' ', '\u3000').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        // 用「碼串前 N 個音節」算消耗量，**不要**用 reading→碼 的前綴比對：
        // 同一個字可能有多個讀音（动 dung/tung），學習詞記下的 readings 未必等於
        // 這次輸入實際用的讀音（實測：`灵机一动` 記的是 dung，但用戶打的是 tung 934i），
        // 前綴比對會失配 → 剩餘碼算不出來 → 後半截照樣丟。
        val spans = decodeSpans(input) ?: return ""
        if (spans.size < tokens.size) return ""
        var consumed = 0
        for (i in 0 until tokens.size) consumed += spans[i].first.length
        return if (input.length > consumed) input.substring(consumed) else ""
    }

    /** PhraseMemory 變化時重建「學習詞 → 讀音」索引（面板與候選條同源）。 */
    private fun ensureLearnedIndex() {
        val version = PhraseMemory.version
        if (version == learnedIndexVersion) return
        val assoc = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        val initial = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        val hybrid = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        val words = HashSet<String>()
        PhraseMemory.allEntries().forEach { entry ->
            val text = entry.text
            if (text.isEmpty()) return@forEach
            val tokens = entry.readings.split(' ', '\u3000').filter { it.isNotEmpty() }
            if (tokens.size < 2) return@forEach
            if (syllableSet.isNotEmpty() && tokens.any { it !in syllableSet }) return@forEach
            val reading = tokens.joinToString(" ")
            words.add(text)
            if (tokens.size == 2) {
                assoc.getOrPut(tokens[0]) { LinkedHashMap() }
                    .getOrPut(tokens[1]) { LinkedHashSet() }
                    .add(text)
            }
            val initials = tokens.joinToString("") { it.first().toString() }
            initial.getOrPut(initials) { LinkedHashMap() }
                .getOrPut(reading) { LinkedHashSet() }
                .add(text)
            val prefix = tokens.dropLast(1).joinToString("") { it.first().toString() }
            hybrid.getOrPut(prefix + "|" + tokens.last()) { LinkedHashMap() }
                .getOrPut(reading) { LinkedHashSet() }
                .add(text)
        }
        learnedAssoc = assoc.mapValues { (_, rows) -> rows.mapValues { (_, ws) -> ws.toList() } }
        fun freeze(src: HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>) =
            src.mapValues { (_, rows) ->
                rows.entries.sortedBy { it.key }.map { it.key to it.value.toList() }
            }
        learnedInitial = freeze(initial)
        learnedHybrid = freeze(hybrid)
        learnedWords = words
        learnedIndexVersion = version
    }

    /**
     * 學習詞在面板選行階段優先，最終顯示仍按讀音排序。
     */
    internal fun isLearnedWord(word: String): Boolean {
        ensureLearnedIndex()
        return word in learnedWords
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
        multiInitialWords.clear()
        learnedIndexVersion = -1
        learnedAssoc = emptyMap()
        learnedInitial = emptyMap()
        learnedHybrid = emptyMap()
        learnedWords = emptySet()
        wordCodeEntries.clear()
        wordCodeIndex = emptyList()
        assocByFirst = emptyMap()
        initialPatternIndex = emptyMap()
        hybridPatternIndex = emptyMap()
        assocReady = false
    }

    /** 詞表載入完後把索引整理成「按熱度降序」的列表，避免每次查詢都排序。 */
    internal fun finishWordIndex() {
        assocWords.values.forEach { bucket ->
            // 先按熱度取有限候選，再對保留結果按字典序顯示。
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
        val baseIndex = buildInitialPatternIndex(wordCodeEntries)
        if (multiInitialWords.isEmpty()) {
            initialPatternIndex = baseIndex
        } else {
            // 詞表首字母索引 + 純詞表多字詞索引：合併後按讀音字典序固定，面板取前幾行才穩定。
            val merged = HashMap<String, LinkedHashMap<String, List<String>>>()
            baseIndex.forEach { (key, entries) ->
                val byReading = merged.getOrPut(key) { LinkedHashMap() }
                entries.forEach { (reading, words) -> byReading[reading] = words }
            }
            multiInitialWords.forEach { (key, byReading) ->
                val target = merged.getOrPut(key) { LinkedHashMap() }
                byReading.forEach { (reading, words) ->
                    val current = target[reading]
                    if (current == null) {
                        target[reading] = words.toList()
                    } else {
                        target[reading] = (current + words).distinct()
                    }
                }
            }
            initialPatternIndex = merged.mapValues { (_, byReading) ->
                byReading.entries.sortedBy { it.key }.map { it.key to it.value }
            }
        }
        assocGeneration++
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF
    }

    /**
     * 按第一音節與第二段條件查聯想詞。面板標題必須為兩個完整音節。
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
            // 整串前綴詞查詢要求第一音節完整編碼已包含在輸入中。
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
        // 詞面顯示按字典序排列。
        return merged.sorted()
    }

    /**
     * 第一段固定音節，第二段按首字母集合選具體音節。
     * 每讀音最多 limitPerReading 詞，先按熱度取 maxReadings 行，再按讀音顯示。
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
        // 先按熱度截斷到 maxReadings，再按讀音字典序顯示。
        val chosen = rows.sortedByDescending { it.second.size }.take(maxReadings)
        return chosen.sortedBy { it.first }
    }

    /**
     * 第一音節精確匹配，第二段按 secondCode 展開為具體音節並精確查聯想索引。
     * 行標題為兩個完整音節；先按熱度取有限行，再按讀音顯示。
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
     * 計算碼位可覆蓋的音節數，供面板選擇普通 16 或豐富鍵 24 的音節配額。
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
            // 包含精確碼命中的首字母，確保單碼完整音節參與候選。
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
        // 首字母索引只收主詞典音節，按字典序固定以與腳本逐行對拍。
        syllablesByInitial = syllableSet
            .filter { it.isNotEmpty() }
            .groupBy { it.first() }
            .mapValues { (_, list) -> list.sorted() }
    }

    /**
     * 返回輸入前綴已完成的音節，如 8e99 的 ze/zet。
     * 供聯想查詢精確定位第二音節，未命中時由調用方回退首字母查詢。
     */
    internal fun syllablesCompletingPrefix(code: String): List<String> {
        if (code.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        codeToSyllables.forEach { (syllableCode, list) ->
            if (syllableCode.isEmpty() || !code.startsWith(syllableCode)) return@forEach
            list.forEach { entry -> out.add(entry.syllable) }
        }
        return out.sorted()
    }

    /**
     * 由詞面逐字首讀音反推完整碼，只供推薦排序。
     * 任一字沒有讀音返回 null；多音字取首讀音，不改變候選文字。
     */
    internal fun derivedWordCode(word: String): String? {
        if (word.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in word) {
            val syllable = charTable[ch.toString()] ?: return null
            val code = codeOf(syllable)
            if (code.isEmpty()) return null
            sb.append(code)
        }
        return sb.toString()
    }

    /** 首字母集合 → 這些字母打頭的全部音節（字典序、去重）。
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

    /** 一個召回首字母模式：純簡拼（每音節一個首字母）或「簡拼 + 最後一個完整音節」。 */
    internal data class InitialPattern(val initials: List<Char>, val tail: String? = null) {
        val isHybrid: Boolean get() = tail != null
        val key: String get() = initials.joinToString("") + (tail?.let { "|$it" } ?: "")
    }

    /**
     * 前段各鍵提供一個音節首字母，最後碼段必須是完整音節。
     * 枚舉各切法，完整性由 isSingleSyllable / exact 音節集合判定。
     */
    internal fun initialHybridPatterns(codes: String): List<InitialPattern> {
        val clean = codes.replace("'", "")
        if (clean.length < 2) return emptyList()
        val out = ArrayList<InitialPattern>()
        for (k in 1 until clean.length) {
            val suffix = clean.substring(k)
            if (!isSingleSyllable(suffix)) continue
            var prefixes: List<List<Char>> = listOf(emptyList())
            var ok = true
            for (i in 0 until k) {
                val letters = keyInitialLetters(clean[i], isLast = false)
                if (letters.isEmpty()) { ok = false; break }
                prefixes = prefixes.flatMap { p -> letters.map { p + it } }
                if (prefixes.size > TwelveKeyTuning.INITIAL_COMBO_PATTERN_LIMIT) { ok = false; break }
            }
            if (!ok) continue
            val tails = syllablesForSegment(
                suffix,
                1_000_000,
                exactOnly = true,
                totalLimit = 1_000_000,
                allowSuffixOnly = true,
            )
            for (pre in prefixes) {
                for (tail in tails) out.add(InitialPattern(pre, tail))
            }
        }
        return out
    }

    /**
     * 首字母組合規則 = 純簡拼組合 + 簡拼+完整音節（總數封頂；與腳本同口徑）。
     *
     * 兩類**交錯**排列，不做「純組合全在前」：純詞表的長詞（三/四字）靠純組合就能出詞，
     * 若把它們排滿 [TwelveKeyTuning.INITIAL_COMBO_MAX_ROWS] 行，簡拼+完整音節（如 `b|cam`）
     * 就永遠輪不到，學習詞/長音節聯想會被吞掉。
     */
    internal fun initialPatterns(codes: String): List<InitialPattern> {
        val pure = initialCombos(codes).map { InitialPattern(it) }
        val hybrids = initialHybridPatterns(codes)
        val all = ArrayList<InitialPattern>(pure.size + hybrids.size)
        val max = maxOf(pure.size, hybrids.size)
        for (i in 0 until max) {
            if (i < pure.size) all.add(pure[i])
            if (i < hybrids.size) all.add(hybrids[i])
        }
        return if (all.size > TwelveKeyTuning.INITIAL_COMBO_PATTERN_LIMIT) {
            all.subList(0, TwelveKeyTuning.INITIAL_COMBO_PATTERN_LIMIT)
        } else {
            all
        }
    }

    /** 按模式查詞表（純簡拼走 [initialPatternIndex]，混合走 [hybridPatternIndex]）。 */
    internal fun wordsByPattern(pattern: InitialPattern): List<Pair<String, List<String>>> {
        ensureLearnedIndex()
        val key = pattern.initials.joinToString("")
        val base = if (pattern.isHybrid) hybridPatternIndex[pattern.key] ?: emptyList()
        else initialPatternIndex[key] ?: emptyList()
        val learned = if (pattern.isHybrid) learnedHybrid[pattern.key] ?: emptyList()
        else learnedInitial[key] ?: emptyList()
        if (learned.isEmpty()) return base
        // 學過的詞與資產詞表按讀音合併（同讀音取並集；行內順序沿用資產詞表在前）。
        val merged = LinkedHashMap<String, LinkedHashSet<String>>()
        base.forEach { (reading, words) ->
            merged.getOrPut(reading) { LinkedHashSet() }.addAll(words)
        }
        learned.forEach { (reading, words) ->
            merged.getOrPut(reading) { LinkedHashSet() }.addAll(words)
        }
        return merged.entries.sortedBy { it.key }.map { it.key to it.value.toList() }
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
        // 簡拼+完整音節索引：末音節用完整讀音、前面的音節只按首字母。
        // 純詞表只知道前兩個音節，所以只把**兩字純詞**當作完整兩音節；帶讀音的詞表按整條入索引。
        val byHybrid = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        fun addHybrid(tokens: List<String>, word: String) {
            if (tokens.size < 2) return
            val prefix = tokens.dropLast(1).joinToString("") { it.firstOrNull()?.toString() ?: "" }
            if (prefix.length != tokens.size - 1) return
            val key = prefix + "|" + tokens.last()
            val reading = tokens.joinToString(" ")
            byHybrid.getOrPut(key) { LinkedHashMap() }
                .getOrPut(reading) { LinkedHashSet() }
                .add(word)
        }
        // 兩音節詞（聯想索引）——純詞表反推出來的也在這裡，是面板最常用的來源。
        assocWords.forEach { (key, bucket) ->
            val sep = key.indexOf('|')
            if (sep <= 0 || sep >= key.length - 1) return@forEach
            val tokens = listOf(key.substring(0, sep), key.substring(sep + 1))
            bucket.keys.forEach { word ->
                add(tokens, word)
                if (word.length == 2) addHybrid(tokens, word)
            }
        }
        // 三音節以上的詞（帶讀音的詞表才有）。
        entries.forEach { entry ->
            add(entry.tokens, entry.word)
            addHybrid(entry.tokens, entry.word)
        }
        hybridPatternIndex = byHybrid.mapValues { (_, rows) ->
            rows.entries.sortedBy { it.key }.map { it.key to it.value.toList() }
        }
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
    internal fun assocSecondMap(first: String): Map<String, List<String>> {
        ensureLearnedIndex()
        val base = assocByFirst[first]
        val learned = learnedAssoc[first]
        if (learned == null || learned.isEmpty()) return base ?: emptyMap()
        if (base == null) return learned
        val merged = LinkedHashMap<String, List<String>>(base)
        learned.forEach { (second, words) ->
            val had = merged[second]
            merged[second] = if (had == null) words else had + words
        }
        return merged
    }

    /** 詞典行載入後統一收尾：音節次數 → code 表。 */
    internal fun finishLoading(counts: HashMap<String, Int>) {
        // 可重入：測試會在不同測試類別各自載入一次資源；這裡先把派生表清空再重建，
        // 避免同一個碼位的音節被重複 append（會讓「輪轉取 limit 個」變少）。
        table.clear()
        codeToSyllables.clear()
        // 運行時音節集合取主詞典 counts.keys；標準集資產僅約束腳本與測試。
        syllableSet.clear()
        syllableSet.addAll(counts.keys)
        syllableFreq.clear()
        syllableFreq.putAll(counts)
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
        // 桶按最高頻降序、同頻按 next-code 排；桶內按首字母，與腳本排序鍵一致。
        val order = outer.entries
            .sortedWith(
                compareByDescending<Map.Entry<Char, LinkedHashMap<Char, MutableList<Entry>>>> { entry ->
                    entry.value.values.maxOfOrNull { it.first().freq } ?: 0
                }.thenBy { it.key }
            )
            .map { it.value }
        // limit 是每個首字母的音節配額，總數另受 MAX_SYLLABLES_TOTAL 限制。
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
        // 讀音按字典序顯示，精確碼音節固定在前。
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
     * 由長到短找完整首音節的切法，最多 limit 種；
     * 純簡拼無完整切法時回退第一碼位與剩餘碼，保留不同合法首段入口。
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

    /**
     * 載入標準音節集；只用於孤單聯想的完整音節判定與通配列表，不擴大運行時集合。
     */
    internal fun loadStandardSyllableLines(lines: Sequence<String>) {
        standardSyllables.clear()
        standardSyllableCodes.clear()
        lines.forEach { raw ->
            val syllable = raw.trim()
            if (syllable.isEmpty() || syllable.startsWith("#")) return@forEach
            standardSyllables.add(syllable)
            standardSyllableCodes.add(codeOf(syllable))
        }
    }

    /** 標準音節集快照（只讀；測試與診斷用）。 */
    internal fun standardSyllablesSnapshot(): Set<String> = standardSyllables

    /**
     * 整串碼恰好是一個完整音節（孤單聯想互斥）。
     *
     * 判定必須看**標準音節集**：`gia` 不在主詞典（運行時 694）但標準集有，
     * 它的碼 `iia` 已是完整音節，不能再列孤單聯想。
     */
    internal fun hasFullSyllable(code: String): Boolean {
        val clean = code.replace("'", "")
        return isSingleSyllable(clean) || clean in standardSyllableCodes
    }

    /**
     * 孤單聯想：多鍵輸入且整串不是完整音節時，首鍵字母各列該字母下的**全部**單音節。
     *
     * 列表是通配口徑：只看音節首字母，不受剩餘碼前綴限制（`55` → 所有 k/l 開頭音節），
     * 所以不會出現 `gia*` 這種「按剩餘碼前綴收窄」的假組合。標準集音節也納入。
     */
    internal fun isolatedSyllables(code: String): List<Pair<Char, List<String>>> {
        val clean = code.replace("'", "")
        if (clean.length < 2 || hasFullSyllable(clean)) return emptyList()
        val universe = LinkedHashSet<String>(syllableSet.size + standardSyllables.size)
        universe.addAll(syllableSet)
        universe.addAll(standardSyllables)
        val out = ArrayList<Pair<Char, List<String>>>(2)
        keyInitialLetters(clean[0], isLast = false).forEach { letter ->
            val syls = universe
                .filter { it.firstOrNull() == letter }
                .sortedWith(compareByDescending<String> { syllableFreq[it] ?: 0 }.thenBy { it })
            if (syls.isNotEmpty()) out.add(letter to syls)
        }
        return out
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
            // 純簡拼無完整切法時取第一碼與剩餘碼，單字組將首碼展開成具體音節。
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
     * 音節對應字按字典順序返回；按常用度顯示時再調 rankChars。
     * 段組傳 SEGMENT_CHARS_LIMIT，避免任意小上限截斷可選字。
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
     * 碼串按明確分隔符逐段解碼；切不出完整音節的段保留原碼。
     * 分詞鍵插入的引號代表音節邊界，不能濾掉後重新合併。
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

    // preedit 首選讀音由發布快照的候選條第一項提供，不能另取引擎第 0 項。
    // 空快照設為 null，回退本地解碼。

    /** 候選條第一條候選的文本；preedit 用它對齊讀音（歧義時對齊第一個字）。 */
    @Volatile
    @JvmField
    var preferredText: String? = null

    /** 候選條第一條候選的 comment = 引擎給的精確拼音（spelling_hints）。 */
    @Volatile
    @JvmField
    var preferredComment: String? = null

    /**
     * 保留引擎已選前綴，只改寫尾部未選碼。首選 comment 的精確讀音優先，
     * 沒有可用首選時回退本地解碼；同碼多音節不存在唯一解碼。
     * @param hint 首條候選文字
     * @param preferred 首條候選讀音
     */
    /**
     * preedit 統一入口：App 快照首選優先，引擎首選兜底，
     * 合併 App 暫存前綴與引擎已選前綴，並補齊未覆蓋原碼。
     */
    // 相同參數的 preedit 渲染復用最近結果，減少重複解碼、查表和字串分配。
    private var renderCacheKey: String? = null
    private var renderCacheValue: String? = null
    private var renderCacheUsedRawCode: Boolean = false

    fun render(
        rawPreedit: String?,
        engineText: String? = null,
        engineComment: String? = null,
    ): String? {
        if (rawPreedit.isNullOrEmpty() || table.isEmpty()) return null
        val cacheKey = rawPreedit + "\u0001" + engineText + "\u0001" + engineComment + "\u0001" +
            preferredText + "\u0001" + preferredComment + "\u0001" + pendingPrefix
        if (cacheKey == renderCacheKey) {
            lastRenderUsedRawCode = renderCacheUsedRawCode
            return renderCacheValue
        }
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
        if (com.hazukieq.hkrime.BuildConfig.DEBUG && com.hazukieq.hkrime.util.DiagLog.verbose) {
            Timber.d(
                "dbgpre: raw=[%s] hint=[%s] preferred=[%s] pending=[%s] decoded=[%s]",
                rawPreedit, hint, preferred, pending, rendered,
            )
        }
        renderCacheKey = cacheKey
        renderCacheValue = rendered
        renderCacheUsedRawCode = lastRenderUsedRawCode
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
        // 本地 spans 僅用於計算碼位覆蓋；顯示優先使用首選 comment。
        // comment 逐節覆蓋輸入碼，失配時使用跨度或碼長兜底，未覆蓋尾碼原樣保留。
        val commentCodeLen = engine?.sumOf { syl ->
            runCatching { codeOf(syl).length }.getOrDefault(0)
        } ?: 0
        // 優先按 comment 音節逐節匹配輸入前綴；簡拼只消耗對應首碼。
        // 第一節失配時回退碼長之和，未覆蓋尾碼保持原始字元。
        val coverage = resolveCovered(cleanCodes, engine)
        val consumed = when {
            engine == null -> cleanCodes.length
            coverage != null -> coverage.first.coerceIn(0, cleanCodes.length)
            commentCodeLen in 1..cleanCodes.length -> commentCodeLen
            else -> cleanCodes.length
        }
        val rawTail = if (consumed in 1 until cleanCodes.length) {
            cleanCodes.substring(consumed)
        } else {
            ""
        }
        val shownBase = coverage?.second ?: engine
        val shown = when {
            engine == null -> own
            shownBase == null -> own
            rawTail.isEmpty() -> shownBase
            else -> shownBase + rawTail
        } ?: return null
        return prefix + shown.joinToString(" ")
    }

    /** 上一次 [render] 是否連本地解碼都拿不出讀音、直接回了原始碼（需要在快照到達後補畫）。 */
    @Volatile
    var lastRenderUsedRawCode: Boolean = false
        private set

    /**
     * 依次計算 comment 音節覆蓋的輸入碼位與顯示讀音。
     * 完整碼匹配時整節消耗；合法別名按本地完整音節消耗；簡拼消耗一碼。
     * 第一節無法匹配時返回 null，由調用方採用長度兜底。
     */
    private fun resolveCovered(codes: String, engine: List<String>?): Pair<Int, List<String>>? {
        if (engine.isNullOrEmpty() || codes.isEmpty()) return null
        var pos = 0
        val shown = ArrayList<String>(engine.size)
        for ((index, syllable) in engine.withIndex()) {
            if (pos >= codes.length) {
                // 簡拼：comment 的後半段是引擎按 abbrev 補出來的（一個鍵對多個音節），
                // 沒有對應輸入碼可吃；整段照抄，不能把 comment 截短。
                shown.addAll(engine.subList(index, engine.size))
                break
            }
            val full = runCatching { codeOf(syllable) }.getOrNull().orEmpty()
            if (full.isNotEmpty() && codes.startsWith(full, pos)) {
                shown += syllable
                pos += full.length
                continue
            }
            val span = decodeSpans(codes.substring(pos))?.firstOrNull()
            if (span != null && span.first.length > 1 &&
                isSpellingAlias(syllable, span.second)
            ) {
                // 顯示仍用 comment（所見即所讀），但按輸入碼整節消耗，不把尾巴留成 `3`。
                shown += syllable
                pos += span.first.length
            } else {
                shown += syllable
                pos += 1
            }
            if (pos > codes.length) return codes.length to shown
        }
        if (pos == 0) return null
        return pos.coerceAtMost(codes.length) to shown
    }

    /** 單測用：comment 覆蓋的碼位長度（走真正的 [resolveCovered] 規則）。 */
    internal fun coveredCodeLengthForTest(codes: String, comment: String?): Int? =
        resolveCovered(codes, parseComment(comment))?.first

    /**
     * 兩個讀音是否只是 schema 的 derive 規則造成的拼寫別名（`cw` ↔ `cu`、`gap` ↔ `gat` …），
     * 而不是兩個不同的音節（`than` vs `the`）。別名才可以把 comment 換成本地解碼讀音。
     */
    private fun isSpellingAlias(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return canonicalSpelling(a) == canonicalSpelling(b)
    }

    /** 把 schema `derive` 規則的變體拼寫歸一到同一形（只用於別名判定）。 */
    private fun canonicalSpelling(syllable: String): String {
        var s = syllable
        s = when {
            s.endsWith("iam") -> s.dropLast(3) + "ian"
            s.endsWith("am") -> s.dropLast(2) + "an"
            s.endsWith("om") -> s.dropLast(2) + "on"
            s.endsWith("im") -> s.dropLast(2) + "in"
            s.endsWith("em") -> s.dropLast(2) + "en"
            s.endsWith("ap") -> s.dropLast(2) + "at"
            s.endsWith("ep") -> s.dropLast(2) + "et"
            s.endsWith("up") -> s.dropLast(2) + "ut"
            s.endsWith("w") -> s.dropLast(1) + "u"
            else -> s
        }
        return s
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
     * 提取 librime preedit 的已選前綴：僅保留非 ASCII 且不是光標標記的文字。
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
     * 單段碼解碼為碼跨度與音節；無法切成完整音節時返回 null。
     * 跨度僅協助覆蓋判斷，不能用推測讀音替代首選 comment。
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
