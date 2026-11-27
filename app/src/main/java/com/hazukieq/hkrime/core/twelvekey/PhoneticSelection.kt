package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 已確認的拼音段；code 保存實際原碼，reading 不參與按顯示長度扣碼。 */
data class PhoneticSelection(val reading: String, val code: String, val initialOnly: Boolean) {
    companion object {
        /** 未確認的原始尾碼（保留可能出現的前導分詞符 `'`，用於精確對齊原碼跨度）。 */
        fun rawTail(code: String, selections: List<PhoneticSelection>): String =
            code.drop(selections.sumOf { it.code.length })

        /** 待確認的有效碼串（去掉已確認段後的分詞符與空白，供音節/首拼展開）。 */
        fun pending(code: String, selections: List<PhoneticSelection>): String =
            rawTail(code, selections).trimStart { it == '\'' || it.isWhitespace() }

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
            val confirmedEnd = selections.take(tokens.size).sumOf { it.code.length }.coerceAtMost(code.length)
            if (tokens.size <= selections.size) {
                var coreEnd = confirmedEnd
                while (coreEnd > 0 && (code[coreEnd - 1] == '\'' || code[coreEnd - 1].isWhitespace())) coreEnd--
                if (end < coreEnd || end > code.length) return false
                return if (end <= confirmedEnd) true
                else code.substring(confirmedEnd, end).all { it == '\'' || it.isWhitespace() }
            }
            val profile = MultiKeyProfile.active
            val tail = code.drop(confirmedEnd)
            val offsets = tail.indices.filter { tail[it] != '\'' && !tail[it].isWhitespace() }
            val raw = offsets.map { tail[it] }.joinToString("")
            var position = 0
            for (token in tokens.drop(selections.size)) {
                if (position == raw.length) break
                val full = profile.codeOf(token)
                val aliasLength = (minOf(6, raw.length - position) downTo 1).firstOrNull { length ->
                    profile.exactSyllables(raw.substring(position, position + length)).any {
                        profile.isSpellingAlias(token, it)
                    }
                }
                when {
                    full.isNotEmpty() && raw.startsWith(full, position) -> position += full.length
                    aliasLength != null -> position += aliasLength
                    token.first() in profile.keyLetters(raw[position]) && profile.allowsAbbrevInitial(token.first()) -> position++
                    full.isNotEmpty() && full.startsWith(raw.substring(position)) -> position = raw.length
                    else -> return false
                }
            }
            val matchedEnd = confirmedEnd + (offsets.getOrNull(position - 1)?.plus(1) ?: 0)
            return end >= matchedEnd && end <= code.length &&
                code.substring(matchedEnd, end).all { it == '\'' || it.isWhitespace() }
        }

        fun resolve(reading: String, code: String, initialOnly: Boolean): PhoneticSelection? {
            if (code.isEmpty()) return null
            val lead = code.indexOfFirst { it != '\'' && !it.isWhitespace() }
            if (lead < 0) return null
            val effective = code.substring(lead)
            val end = if (initialOnly) {
                // 12 鍵 th/ng/nz 整段匹配，9 鍵為合法首字母。
                if (reading.isEmpty() || reading !in InitialDisambiguation.choices(effective)) return null
                1
            } else {
                // 確認完整音節不能借用簡拼或失配解碼的回退邊界。
                MultiKeyProfile.active.resolveExactCodeLength(reading, effective) ?: return null
            }
            var boundary = lead + end
            while (boundary < code.length && (code[boundary] == '\'' || code[boundary].isWhitespace())) boundary++
            return PhoneticSelection(reading, code.take(boundary), initialOnly)
        }
    }
}
