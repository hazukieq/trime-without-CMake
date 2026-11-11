package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 面板「聯想詞組」行（`song g**` / `song h**`）的數據層測試（用戶 2026-11-10 要求）。
 *
 * 詞表只有詞條、沒有讀音，讀音靠 `hakka.dict.yaml` 的單字首讀音反推；
 * 這裡直接用 assets 的同一份數據驗證索引與查詢語義，避免真機上才發現「一行詞都沒有」。
 */
class AssociatedWordsTest {

    companion object {
        private val preedit: TwelveKeyPreedit by lazy { TwelveKeyPreedit }

        @BeforeClass
        @JvmStatic
        fun load() {
            val counts = TwelveKeyPreedit.loadDictLines(
                File("src/main/assets/rime/hakka.dict.yaml").readLines().asSequence()
            )
            TwelveKeyPreedit.finishLoading(counts)
            listOf(
                "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords",
                "hakka_places", "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
            ).forEach { name ->
                TwelveKeyPreedit.loadWordLines(
                    File("src/main/assets/rime/$name.dict.yaml").readLines().asSequence()
                )
            }
            TwelveKeyPreedit.finishWordIndex()
        }
    }

    @Test
    fun songFollowedByGInitialHasWords() {
        val words = preedit.associatedWords("song", 'g')
        assertTrue("song g** 應該有聯想詞，實際 ${words.size}", words.isNotEmpty())
        assertTrue("每行最多 6 條，實際 ${words.size}", words.size <= 6)
        assertTrue("必須是 2~4 字詞：$words", words.all { it.length in 2..4 })
    }

    @Test
    fun songFollowedByHInitialHasWords() {
        val words = preedit.associatedWords("song", 'h')
        assertTrue("song h** 應該有聯想詞，實際 ${words.size}", words.isNotEmpty())
        assertTrue("每行最多 6 條", words.size <= 6)
    }

    @Test
    fun limitIsRespected() {
        assertEquals(2, preedit.associatedWords("song", 'g', limit = 2).size.coerceAtMost(2))
        assertTrue(preedit.associatedWords("song", 'g', limit = 1).size <= 1)
    }

    @Test
    fun unknownCombinationReturnsEmpty() {
        assertTrue(preedit.associatedWords("", 'g').isEmpty())
        // 本詞典沒有 x 開頭的音節，所以「song + x**」必然是空
        assertTrue(preedit.associatedWords("song", 'x').isEmpty())
    }

    @Test
    fun initialsForPrefixEnumeratesDistinctInitials() {
        // 碼段 `i` = g/h/i 開頭的音節 → 至少要能看到 g 與 h（用戶 2026-11-10 報過只有 g）。
        val initials = preedit.initialsForPrefix("i", limit = 2)
        assertEquals("i 碼應展開出兩個首字母：$initials", 2, initials.size)
        assertTrue("應包含 g：$initials", initials.contains('g'))
        assertTrue("應包含 h：$initials", initials.contains('h'))
    }

    @Test
    fun mixedCodeMatrixKeepsAssociationRows() {
        // 12 鍵覆蓋矩陣（用戶 2026-11-10：「你的測試用例還有問題，12鍵根本沒有覆蓋完全」）：
        // 每個混合碼都要能出「第一段音節組 + 至少一行聯想詞」。
        // 2026-11-11 起聯想行按**首字母序列**匹配（元音自由），行標題不再固定首音節：
        // 例如 `5i34iwe` 的第一個碼位 `5` 給 {k,l}，聯想行可能是 `kiung than`、`kau hak`、`len he`…
        // 所以這裡斷言「第一音節首字母落在該鍵的首字母集合裡」，不再釘死具體讀音。
        val cases = mapOf(
            "5i34iwe" to setOf('k', 'l'),   // 5 → k/l
            "744ii" to setOf('p', 's'),     // 7 → p/s
            "5i34iw" to setOf('k', 'l'),
            "i44yi" to null,                // gong + h：只剩前綴，不能崩
        )
        for ((codes, initials) in cases) {
            val groups = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
                .debugSegmentGroups(codes)
            val readings = groups.map { it.reading }
            println("PROBE $codes -> $readings")
            assertTrue("[$codes] 應至少有單字組，實際 $readings", groups.any { it.segment == 0 })
            if (initials != null) {
                val assocRows = groups.filter { it.segment == 1 && it.reading.contains(' ') }
                assertTrue("[$codes] 應有聯想詞行，實際 $readings", assocRows.isNotEmpty())
                assertTrue(
                    "[$codes] 聯想行第一音節首字母應 ∈ $initials，實際 ${assocRows.map { it.reading }}",
                    assocRows.all { it.reading.first() in initials },
                )
                assertTrue("[$codes] 聯想詞行不能是空的", assocRows.all { it.items.isNotEmpty() })
            }
        }
    }

    @Test
    fun wordsByCodePrefixRequiresFirstSyllableFullyTyped() {
        // 用戶 2026-11-11：`ia8i3` 面板只看到 `ga j/z*`，腳本卻列出 `gam se`——
        // 因為 `ia` 只是 `gam`（= ia4）的第一段前綴，詞行的第一段必須是**打完的音節**。
        val word = "网易云"
        val firstCode = TwelveKeyPreedit.codeOf("mong")
        val fullCode = listOf("mong", "ji", "jun").joinToString("") { TwelveKeyPreedit.codeOf(it) }
        assertTrue("[mong] 應命中 $word", preedit.wordsByCodePrefix(firstCode).any { it.second.contains(word) })
        assertTrue("[$fullCode] 應命中 $word", preedit.wordsByCodePrefix(fullCode).any { it.second.contains(word) })
        // 第一段沒打全（少了最後一個碼）→ 不得當成詞行
        assertFalse(
            "[${firstCode.dropLast(1)}] 第一段未打全時不應命中 $word",
            preedit.wordsByCodePrefix(firstCode.dropLast(1)).any { it.second.contains(word) },
        )
    }

    @Test
    fun suffixOnlySyllableRAppearsOnlyAtWordEnd() {
        // 用戶 2026-11-11：「关于 r，这是一个儿后缀的读音。所以它默认不做首字母拼，
        // 只能出现在后面，这是特殊规则」。
        val rCode = TwelveKeyPreedit.codeOf("r")
        assertTrue("r 的碼不應為空", rCode.isNotEmpty())

        // ① r 在最後一段 → 允許（面板要能選「兒」）
        val asLast = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
            .debugSegmentGroups(rCode).map { it.reading }
        assertTrue("r 作為最後一段應出現，實際 $asLast", asLast.contains("r"))

        // ② r 不在最後一段 → 不得出現
        val notLast = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
            .debugSegmentGroups(rCode + "5i").map { it.reading }
        assertTrue("r 不在結尾時不得出現，實際 $notLast", notLast.none { it == "r" })

        // ③ 真實詞「到下儿 = dau ha r」：第一段不得把 r 當音節
        val phrase = listOf("dau", "ha", "r").joinToString("") { TwelveKeyPreedit.codeOf(it) }
        val first = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
            .debugSegmentGroups(phrase).filter { it.segment == 0 }.map { it.reading }
        assertTrue("第一段不得含 r，實際 $first", first.none { it == "r" })

        // ④ 本地解碼同一規則：r 夾在中間就整串不切（寧可保留原碼）
        assertNull("r 不在結尾時本地解碼應失敗", TwelveKeyPreedit.decodeSingle(rCode + "5i"))
    }

    @Test
    fun associatedBySegmentCodeUsesExactSyllables() {
        // 用戶 2026-11-10：行標題必須是兩個完整讀音（`than dung`），不能是 `than d`。
        val rows = preedit.associatedBySegmentCode("than", "e", perRow = 6, maxRows = 2)
        assertTrue("應有行，實際 $rows", rows.isNotEmpty())
        for ((reading, words) in rows) {
            val second = reading.substringAfter(' ')
            assertTrue("第二段必須是完整音節（不是單字母）：$reading", second.length >= 2)
            assertTrue("詞數應 ≤6，實際 ${words.size}", words.size <= 6)
        }
        assertTrue("行數應 ≤2，實際 ${rows.size}", rows.size <= 2)
    }

    @Test
    fun kiungPlusTheStillEmitsAssociationRows() {
        // 用戶 2026-11-10：「kiung th d —— th 和 def 合成 the，導致什麼聯想詞都沒有」。
        // 剩餘碼 `we` 展開不到任何音節（`we` 不是任何音節的碼前綴）→ 面板必須回退到
        // 「該第一音節的全部後續音節」，照樣出聯想行，而不是一行都沒有。
        //
        // 2026-11-11 用戶改定「分桶輪轉 + 字母序 + 行數 12」：具體選中哪幾行由桶的字母序決定
        // （`kiun bak` 這類桶排在 `kiung than` 前面），所以這裡不再釘死某一行，只釘
        // 「有聯想行、行標題是具體兩音節、內容非空」。
        val groups = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
            .debugSegmentGroups("5i34iwe")
        val assoc = groups.filter { it.segment == 1 }
        println("PROBE assoc=${assoc.map { it.reading }}")
        assertTrue("應有聯想詞行，實際 ${groups.map { it.reading }}", assoc.isNotEmpty())
        assertTrue(
            "聯想行標題必須是兩個完整音節：${assoc.map { it.reading }}",
            assoc.all { it.reading.split(' ').size == 2 },
        )
        assertTrue("聯想行不能是空的：${assoc.map { it.reading }}", assoc.all { it.items.isNotEmpty() })
    }

    @Test
    fun kiungFollowedByThanListsCommunistParty() {
        // 用戶 2026-11-10：打 kiung + th 想選「共產黨」三個字，但面板既沒有 than 音節、
        // 也沒有 kiung than 這行聯想詞。這裡釘住：索引要收 3~4 字詞，並按 (第一音節, 第二音節) 命中。
        val rows = preedit.associatedByReading("kiung", setOf('t'), limitPerReading = 6, maxReadings = 6)
        val thanRow = rows.firstOrNull { it.first == "than" }
        assertTrue("應有 kiung than 行，實際 ${rows.map { it.first }}", thanRow != null)
        val words = thanRow!!.second
        assertTrue("kiung than 應含共產黨，實際 $words", words.any { it.contains("共产党") || it.contains("共產黨") })
        // 行截斷必須按熱度、不能按字母序：僅取 2 行時也要保留 than（3 字詞都在這裡）
        val top2 = preedit.associatedByReading("kiung", setOf('t'), 6, 2)
        assertTrue("取 2 行時應保留 than，實際 ${top2.map { it.first }}", top2.any { it.first == "than" })
    }

    @Test
    fun associatedByReadingUsesExactSecondSyllable() {
        val rows = preedit.associatedByReading("song", setOf('h'), limitPerReading = 6, maxReadings = 4)
        assertTrue("song + h 開頭應有讀音行，實際 ${rows.size}", rows.isNotEmpty())
        assertTrue("最多 4 行，實際 ${rows.size}", rows.size <= 4)
        for ((second, words) in rows) {
            assertTrue("第二音節必須是 h 開頭：$second", second.startsWith("h"))
            assertTrue("每行最多 6 條", words.size <= 6)
            assertTrue("必須是 2~4 字詞：$words", words.all { it.length in 2..4 })
        }
    }

    @Test
    fun associatedByReadingHonoursInitialFilter() {
        val onlyG = preedit.associatedByReading("song", setOf('g'), maxReadings = 8)
        assertTrue("song + g 應有讀音行", onlyG.isNotEmpty())
        assertTrue("只保留 g 開頭", onlyG.all { it.first.startsWith("g") })
        assertTrue("空 initials 應返回空", preedit.associatedByReading("song", emptySet()).isEmpty())
    }

    @Test
    fun initialsRespectLimit() {
        assertEquals(1, preedit.initialsForPrefix("i", limit = 1).size)
        assertFalse(preedit.initialsForPrefix("", limit = 2).isNotEmpty())
    }

    // ---- preedit 讀音對齊（docs/14）----

    /** 候選條第一條有 comment 時，preedit 必須用它的精確讀音（`kiung than` 不是 `kiung the`）。 */
    @Test
    fun rewriteUsesFirstCandidateReading() {
        assertEquals("kiung than", preedit.rewrite("5i34i w\u2038", "共产", "kiung than"))
    }

    /**
     * 首條是**多字詞**、讀音段數與本地解碼不同：`5i34i` 本地切出 1 段 kiung，
     * 但首條「理會」讀 `li voi`（2 段，5i|34i 正好吃完 5i34i）—— preedit 要顯示 `li voi`
     * （用戶 2026-11-10 指出：只看段數相等的舊規則會把這種最常見的情況擋掉）。
     */
    @Test
    fun rewriteUsesWordReadingWhenFirstCandidateIsAWord() {
        assertEquals("li voi", preedit.rewrite("5i34i\u2038", "理会", "li voi"))
    }

    /** 同上但碼短一段：`5i34` 的首條「梨渦」讀 `li vo`。 */
    @Test
    fun rewriteUsesWordReadingForShorterCode() {
        assertEquals("li vo", preedit.rewrite("5i34\u2038", "梨涡", "li vo"))
    }

    /** 首條是**單字**時讀音 1 段，同樣要對齊。 */
    @Test
    fun rewriteUsesSingleCharReading() {
        assertEquals("kiung", preedit.rewrite("5i34i\u2038", "共", "kiung"))
    }

    /** 首條是 3 字詞、讀音 3 段（共產黨 = kiung than dong 正好吃完 5i34iwe）。 */
    @Test
    fun rewriteUsesThreeSyllableWordReading() {
        assertEquals("kiung than dong", preedit.rewrite("5i34iwe", "共產黨", "kiung than dong"))
    }

    /** librime 的 `‸`(U+2038) 光標標記不能被當成「已選前綴」，要剝掉再切碼段。 */
    @Test
    fun rewriteStripsCaretMarker() {
        assertEquals("kiung th", preedit.rewrite("5i34iw\u2038", "共产", null))
    }

    /** 半截碼（尾端 `w` 還不是完整音節）也要翻成鍵盤字母；空格分隔的半截碼同理。 */
    @Test
    fun rewriteMapsPartialTailCodeToLetters() {
        assertEquals("kiung th", preedit.rewrite("5i34i w", "共产", null))
    }

    /**
     * 12 鍵多對多、沒有唯一解碼：comment 一律優先（它與候選條同源，是「用戶看到的第一條」的讀音）。
     * 這裡用一串「本地解碼完全對不上」的 comment 釘住契約：不做本地校驗，直接顯示。
     *
     * 尾端的半截鍵（`w`）仍要顯示：comment 只承諾了 `5i`+`34i` 六個碼位裡的五個，
     * 剩下的按「按下的鍵必須全部顯示」原樣補上（與 `5i4a57` 的 `7` 同一條規則）。
     */
    @Test
    fun rewriteTrustsFirstCandidateCommentAsIs() {
        assertEquals("li voi w", preedit.rewrite("5i34iw", "理會", "li voi"))
    }

    /**
     * 首要規則（用戶 2026-11-10）：「輸入的編碼一定要顯示」。
     * 首條候選只覆蓋前面幾節時，後面沒覆蓋的節要用本地解碼補上，不能讓 preedit 比輸入短。
     */
    @Test
    fun rewriteAppendsRawCodeWhenFirstCandidateCoversOnlyPart() {
        // 用戶 2026-11-10：「comment 節數 >= 本地解碼節數 → 只用 comment；否則 comment + 原始編碼。
        // 不要試圖去拼湊。」共產 / kiung than 之於三音節碼 → 尾巴原樣顯示未覆蓋的碼。
        assertEquals("kiung than e44i", preedit.rewrite("5i34iwa4e44i", "共产", "kiung than"))
    }

    @Test
    fun rewriteNeverShortensLongInput() {
        // 中國人民共和國 = zung get nzin min kiung fo get（7 節的碼），首條候選只覆蓋前 4 節 →
        // 後面 10 個碼位原樣顯示（引擎詞庫只有「中國人民」，enable_sentence 關掉了）
        val code = "834iie9yi44i45i34ie4ie9"
        val out = preedit.rewrite(code + "\u2038", "中国人民", "zung get nzin min")!!
        assertEquals("zung get nzin min 5i34ie4ie9", out)
    }

    /**
     * 用戶 2026-11-11：打 `qqqqqq`（= `999999`…）時首條會補全成一串 `qa`，但**再按的鍵會被吞**。
     * 逐節前綴匹配：`qa` 對不上 `99…` → 每節只吃 1 個碼位；超出的那個碼位要原樣顯示。
     */
    @Test
    fun rewriteKeepsExtraKeysWhenCommentOnlyCoversPrefixByAbbreviation() {
        val comment = List(6) { "qa" }.joinToString(" ")
        assertEquals("qa qa qa qa qa qa 9", preedit.rewrite("9999999\u2038", "哈哈", comment))
        assertEquals("qa qa qa qa qa qa", preedit.rewrite("999999\u2038", "哈哈", comment))
    }

    /**
     * 用戶 2026-11-11：打 `li nak s`（碼 `5i4a57`）時面板必須能出 `li nak si` → Linux。
     * 舊聯想索引只按前兩音節建 key，三音節詞打到第三節就查不到。
     */
    /**
     * 用戶 2026-11-11：真機 `hakka_zon.dict.yaml` 的寫法是 `Linux\tli nak si\t1000`（帶權重欄），
     * 舊解析把權重算進最後一節 → 整串碼對不上輸入 → 面板查不到 Linux。
     */
    @Test
    fun weightColumnInReadingDoesNotBreakLookup() {
        TwelveKeyPreedit.loadWordLines(sequenceOf("Linux\tli nak si\t1000"))
        TwelveKeyPreedit.finishWordIndex()
        val rows = TwelveKeyPreedit.wordsByCodePrefix("5i4a57", 4, 6)
        assertTrue("帶權重欄的詞條也要能查到：$rows",
            rows.any { it.first == "li nak si" && "Linux" in it.second })
    }

    @Test
    fun wordsByCodePrefixFindsThreeSyllableWord() {
        // assets 的 zon 詞表裡有 `网易云 / mong ji jun`（三音節）；`Linux / li nak si` 只在
        // 真機 `hakka_zon.dict.yaml`（用戶自己加的），所以單測用前者，真機另驗後者。
        val code = TwelveKeyPreedit.codeOf("mong") + TwelveKeyPreedit.codeOf("ji") +
            TwelveKeyPreedit.codeOf("jun").take(1)
        val rows = TwelveKeyPreedit.wordsByCodePrefix(code, 4, 6)
        assertTrue("整串碼前綴應查到 `mong ji jun` 行：code=$code rows=$rows",
            rows.any { it.first == "mong ji jun" && it.second.isNotEmpty() })
    }


    /**
     * 用戶 2026-11-11 報：`5i4a57`（ki mak + 半截 `7`）只顯示 `ki mak`，尾碼被吃掉。
     * 本地 DP 切不出完整音節（`7` 是單鍵），舊寫法算不出覆蓋長度 → 用 comment 自己的音節碼
     * 逐節吃碼位（ki→5i、mak→4a5 = 5 個），剩下的 `7` 原樣顯示。
     */
    @Test
    fun rewriteKeepsPartialTailAfterWordReading() {
        assertEquals("ki mak 7", preedit.rewrite("5i 4a57\u2038", "气脉", "ki mak"))
        assertEquals("ki mak 7", preedit.rewrite("5i4a57\u2038", "气脉", "ki mak"))
    }

    /** comment 剛好覆蓋全部碼位時不該補尾巴（避免重複）。 */
    @Test
    fun rewriteAddsNoTailWhenCommentCoversAllCode() {
        assertEquals("ki mak", preedit.rewrite("5i 4a5\u2038", "气脉", "ki mak"))
    }

    /** 用戶只按了一聲母鍵時（簡拼），comment 的每一節只吃一個碼位。 */
    @Test
    fun rewriteConsumesOneCodePerSyllableForAbbreviation() {
        // 8 8 = 两个首字母键，首条候选读 ji jen → 全部吃完，不补尾码
        assertEquals("ji jen", preedit.rewrite("8 8\u2038", "医院", "ji jen"))
    }

    /** 引擎沒給 comment（方案沒開 spelling_hints）時退回本地解碼。 */
    @Test
    fun rewriteFallsBackToOwnDecodeWithoutComment() {
        assertEquals("kiung th", preedit.rewrite("5i34iw", "共产", null))
    }

    /** comment 可能被包成 `［…］`（comment_format 的 xform），要剝掉再顯示。 */
    @Test
    fun rewriteStripsBracketWrappedComment() {
        assertEquals("kiung than", preedit.rewrite("5i34i w\u2038", "共产", "［kiung than］"))
    }

    /** 已選中的前綴（咧）必須保留，只改寫尾部碼段。 */
    @Test
    fun rewriteKeepsSelectedPrefix() {
        assertEquals("咧le", preedit.rewrite("咧5e", "咧", null))
    }

    /** 尾端沒有碼段（已全部選完）時回傳 null，保持 librime 原樣。 */
    /**
     * 用戶 2026-11-11：打 `ee5` 時 preedit/候選條顯示原始字母（dek），展開面板再關閉才變成
     * `de 5`/`邸`。根因是「兜底」判據用了面板分組數（bar 模式下恆為空），
     * 於是每一幀都掛 fallback。這裡釘住正確判據：只有**引擎候選為 0** 才兜底。
     */
    @Test
    fun fallbackOnlyWhenEngineHasNoCandidates() {
        val builder = com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
        assertFalse(builder.shouldShowFallback(500, "ee5"))
        assertFalse(builder.shouldShowFallback(1, "ee5"))
        assertTrue(builder.shouldShowFallback(0, "ee5"))
        assertFalse(builder.shouldShowFallback(0, ""))
    }

    @Test
    fun rewriteReturnsNullWhenNoCode() {
        assertNull(preedit.rewrite("咧", null, null))
    }

    @Test
    fun `compositionPrefix keeps selected hanzi and drops codes and caret`() {
        assertEquals("", TwelveKeyPreedit.compositionPrefix(null))
        assertEquals("", TwelveKeyPreedit.compositionPrefix(""))
        // 一格都沒選：碼串 + 光標 → 前綴必須是空（否則嵌入式預覽把原始碼寫進輸入框）
        assertEquals("", TwelveKeyPreedit.compositionPrefix("8 8\u2038"))
        assertEquals("", TwelveKeyPreedit.compositionPrefix("5i34i\u2038"))
        // 選中一段：漢字 + 剩餘碼 + 光標
        assertEquals("以", TwelveKeyPreedit.compositionPrefix("以8\u2038"))
        assertEquals("以院", TwelveKeyPreedit.compositionPrefix("以院 8\u2038"))
        // 漢字與碼串交錯（多段選字）
        assertEquals("以院", TwelveKeyPreedit.compositionPrefix("以8院\u2038"))
    }

    @Test
    fun `compositionPrefix ignores caret only preedit`() {
        assertEquals("", TwelveKeyPreedit.compositionPrefix("\u2038"))
    }

    /** render()：preedit 的唯一入口（讀音來源 / 前綴 / 暫存 三件事的組合）。 */
    @Test
    fun `render prefers bar hint over engine hint`() {
        TwelveKeyPreedit.preferredText = "共產"
        TwelveKeyPreedit.preferredComment = "kiung than"
        assertEquals("kiung than", TwelveKeyPreedit.render("5i34i w\u2038", "其它", "ki biet"))
        TwelveKeyPreedit.preferredText = null
        TwelveKeyPreedit.preferredComment = null
    }

    @Test
    fun `render falls back to engine hint when bar snapshot is absent`() {
        TwelveKeyPreedit.preferredText = null
        TwelveKeyPreedit.preferredComment = null
        assertEquals("kiung than", TwelveKeyPreedit.render("5i34i w\u2038", "共產", "kiung than"))
    }

    @Test
    fun `render keeps selected prefix and appends pending first`() {
        TwelveKeyPreedit.preferredText = null
        TwelveKeyPreedit.preferredComment = null
        TwelveKeyPreedit.pendingPrefix = "以"
        // 已選前綴「以」保留；尾部碼 8 轉成本地解碼；暫存字在最前
        val rendered = TwelveKeyPreedit.render("以8\u2038", "以", "ji")
        assertTrue(rendered!!.startsWith("以"))
        assertEquals("以", TwelveKeyPreedit.pendingPrefix.take(1))
        TwelveKeyPreedit.pendingPrefix = ""
    }

    @Test
    fun `render returns null when there is nothing to rewrite`() {
        assertNull(TwelveKeyPreedit.render(null))
        assertNull(TwelveKeyPreedit.render(""))
    }
}
