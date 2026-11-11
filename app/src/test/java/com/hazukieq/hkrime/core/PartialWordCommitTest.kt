package com.hazukieq.hkrime.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「詞只覆蓋輸入前幾個音節」時，上屏後要把剩餘碼重新餵回引擎。
 *
 * 用戶 2026-11-11：輸入「灵机一动来的」只上屏「灵机一动」，後面「来的」丟失——
 * 聯想詞/學習詞整詞 commit + clearComposition，沒有 librime select_candidate
 * 的「選中不提交、剩餘輸入保留」語義。
 */
class PartialWordCommitTest {

    @Test
    fun readingToCode() {
        // lin=5i4、gi=ii、jit=8i9、dung=e34i
        assertEquals("5i4ii8i9e34i", TwelveKeyPreedit.codeOfReading("lin gi jit dung"))
    }

    @Test
    fun remainderOfPartialWord() {
        // 灵机一动来的：灵机一动 = 5i4ii8i9e34i，来的 = 54iei9
        assertEquals(
            "54iei9",
            TwelveKeyPreedit.remainingCodeAfter("lin gi jit dung", "5i4ii8i9e34i54iei9"),
        )
        // 詞碼吃掉整個輸入 → 沒有剩餘
        assertEquals("", TwelveKeyPreedit.remainingCodeAfter("lin gi jit dung", "5i4ii8i9e34i"))
        // 簡拼/非前綴碼串不猜剩餘（維持整詞上屏）
        assertEquals("", TwelveKeyPreedit.remainingCodeAfter("lin gi jit dung", "aiaa4"))
    }
}
