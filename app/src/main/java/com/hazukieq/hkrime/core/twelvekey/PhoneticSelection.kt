package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 已確認的拼音段；code 保存實際原碼，reading 不參與按顯示長度扣碼。 */
data class PhoneticSelection(val reading: String, val code: String, val initialOnly: Boolean) {
    companion object {
        fun pending(code: String, selections: List<PhoneticSelection>): String =
            code.drop(selections.sumOf { it.code.length })

        fun constraint(selections: List<PhoneticSelection>): String = selections.joinToString(" ") {
            (if (it.initialOnly) "~" else "=") + it.reading
        }

        fun matches(reading: String, selections: List<PhoneticSelection>): Boolean {
            if (selections.isEmpty()) return true
            val tokens = TwelveKeyPreedit.parseComment(reading) ?: return false
            // 部分選字可以只覆蓋已確認序列的前幾段。
            return tokens.isNotEmpty() && selections.take(tokens.size).zip(tokens).all { (selection, token) ->
                if (selection.initialOnly) token.startsWith(selection.reading) else token == selection.reading
            }
        }

        fun matchesInput(reading: String, code: String, end: Int, selections: List<PhoneticSelection>): Boolean {
            if (!matches(reading, selections)) return false
            val tokens = TwelveKeyPreedit.parseComment(reading) ?: return false
            val confirmedEnd = selections.take(tokens.size).sumOf { it.code.length }
            if (tokens.size <= selections.size) return end == confirmedEnd
            val tail = code.drop(confirmedEnd)
            val offsets = tail.indices.filter { tail[it] != '\'' && !tail[it].isWhitespace() }
            val raw = offsets.map { tail[it] }.joinToString("")
            var position = 0
            for (token in tokens.drop(selections.size)) {
                if (position == raw.length) break
                val full = TwelveKeyPreedit.codeOf(token)
                val aliasLength = (minOf(6, raw.length - position) downTo 1).firstOrNull { length ->
                    TwelveKeyPreedit.exactSyllables(raw.substring(position, position + length)).any {
                        TwelveKeyPreedit.isSpellingAlias(token, it)
                    }
                }
                when {
                    raw.startsWith(full, position) -> position += full.length
                    aliasLength != null -> position += aliasLength
                    token.first() in TwelveKeyPreedit.keyLetters(raw[position]) && token.first() !in "aeiou" -> position++
                    full.startsWith(raw.substring(position)) -> position = raw.length
                    else -> return false
                }
            }
            val matchedEnd = confirmedEnd + (offsets.getOrNull(position - 1)?.plus(1) ?: 0)
            return code.substring(matchedEnd, end.coerceAtLeast(matchedEnd)).all { it == '\'' || it.isWhitespace() } &&
                end >= matchedEnd
        }

        fun resolve(reading: String, code: String, initialOnly: Boolean): PhoneticSelection? {
            if (code.isEmpty()) return null
            val end = if (initialOnly) {
                // th/ng/nz 是組合聲母單元，按整段匹配，不拆字母。
                if (reading.isEmpty() || reading !in InitialDisambiguation.choices(code)) return null
                1
            } else {
                // 確認完整音節不能借用簡拼或失配解碼的回退邊界。
                TwelveKeySnapshotBuilder.splitParts(code).firstOrNull { reading in it.syllables }
                    ?.let { code.length - it.rest.length } ?: return null
            }
            var boundary = end
            while (boundary < code.length && (code[boundary] == '\'' || code[boundary].isWhitespace())) boundary++
            return PhoneticSelection(reading, code.take(boundary), initialOnly)
        }
    }
}
