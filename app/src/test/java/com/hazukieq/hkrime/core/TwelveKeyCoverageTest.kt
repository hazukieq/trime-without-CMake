package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 驗證全鍵位段組、後綴 r、聯想配額、空引擎兜底與組合模式。 */
class TwelveKeyCoverageTest {

    companion object {
        private lateinit var preedit: TwelveKeyPreedit

        /** 鍵帽 → 該鍵送出的碼位（與 hakka_theme.trime.yaml 的 preset_keys 對應）。 */
        val KEYS: List<Pair<String, Char>> = listOf(
            "nz" to 'y', "qt" to '9', "abc" to 'a', "def" to 'e', "ng" to '1', "ghi" to 'i',
            "kl" to '5', "mno" to '4', "th" to 'w', "ps" to '7', "vur" to '3', "zj" to '8',
        )

        /** 從詞典實際統計：每個碼位覆蓋的音節首字母（不硬編碼，避免字典沒有該首字母時誤報）。 */
        private var availableInitials: Map<Char, Set<Char>> = emptyMap()

        @BeforeClass
        @JvmStatic
        fun load() {
            preedit = TwelveKeyPreedit
            val dict = File("src/main/assets/rime/hakka.dict.yaml").readLines()
            val counts = HashMap<String, Int>()
            dict.forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[1].isNotBlank()) {
                    counts[parts[1]] = (counts[parts[1]] ?: 0) + 1
                }
            }
            val lines = dict.asSequence().filter { it.contains('\t') }
            preedit.loadDictLines(lines)
            preedit.finishLoading(counts)
            // 聯想詞也要一起覆蓋：詞表只有詞條沒有讀音，索引靠 charTable 反推。
            listOf(
                "hakka_sougou_words", "hakka_mobilewords", "hakka_oftenwords",
                "hakka_places", "hakka_poems", "hakka_website", "hakka_zon", "hakka_cn_en",
            ).forEach { name ->
                preedit.loadWordLines(
                    File("src/main/assets/rime/$name.dict.yaml").readLines().asSequence()
                )
            }
            preedit.finishWordIndex()
            val byCode = HashMap<Char, MutableSet<Char>>()
            counts.keys.forEach { syllable ->
                val code = preedit.codeOf(syllable)
                if (code.isEmpty()) return@forEach
                byCode.getOrPut(code[0]) { HashSet() }.add(syllable.first())
            }
            availableInitials = byCode
        }
    }

    @Test
    fun allTwelveKeysProduceSyllables() {
        for ((label, code) in KEYS) {
            val got = preedit.syllablesForSegment(code.toString(), 16)
            assertTrue("[$label/$code] 應有音節，實際 $got", got.isNotEmpty())
            // 每個音節的碼必須以該鍵的碼位開頭（精確或前綴）
            val bad = got.filterNot { preedit.codeOf(it).startsWith(code) }
            assertTrue("[$label/$code] 音節碼與鍵位不符：$bad", bad.isEmpty())
        }
    }

    @Test
    fun multiLetterKeysCoverEveryAvailableInitial() {
        // 後綴 r 不作前段首字母。
        val suffixInitials = TwelveKeyPreedit.SUFFIX_ONLY_SYLLABLES.map { it.first() }.toSet()
        val multiLetter = listOf('a', 'e', 'i', '5', '4', '7', '3', '8', '9')
        for (code in multiLetter) {
            val initials = availableInitials[code]?.toSet().orEmpty()
            assertTrue("[$code] 詞典裡應有以該鍵開頭的整首字母", initials.isNotEmpty())
            val plainInitials = initials - suffixInitials

            // ① 非結尾段（默認 allowSuffixOnly=false）：不得出現後綴音節
            val got = preedit.syllablesForSegment(code.toString(), 16)
            val gotInitials = got.map { it.first() }.toSet()
            assertTrue(
                "[$code] 非結尾段不得出現後綴音節（r），實際 $got",
                got.none { TwelveKeyPreedit.isSuffixOnlySyllable(it) },
            )
            for (initial in plainInitials) {
                assertTrue(
                    "[$code] 應覆蓋首字母 $initial，實際 $gotInitials（$got）",
                    initial in gotInitials,
                )
            }

            // ② 結尾段（allowSuffixOnly=true）：後綴音節要能選到
            val gotAsLast = preedit.syllablesForSegment(
                code.toString(), 16, allowSuffixOnly = true,
            )
            assertTrue(
                "[$code] 結尾段應覆蓋全部首字母 $initials，實際 ${gotAsLast.map { it.first() }.toSet()}",
                gotAsLast.map { it.first() }.toSet().containsAll(initials),
            )

            val viaPrefix = preedit.initialsForPrefix(code.toString(), 4).toSet()
            assertTrue(
                "[$code] initialsForPrefix 應含全部可用首字母 $plainInitials，實際 $viaPrefix",
                viaPrefix.containsAll(plainInitials),
            )
        }
    }

    @Test
    fun singleLetterKeysAreListed() {
        // nz/ng/th 這類「一個鍵就是一個完整碼位組合」的鍵也要能出音節（不能因為沒有前綴展開而為空）
        for (code in listOf('y', '1', 'w')) {
            val got = preedit.syllablesForSegment(code.toString(), 8)
            assertTrue("[$code] 單字母鍵也必須有音節，實際 $got", got.isNotEmpty())
        }
    }

    @Test
    fun everyKeyPairBuildsSingleGroups() {
        // 12 × 12 = 144 種「鍵 + 鍵」：任一條混合碼都必須能生成單字組（面板才有東西可點）
        var checked = 0
        for ((labelA, codeA) in KEYS) {
            for ((labelB, codeB) in KEYS) {
                val codes = "$codeA$codeB"
                val groups = TwelveKeySnapshotBuilder.debugSegmentGroups(codes)
                assertTrue("[$labelA+$labelB=$codes] 應有分組", groups.isNotEmpty())
                assertTrue(
                    "[$labelA+$labelB=$codes] 每組都必須有候選字",
                    groups.all { it.items.isNotEmpty() },
                )
                assertTrue(
                    "[$labelA+$labelB=$codes] 至少要有一個單字組（segment=0）",
                    groups.any { it.segment == 0 },
                )
                checked++
            }
        }
        assertEquals(144, checked)
    }

    @Test
    fun afterPickingFirstSegmentRemainingTwoCodesCoverBothReadings() {
        // 部分選字後應按剩餘編碼重新生成段組。
        val groups = TwelveKeySnapshotBuilder.debugSegmentGroups("we")
        val readings = groups.map { it.reading }
        println("PROBE we groups(single)=" + groups.filter { it.segment == 0 }.map { it.reading })
        println("PROBE we groups(assoc)=" + groups.filter { it.segment == 1 }.map { it.reading })
        assertTrue("應含完整音節 the：$readings", "the" in readings)
        assertTrue("應含 than（w|e 拆法）：$readings", "than" in readings)
        assertTrue("應含 thu/thi 等同族音節：$readings", readings.any { it == "thu" || it == "thi" })
        // 反例：不能只出 the 系列
        assertTrue("不能只有 the 系列：$readings", readings.any { it != "the" && !it.startsWith("the") })
        // 聯想行：第二段只按首字母匹配（th* + d/e/f），行標題形如 `than d`
        val assoc = groups.filter { it.segment == 1 }
        assertTrue("we 應有首字母級聯想行（如 than d），實際 ${assoc.map { it.reading }}", assoc.isNotEmpty())
        // 不足 20 詞的聯想行完整列出。
        val wordCap = com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning.ASSOC_WORDS
        assertTrue(
            "聯想行詞數應 ≤ $wordCap，實際 ${assoc.first().items.size}",
            assoc.first().items.size <= wordCap,
        )
        // 聯想行總額遵守 ASSOC_BUCKET_ROW_BUDGET。
        val budget = com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning.ASSOC_BUCKET_ROW_BUDGET
        assertTrue("聯想行總數應 ≤$budget，實際 ${assoc.size}：${assoc.map { it.reading }}", assoc.size <= budget)
    }

    @Test
    fun singleLetterKeysNgNzSplitLikeOtherKeys() {
        // ng/nz 等特殊聲母遵守相同段組規則。
        val ngGroups = TwelveKeySnapshotBuilder.debugSegmentGroups("1e")
        val nzGroups = TwelveKeySnapshotBuilder.debugSegmentGroups("ye")
        // 只看單字組（segment 0）：聯想行現在按首字母序列匹配，行標題可能是 `ga fui`/`ngen ci` 等。
        val ngSingle = ngGroups.filter { it.segment == 0 }
        val nzSingle = nzGroups.filter { it.segment == 0 }
        assertTrue("1e 應出 ng* 音節，實際 ${ngSingle.map { it.reading }}",
            ngSingle.isNotEmpty() && ngSingle.all { it.reading.startsWith("ng") })
        assertTrue("ye 應出 nz* 音節，實際 ${nzSingle.map { it.reading }}",
            nzSingle.isNotEmpty() && nzSingle.all { it.reading.startsWith("nz") })
        // 同樣要能給出「第一段音節組」而不是只有兜底
        assertTrue(ngGroups.any { it.segment == 0 && it.items.isNotEmpty() })
        assertTrue(nzGroups.any { it.segment == 0 && it.items.isNotEmpty() })
        // 剩餘碼要麼是整段（""）、要麼是拆段剩下的 e —— 都要能遞歸回引擎
        val restCodes = ngGroups.filter { it.segment == 0 }
            .flatMap { g -> g.items.map { it.restCode } }.toSet()
        assertTrue("restCode 只應為 \"\" 或 \"e\"，實際 $restCodes", restCodes.all { it == "" || it == "e" })
        assertTrue("應有拆段項（restCode=e）", restCodes.contains("e"))
        // 1i / yi 同理（i 介音 / nz-i）
        assertTrue(TwelveKeySnapshotBuilder.debugSegmentGroups("1i").isNotEmpty())
        assertTrue(TwelveKeySnapshotBuilder.debugSegmentGroups("yi").isNotEmpty())
    }

    @Test
    fun engineEmptyFallbackPanelKeepsAssociationRows() {
        // 空引擎仍保留聯想、組合和整串詞來源。
        val local = TwelveKeySnapshotBuilder.Local(
            input = "ee5",
            consumedEnd = 0,
            remaining = "ee5",
            segmentsKey = "",
        )
        val snapshot = TwelveKeySnapshotBuilder.build(
            local = local,
            applyLearning = false,
            wantGroups = true,
            candidatesOverride = emptyList(),
        )
        // 12 鍵左列只放確認行；聯想詞行在流水線裏（右側候選來源）。
        val assoc = TwelveKeySnapshotBuilder.debugPanelSources("ee5").merged.filter { it.segment == 1 }
        assertTrue(
            "引擎空兜底時應有聯想詞行，實際 ${assoc.map { it.reading }}",
            assoc.isNotEmpty(),
        )
        assertTrue(
            "詞行標題必須是完整音節（不得含空 token）：${assoc.map { it.reading }}",
            assoc.all { row -> row.reading.split(' ').let { it.size >= 2 && it.all(String::isNotBlank) } },
        )
        assertTrue("推薦組應有聯想詞", snapshot.recommend.isNotEmpty())
        assertTrue(
            "推薦組只能是 CJK 詞/單字：${snapshot.recommend.map { it.text }}",
            snapshot.recommend.all {
                com.hazukieq.hkrime.core.twelvekey.CandidateRanking.isCjkWord(it.text) ||
                    com.hazukieq.hkrime.core.twelvekey.CandidateRanking.isSingleCjkChar(it.text)
            },
        )
    }

    @Test
    fun hybridInitialCombosKeepFullTailSyllable() {
        // 簡拼+完整末音節要產生合法組合。
        val patterns = TwelveKeyPreedit.initialPatterns("aaa4")
        assertTrue(
            "應含 b·can：${patterns.map { it.key }}",
            patterns.any { it.initials == listOf('b') && it.tail == "can" },
        )
        assertTrue(
            "應含 b·cam：${patterns.map { it.key }}",
            patterns.any { it.initials == listOf('b') && it.tail == "cam" },
        )
        assertTrue(
            "簡拼前綴不得含元音：${patterns.filter { it.isHybrid }.map { it.initials }}",
            patterns.filter { it.isHybrid }.all { p -> p.initials.all { it !in "aeiou" } },
        )
    }

    @Test
    fun associationRowAndRecommendKeepFullWordsBelowLimit() {
        // 聯想配額保留符合條件的多音節目標詞。
        val code = "8e4ai4534i8"
        val local = TwelveKeySnapshotBuilder.Local(code, 0, code, "")
        val snapshot = TwelveKeySnapshotBuilder.build(
            local = local,
            applyLearning = false,
            wantGroups = true,
            candidatesOverride = emptyList(),
        )
        assertTrue(
            "推薦應含遠程控制：${snapshot.recommend.map { it.text }}",
            snapshot.recommend.any { it.text == "远程控制" },
        )
        val row = TwelveKeySnapshotBuilder.debugPanelSources(code).merged.firstOrNull { it.reading == "jen cin" }
        assertTrue("jen cin 聯想行應存在", row != null)
        assertTrue(
            "jen cin 行內應含遠程控制：${row!!.items.map { it.text }}",
            row.items.any { it.text == "远程控制" },
        )
    }

    @Test
    fun twoLetterKeyCombinationsKeepItems() {
        // 兩個「多字母鍵」相連（如 abc+def、ghi+mno）最容易出「整串是一個音節」的邊界
        val multiLetterCodes = setOf('a', 'e', 'i', '5', '4', '7', '3', '8', '9')
        for ((la, a) in KEYS.filter { (_, c) -> c in multiLetterCodes }) {
            for ((lb, b) in KEYS.filter { (_, c) -> c in multiLetterCodes }) {
                val groups = TwelveKeySnapshotBuilder.debugSegmentGroups("$a$b")
                assertTrue("[$la+$lb] 應有分組", groups.isNotEmpty())
                assertTrue("[$la+$lb] 分組不能為空組", groups.all { it.items.isNotEmpty() })
            }
        }
    }
}
