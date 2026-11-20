package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import com.hazukieq.hkrime.core.TwelveKeyPreedit.codeOf
import com.hazukieq.hkrime.core.TwelveKeyPreedit.decodeSpans
import com.hazukieq.hkrime.core.TwelveKeyPreedit.isSpellingAlias
import com.hazukieq.hkrime.core.TwelveKeyPreedit.isSuffixOnlySyllable
import com.hazukieq.hkrime.core.TwelveKeyPreedit.keyLetters
import com.hazukieq.hkrime.core.TwelveKeyPreedit.parseComment

/** 只在生成候選時解析原碼邊界；不讀 preedit、顯示長度或 UI 狀態。 */
object CandidateInputBoundary {
    /** 返回已證實選擇後的尾碼；真正選擇應使用可區分 null 的 selectedInputEnd。 */
    fun remainingCodeAfter(wordReading: String, input: String): String =
        selectedInputEnd(wordReading, input)?.let { input.substring(it).trimStart { it == '\'' || it.isWhitespace() } }.orEmpty()

    /** 真正選擇用的碼位證明；與顯示的寬鬆讀音補齊分離，null 表示不能安全選擇。 */
    fun selectedInputEnd(reading: String, input: String): Int? {
        val tokens = parseComment(reading)?.takeIf { it.isNotEmpty() } ?: return null
        if (input.isEmpty()) return null
        val offsets = input.indices.filter { input[it] != '\'' && !input[it].isWhitespace() }
        val codes = offsets.map { input[it] }.joinToString("")
        if (codes.isEmpty()) return null
        if (tokens.any { token -> token.any { it !in 'a'..'z' } }) return null
        if (tokens.dropLast(1).any { isSuffixOnlySyllable(it) }) return null
        var pos = 0
        var matched = true
        for (token in tokens) {
            if (pos == codes.length) break // 引擎補全的詞尾沒有額外按鍵需要消費。
            if (isSuffixOnlySyllable(token) && pos + 1 != codes.length) return null
            val full = codeOf(token)
            if (codes.startsWith(full, pos)) {
                pos += full.length
                continue
            }
            val aliasLength = (minOf(6, codes.length - pos) downTo 1).firstOrNull { length ->
                TwelveKeyPreedit.exactSyllables(codes.substring(pos, pos + length)).any {
                    isSpellingAlias(token, it)
                }
            }
            when {
                aliasLength != null -> pos += aliasLength
                token.first() in keyLetters(codes[pos]) && token.first() !in "aeiou" -> pos++
                full.startsWith(codes.substring(pos)) -> pos = codes.length
                else -> { matched = false; break }
            }
        }
        if (!matched) {
            // 學習詞的異讀可能不同於實際輸入（dung/tung），只用完整輸入跨度回退。
            if (tokens.any { !TwelveKeyPreedit.isKnownSyllable(it) }) return null
            val spans = decodeSpans(codes) ?: return null
            if (spans.size < tokens.size) return null
            pos = spans.take(tokens.size).sumOf { it.first.length }
        }
        return offsets.getOrNull(pos - 1)?.plus(1)
    }

}
