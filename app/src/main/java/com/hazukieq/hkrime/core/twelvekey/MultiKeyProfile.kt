package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/**
 * 多鍵模糊拼音方案的數據驅動配置（客語 12 鍵 / 普通話 9 鍵共享同一條多鍵管線）。
 *
 * 所有「逐段確認、左列推進、原碼跨度綁定、未選完防誤上屏、DEL 逐級撤銷、preedit 具象化」
 * 均為多鍵通用能力；各方案僅通過實現此接口聲明自己的按鍵映射、合法音節集與聲母規則。
 */
sealed interface MultiKeyProfile {
    /** 緩存鍵與診斷標識（如 `"12key"` / `"9key"`）。 */
    val id: String

    /** 合法碼位字符集（含分詞符 `'`）。 */
    val codeKeys: String

    /** 是否允許在過長無法切分編碼時按首鍵合成佔位詞（客語 12 鍵開啟，普通話 9 鍵關閉）。 */
    val allowLongCodeSynthesis: Boolean

    fun isCodeChar(c: Char): Boolean = c in codeKeys

    fun isCodeString(input: String): Boolean =
        input.isNotEmpty() && input.all { it in codeKeys || it == '\'' || it.isWhitespace() }

    /** 單個碼鍵對應的字母集合（如 12 鍵 `'5' → "kl"`；9 鍵 `'9' → "wxyz"`）。 */
    fun keyLetters(c: Char): String

    /** 單個音節轉回原碼串（如 12 鍵 `"hak" → "ia5"`；9 鍵 `"tai" → "824"`）。 */
    fun codeOf(syllable: String): String

    /** 音節的首拼單元（12 鍵含 `th/ng/nz` 組合聲母；9 鍵為首字母）。 */
    fun initialUnit(syllable: String): String

    /** 待確認碼首鍵可選的合法首拼單元列表（按字典序去重）。 */
    fun keyInitialUnits(codeChar: Char, isLast: Boolean): List<String>

    /** 是否為本方案合法的完整音節。 */
    fun isKnownSyllable(syllable: String): Boolean

    /** 與碼串精確匹配的完整音節列表。 */
    fun exactSyllables(code: String): List<String>

    /** 碼串前綴命中的全部合法完整音節（按消費碼長降序、音節字典序）。 */
    fun syllablesCompletingPrefix(pending: String): List<String>

    /** 指定首拼單元集合對應的全部合法音節。 */
    fun syllablesByInitials(initials: List<String>): List<String>

    /** 是否為僅允許出現在末段的兒化等後綴音節（僅 12 鍵 `r`）。 */
    fun isSuffixOnlySyllable(syllable: String): Boolean

    /** 兩個音節是否為合法的拼寫別名（如 12 鍵方言變體、9 鍵 `nv/nu`）。 */
    fun isSpellingAlias(a: String, b: String): Boolean

    /** 某首字母是否允許作為簡拼首字母（12 鍵排除 `aeiou`；9 鍵允許零聲母 `a/e/o`）。 */
    fun allowsAbbrevInitial(letter: Char): Boolean

    /** 整個剩餘碼串能否完整切分為若干合法音節。 */
    fun hasFullSyllable(code: String): Boolean

    /** 把原碼串切分為 `(首段碼, 該段匹配的音節列表)` 供精確確認與回退跨度計算。 */
    fun decodeSpans(code: String): List<Pair<String, String>>?

    /** 確定首段完整音節 [reading] 對應的碼長（不借用簡拼或失配邊界）。 */
    fun resolveExactCodeLength(reading: String, effectiveCode: String): Int?

    /** 將首候選讀音具象化到已輸入碼位上，不補未輸入音節或韻母。 */
    fun rewritePreedit(raw: String, firstText: String?, firstComment: String?, rawInput: String): String?

    /** 無候選兜底時把單個原碼鍵轉為代表字母。 */
    fun fallbackLetter(codeChar: Char): String

    /** 客語 12 鍵配置：完全委託 [TwelveKeyPreedit]，100% 保持既有語義與單測兼容。 */
    object Hakka12Key : MultiKeyProfile {
        override val id: String = "12key"
        override val codeKeys: String = "aei5479381wy'"
        override val allowLongCodeSynthesis: Boolean = true

        override fun isCodeChar(c: Char): Boolean =
            if (TwelveKeyPreedit.hasTokenBindings()) {
                c == '\'' || TwelveKeyPreedit.isBoundHandle(c)
            } else {
                c in codeKeys
            }

        override fun isCodeString(input: String): Boolean =
            input.isNotEmpty() && input.all { isCodeChar(it) || it == '\'' || it.isWhitespace() }

        private val fallbackMap = mapOf(
            '3' to "u",
            '1' to "ng",
            'w' to "th",
            'y' to "nz",
            '4' to "m",
            '5' to "k",
            '7' to "p",
            '8' to "z",
            '9' to "q",
        )

        override fun keyLetters(c: Char): String = TwelveKeyPreedit.keyLetters(c)
        override fun codeOf(syllable: String): String = TwelveKeyPreedit.codeOf(syllable)
        override fun initialUnit(syllable: String): String = TwelveKeyPreedit.initialUnit(syllable)
        override fun keyInitialUnits(codeChar: Char, isLast: Boolean): List<String> =
            TwelveKeyPreedit.keyInitialUnits(codeChar, isLast)
        override fun isKnownSyllable(syllable: String): Boolean = TwelveKeyPreedit.isKnownSyllable(syllable)
        override fun exactSyllables(code: String): List<String> = TwelveKeyPreedit.exactSyllables(code)
        override fun syllablesCompletingPrefix(pending: String): List<String> =
            TwelveKeyPreedit.syllablesCompletingPrefix(pending)
        override fun syllablesByInitials(initials: List<String>): List<String> =
            TwelveKeyPreedit.syllablesByInitials(initials)
        override fun isSuffixOnlySyllable(syllable: String): Boolean =
            TwelveKeyPreedit.isSuffixOnlySyllable(syllable)
        override fun isSpellingAlias(a: String, b: String): Boolean =
            TwelveKeyPreedit.isSpellingAlias(a, b)
        override fun allowsAbbrevInitial(letter: Char): Boolean = letter !in "aeiou"
        override fun hasFullSyllable(code: String): Boolean = TwelveKeyPreedit.hasFullSyllable(code)
        override fun decodeSpans(code: String): List<Pair<String, String>>? = TwelveKeyPreedit.decodeSpans(code)
        override fun resolveExactCodeLength(reading: String, effectiveCode: String): Int? =
            TwelveKeyPreedit.exactCodeLengthForSyllable(reading, effectiveCode)
        override fun rewritePreedit(
            raw: String,
            firstText: String?,
            firstComment: String?,
            rawInput: String,
        ): String? = TwelveKeyPreedit.rewrite(raw, firstText, firstComment, rawInput = rawInput)

        override fun fallbackLetter(codeChar: Char): String =
            if (TwelveKeyPreedit.hasTokenBindings()) TwelveKeyPreedit.fallbackTokenLetter(codeChar)
            else fallbackMap[codeChar] ?: codeChar.toString()
    }

    /**
     * 普通話 9 鍵（T9）配置：基於標準 414 個漢語拼音音節表（與內置 `cn_dicts/8105.dict.yaml` 同源）。
     * 嚴格約束合法音節與合法首拼，絕不生成非法拼音。
     */
    object Mandarin9Key : MultiKeyProfile {
        override val id: String = "9key"
        override val codeKeys: String = "23456789'"
        override val allowLongCodeSynthesis: Boolean = false

        private val keyMap: Map<Char, String> = mapOf(
            '2' to "abc",
            '3' to "def",
            '4' to "ghi",
            '5' to "jkl",
            '6' to "mno",
            '7' to "pqrs",
            '8' to "tuv",
            '9' to "wxyz",
        )

        private val letterToKey: Map<Char, Char> = buildMap {
            keyMap.forEach { (k, letters) ->
                letters.forEach { ch -> put(ch, k) }
            }
        }

        private val syllables: Set<String> = (
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bu " +
                "ca cai can cang cao ce cei cen ceng cha chai chan chang chao che chen cheng chi chong chou " +
                "chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
                "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
                "e ei en eng er fa fan fang fei fen feng fiao fo fou fu " +
                "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
                "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
                "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
                "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
                "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve " +
                "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
                "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve " +
                "o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
                "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
                "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo " +
                "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou " +
                "shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo " +
                "ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
                "wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
                "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
                "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou " +
                "zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"
            ).split(' ').toSet()

        private fun defaultCodeOf(syllable: String): String {
            if (syllable.isEmpty()) return ""
            val lower = syllable.lowercase()
            val sb = StringBuilder(lower.length)
            for (ch in lower) {
                val mapped = letterToKey[ch] ?: return ""
                sb.append(mapped)
            }
            return sb.toString()
        }

        private val codeToSyllables: Map<String, List<String>> = syllables
            .groupBy { defaultCodeOf(it) }
            .mapValues { (_, list) -> list.sorted() }

        private val initialToSyllables: Map<String, List<String>> = syllables
            .groupBy { it.first().toString() }
            .mapValues { (_, list) -> list.sorted() }

        private val initialUnitsByKey: Map<Char, List<String>> = keyMap.mapValues { (_, letters) ->
            letters.map { it.toString() }.filter { it in initialToSyllables }.sorted()
        }

        private class NineTrieNode {
            val children = arrayOfNulls<NineTrieNode>(26)
            var syllable: String? = null
        }

        private val trieRoot: NineTrieNode = NineTrieNode().apply {
            syllables.forEach { syll ->
                var cur = this
                for (i in 0 until syll.length) {
                    val ch = syll[i]
                    if (ch !in 'a'..'z') return@forEach
                    val idx = ch - 'a'
                    cur = cur.children[idx] ?: NineTrieNode().also { cur.children[idx] = it }
                }
                cur.syllable = syll
            }
        }

        private val maxSyllableLen: Int = syllables.maxOf { it.length }

        private fun isCodeLetter(c: Char): Boolean =
            if (TwelveKeyPreedit.hasTokenBindings()) TwelveKeyPreedit.isBoundHandle(c)
            else c in '2'..'9'

        override fun isCodeChar(c: Char): Boolean = c == '\'' || isCodeLetter(c)

        override fun isCodeString(input: String): Boolean =
            input.isNotEmpty() && input.all { isCodeChar(it) || it.isWhitespace() }

        override fun keyLetters(c: Char): String {
            if (TwelveKeyPreedit.hasTokenBindings()) {
                val bound = TwelveKeyPreedit.tokenBindingsSnapshot()[c]
                if (!bound.isNullOrEmpty()) return bound.joinToString("")
            }
            return keyMap[c.lowercaseChar()].orEmpty()
        }

        override fun codeOf(syllable: String): String {
            if (syllable.isEmpty()) return ""
            val lower = syllable.lowercase()
            if (TwelveKeyPreedit.hasTokenBindings()) {
                return TwelveKeyPreedit.encodeRawByBindings(lower)
            }
            return defaultCodeOf(lower)
        }

        override fun initialUnit(syllable: String): String =
            syllable.firstOrNull()?.lowercaseChar()?.toString().orEmpty()

        override fun keyInitialUnits(codeChar: Char, isLast: Boolean): List<String> {
            if (TwelveKeyPreedit.hasTokenBindings()) {
                val tokens = TwelveKeyPreedit.tokenBindingsSnapshot()[codeChar]
                if (!tokens.isNullOrEmpty()) {
                    return tokens.filter { it in initialToSyllables }.sorted()
                }
            }
            return initialUnitsByKey[codeChar].orEmpty()
        }

        override fun isKnownSyllable(syllable: String): Boolean = syllable.lowercase() in syllables

        private fun stepTrieNode(node: NineTrieNode, tok: String): NineTrieNode? {
            var cur = node
            for (i in 0 until tok.length) {
                val ch = tok[i]
                if (ch !in 'a'..'z') return null
                cur = cur.children[ch - 'a'] ?: return null
            }
            return cur
        }

        private fun exactSyllablesForClean(clean: String): List<String> {
            if (clean.isEmpty()) return emptyList()
            if (!TwelveKeyPreedit.hasTokenBindings()) return codeToSyllables[clean].orEmpty()
            val bindings = TwelveKeyPreedit.tokenBindingsSnapshot()
            val out = ArrayList<String>(4)
            fun walk(stepIdx: Int, node: NineTrieNode) {
                if (stepIdx == clean.length) {
                    node.syllable?.let { out.add(it) }
                    return
                }
                val toks = bindings[clean[stepIdx]] ?: return
                for (tok in toks) {
                    val next = stepTrieNode(node, tok) ?: continue
                    walk(stepIdx + 1, next)
                }
            }
            walk(0, trieRoot)
            if (out.size > 1) out.sort()
            return out
        }

        override fun exactSyllables(code: String): List<String> =
            exactSyllablesForClean(code.filter { isCodeLetter(it) })

        override fun syllablesCompletingPrefix(pending: String): List<String> {
            val clean = pending.filter { isCodeLetter(it) }
            if (clean.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            val maxLen = minOf(maxSyllableLen, clean.length)
            for (len in maxLen downTo 1) {
                val hits = exactSyllablesForClean(clean.substring(0, len))
                for (s in hits) {
                    if (seen.add(s)) out.add(s)
                }
            }
            return out
        }

        override fun syllablesByInitials(initials: List<String>): List<String> {
            if (initials.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            for (unit in initials) {
                initialToSyllables[unit]?.let { out.addAll(it) }
            }
            return out
        }

        override fun isSuffixOnlySyllable(syllable: String): Boolean = false

        override fun isSpellingAlias(a: String, b: String): Boolean {
            if (a == b) return true
            val pair = if (a < b) a to b else b to a
            return pair == ("lu" to "lv") ||
                pair == ("lue" to "lve") ||
                pair == ("nu" to "nv") ||
                pair == ("nue" to "nve")
        }

        override fun allowsAbbrevInitial(letter: Char): Boolean = letter in 'a'..'z' && letter !in "iu"

        override fun hasFullSyllable(code: String): Boolean = decodeSpans(code) != null

        override fun decodeSpans(code: String): List<Pair<String, String>>? {
            val clean = code.filter { isCodeLetter(it) }
            if (clean.isEmpty()) return null
            val n = clean.length
            val dp = arrayOfNulls<Pair<Int, String>>(n + 1)
            dp[0] = 0 to ""
            for (i in 0 until n) {
                if (dp[i] == null) continue
                val maxLen = minOf(maxSyllableLen, n - i)
                for (len in maxLen downTo 1) {
                    val hits = exactSyllablesForClean(clean.substring(i, i + len))
                    if (hits.isEmpty()) continue
                    val next = i + len
                    if (dp[next] == null) {
                        dp[next] = i to hits.first()
                    }
                }
            }
            if (dp[n] == null) return null
            val result = ArrayList<Pair<String, String>>()
            var cur = n
            while (cur > 0) {
                val (prev, syll) = dp[cur] ?: return null
                result.add(clean.substring(prev, cur) to syll)
                cur = prev
            }
            result.reverse()
            return result
        }

        override fun resolveExactCodeLength(reading: String, effectiveCode: String): Int? {
            val clean = effectiveCode.filter { isCodeLetter(it) }
            val full = codeOf(reading)
            if (full.isEmpty() || !clean.startsWith(full)) return null
            // 映射回 effectiveCode 含分詞符的實際跨度
            var matched = 0
            for (i in effectiveCode.indices) {
                if (isCodeLetter(effectiveCode[i])) {
                    matched++
                    if (matched == full.length) return i + 1
                }
            }
            return null
        }

        override fun rewritePreedit(
            raw: String,
            firstText: String?,
            firstComment: String?,
            rawInput: String,
        ): String? {
            val cleanRaw = raw.replace("\u2038", "")
            val comment = firstComment?.trim().orEmpty()
            val base = if (comment.isEmpty()) {
                cleanRaw
            } else {
                PreeditPresentation.materializeSegmentedPreedit(cleanRaw, comment)
            }
            if (!TwelveKeyPreedit.hasTokenBindings()) return base
            return decodeRemainingBoundHandles(base)
        }

        private fun decodeRemainingBoundHandles(text: String): String {
            var hasHandle = false
            for (i in text.indices) {
                if (TwelveKeyPreedit.isBoundHandle(text[i])) {
                    hasHandle = true
                    break
                }
            }
            if (!hasHandle) return text
            val out = StringBuilder(text.length + 8)
            var i = 0
            while (i < text.length) {
                if (!TwelveKeyPreedit.isBoundHandle(text[i])) {
                    out.append(text[i])
                    i++
                    continue
                }
                var j = i
                while (j < text.length && TwelveKeyPreedit.isBoundHandle(text[j])) j++
                val handleRun = text.substring(i, j)
                if (i > 0 && text[i - 1] != ' ' && text[i - 1] != '\'') {
                    out.append(' ')
                }
                out.append(decodeNineKeyHandleRun(handleRun))
                i = j
            }
            return out.toString()
        }

        private fun decodeNineKeyHandleRun(run: String): String {
            val spans = decodeSpans(run)
            if (spans != null) return spans.joinToString(" ") { it.second }
            for (cut in run.length - 1 downTo 1) {
                val head = decodeSpans(run.substring(0, cut)) ?: continue
                val tailLetters = run.substring(cut).map { fallbackLetter(it) }.joinToString("")
                return head.joinToString(" ") { it.second } + " " + tailLetters
            }
            return run.map { fallbackLetter(it) }.joinToString("")
        }

        override fun fallbackLetter(codeChar: Char): String =
            keyLetters(codeChar).firstOrNull()?.toString() ?: codeChar.toString()
    }

    companion object {
        /** 當前活躍的多鍵方案配置（由 TextInputManager 按鍵盤類型自動切換）。 */
        @Volatile
        var active: MultiKeyProfile = Hakka12Key
    }
}
