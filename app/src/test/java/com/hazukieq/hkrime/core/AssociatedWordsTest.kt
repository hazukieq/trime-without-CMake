package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import com.hazukieq.hkrime.core.twelvekey.PreeditPresentation
import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshot
import org.junit.Test
import java.io.File

/** 驗證聯想行使用兩個具體音節、首字母匹配、整串前綴與 r 後綴規則。 */
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
        // 多字母鍵必須覆蓋 g/h 等全部合法首字母。
        val initials = preedit.initialsForPrefix("i", limit = 2)
        assertEquals("i 碼應展開出兩個首字母：$initials", 2, initials.size)
        assertTrue("應包含 g：$initials", initials.contains('g'))
        assertTrue("應包含 h：$initials", initials.contains('h'))
    }

    @Test
    fun mixedCodeMatrixKeepsAssociationRows() {
        // 聯想行按首字母匹配，標題使用具體讀音。
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
        // 整串詞前綴要求第一音節已打全。
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
        // r 僅允許作末段後綴。
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
        // 聯想選行遵守分桶和字典序規則。
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
        // 空引擎仍需生成完整聯想來源。
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
        // 聯想行配額覆蓋實際可選詞。
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

    /** 首選讀音只具體化已輸入碼位，未輸入的 an 不補全。 */
    @Test
    fun rewriteUsesFirstCandidateReading() {
        assertEquals("kiung th", preedit.rewrite("5i34i w\u2038", "共产", "kiung than"))
    }

    /** 詞候選 comment 優先決定 preedit 顯示讀音。 */
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

    /** 首條三音節：未輸入的韻母不顯示。 */
    @Test
    fun rewriteUsesThreeSyllableWordReading() {
        assertEquals("kiung th d", preedit.rewrite("5i34iwe", "共產黨", "kiung than dong"))
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

    /** 首選只改寫能與原碼匹配的部分，其餘原碼保留。 */
    @Test
    fun rewriteConcretizesOnlyMatchingCandidatePrefixes() {
        assertEquals("li voi w", preedit.rewrite("5i34iw", "理會", "li voi"))
    }

    /** 首選僅覆蓋前段時，未覆蓋尾碼原樣顯示。 */
    @Test
    fun rewriteAppendsRawCodeWhenFirstCandidateCoversOnlyPart() {
        // 較長輸入不得被 preedit 渲染吞掉尾碼。
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

    /** 合法拼寫別名按本地完整音節消耗碼位。 */

    @Test
    fun rewriteConsumesWholeEncodedSyllableForDerivedSpellingAlias() {
        // 顯示仍用 comment（所見即所讀），但整個 a3 都被吃掉，不能再補一個 `3`。
        assertEquals("cu", preedit.rewrite("a3", "次", "cw"))
        assertEquals("ha cu", preedit.rewrite("iaa3", "下次", "ha cw"))
    }

    /** 簡拼引申（一個鍵代表一個音節）仍然只吃一個碼位，不能誤吃後面。 */
    @Test
    fun rewriteKeepsSingleKeyForAbbreviatedSyllable() {
        assertEquals("h", preedit.rewrite("i", "下", "ha"))
        assertEquals("h cu", preedit.rewrite("ia3", "下厨", "ha cu"))
    }

    @Test
    fun rewriteKeepsExtraKeysWhenCommentOnlyCoversPrefixByAbbreviation() {
        val comment = List(6) { "qa" }.joinToString(" ")
        assertEquals("q q q q q q 9", preedit.rewrite("9999999\u2038", "哈哈", comment))
        assertEquals("q q q q q q", preedit.rewrite("999999\u2038", "哈哈", comment))
    }

    /** 帶權重詞條僅第二欄作讀音。 */

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


    /** 未完成尾碼保留於詞候選讀音之後。 */
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
        // 8 8 只有两个首字母键，显示 j j，不补 i/en。
        assertEquals("j j", preedit.rewrite("8 8\u2038", "医院", "ji jen"))
    }

    /** 引擎沒給 comment（方案沒開 spelling_hints）時退回本地解碼。 */
    @Test
    fun rewriteFallsBackToOwnDecodeWithoutComment() {
        assertEquals("kiung th", preedit.rewrite("5i34iw", "共产", null))
    }

    /** comment 可能被包成 `［…］`（comment_format 的 xform），要剝掉再顯示。 */
    @Test
    fun rewriteStripsBracketWrappedComment() {
        assertEquals("kiung th", preedit.rewrite("5i34i w\u2038", "共产", "［kiung than］"))
    }

    /** 已選中的前綴（咧）必須保留，只改寫尾部碼段。 */
    @Test
    fun rewriteKeepsSelectedPrefix() {
        assertEquals("咧le", preedit.rewrite("咧5e", "咧", null))
    }

    /** 尾端沒有碼段（已全部選完）時回傳 null，保持 librime 原樣。 */
    /** 僅引擎與字典都無候選時退為原字母。 */
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
        val raw = "5i34i w\u2038"
        PreeditPresentation.publish(TwelveKeySnapshot("5i34iw", 0, "5i34iw", emptyList(), null,
            barEntries = listOf(CandidateItem("共產", "kiung than", 0)), rawPreedit = raw), "")
        assertEquals("kiung th", PreeditPresentation.render(raw, "5i34iw", "其它", "ki biet"))
        PreeditPresentation.invalidate("")
    }

    @Test
    fun `render falls back to engine hint when bar snapshot is absent`() {
        PreeditPresentation.invalidate("")
        assertEquals("kiung th", PreeditPresentation.render("5i34i w\u2038", "5i34iw", "共產", "kiung than"))
    }

    @Test
    fun `render keeps selected prefix and appends pending first`() {
        PreeditPresentation.invalidate("")
        PreeditPresentation.invalidate("以")
        // 已選前綴「以」保留；尾部碼 8 轉成本地解碼；暫存字在最前
        val rendered = PreeditPresentation.render("以8\u2038", "8", "以", "ji")
        assertTrue(rendered!!.startsWith("以"))
        assertTrue(rendered.startsWith("以以"))
        PreeditPresentation.invalidate("")
    }

    @Test
    fun `render returns null when there is nothing to rewrite`() {
        assertNull(PreeditPresentation.render(null, ""))
        assertNull(PreeditPresentation.render("", ""))
    }
}
