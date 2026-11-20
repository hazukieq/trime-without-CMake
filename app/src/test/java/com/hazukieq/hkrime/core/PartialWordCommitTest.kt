package com.hazukieq.hkrime.core

import com.hazukieq.hkrime.core.twelvekey.CandidateInputBoundary

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 驗證部分選詞按實際輸入音節數扣碼，保留未選尾碼。 */
class PartialWordCommitTest {

    companion object {
        private lateinit var preedit: TwelveKeyPreedit

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
            preedit.loadDictLines(dict.asSequence().filter { it.contains('\t') })
            preedit.finishLoading(counts)
        }
    }

    @Test
    fun readingToCode() {
        // lin=5i4、gi=ii、jit=8i9、dung=e34i
        assertEquals("5i4ii8i9e34i", TwelveKeyPreedit.codeOfReading("lin gi jit dung"))
    }

    @Test
    fun remainderOfPartialWord() {
        // 灵机一动来的：lin gi jit dung + loi dit；灵机一动 = 5i4ii8i9e34i，来的 = 54iei9
        assertEquals(
            "54iei9",
            CandidateInputBoundary.remainingCodeAfter("lin gi jit dung", "5i4ii8i9e34i54iei9"),
        )
        // 用戶實際打的是 tung（动 = tung = 934i），學習詞 readings 記的是 dung；
        // 前綴比對會在這裡失配，必須按音節切分取前 4 個音節。
        assertEquals(
            "54iei9",
            CandidateInputBoundary.remainingCodeAfter("lin gi jit dung", "5i4ii8i9934i54iei9"),
        )
        // 詞碼吃掉整個輸入 → 沒有剩餘
        assertEquals("", CandidateInputBoundary.remainingCodeAfter("lin gi jit dung", "5i4ii8i9e34i"))
        // 簡拼/切不出完整音節的碼串不猜剩餘（維持整詞上屏）
        assertEquals("", CandidateInputBoundary.remainingCodeAfter("lin gi jit dung", "aiaa4"))
    }
}
