package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit

/** 顯示狀態一次發布；沒有選詞、撤銷、提交或引擎寫操作。 */
object PreeditPresentation {
    data class Frame(
        val input: String,
        val rawPreedit: String,
        val text: String,
        val comment: String,
        val inline: String,
    ) {
        fun matches(code: String, raw: String): Boolean = input == code && rawPreedit == raw
    }

    private data class State(val selected: String = "", val frame: Frame? = null,
        val phonetics: List<PhoneticSelection> = emptyList())
    @Volatile private var state = State()
    private data class RenderKey(val state: State, val raw: String, val input: String,
        val engineText: String?, val engineComment: String?)
    private data class Cached(val key: RenderKey, val text: String?)
    @Volatile private var cached: Cached? = null

    @Synchronized
    fun publish(snapshot: TwelveKeySnapshot, selected: String): Boolean {
        val first = snapshot.barItems.firstOrNull()
        val next = State(selected, if (snapshot.isEmpty) null else Frame(
            snapshot.input, snapshot.rawPreedit, first?.text.orEmpty(), (first?.inputSpan?.reading?.takeIf { it.isNotEmpty() }
                ?: first?.engineSpan?.sourceComment ?: first?.comment).orEmpty(), snapshot.inlineText,
        ), snapshot.phonetics)
        val changed = next != state
        state = next
        return changed
    }

    @Synchronized
    fun invalidate(selected: String, phonetics: List<PhoneticSelection> = emptyList()) {
        state = State(selected, phonetics = phonetics)
    }

    fun render(raw: String?, input: String, engineText: String? = null, engineComment: String? = null): String? {
        val current = state
        if (raw.isNullOrEmpty()) return current.selected.takeIf { it.isNotEmpty() }
        val key = RenderKey(current, raw, input, engineText, engineComment)
        cached?.takeIf { it.key == key }?.let { return it.text }
        val frame = current.frame?.takeIf { it.matches(input, raw) }
        val text = if (current.phonetics.isEmpty()) {
            TwelveKeyPreedit.rewrite(raw, frame?.text ?: engineText, frame?.comment ?: engineComment)
                ?: raw.replace("\u2038", "")
        } else {
            val pending = PhoneticSelection.pending(input, current.phonetics)
            val tailReading = TwelveKeyPreedit.parseComment(frame?.comment ?: engineComment.orEmpty())
                ?.drop(current.phonetics.size)?.joinToString(" ")
            val tail = if (pending.isEmpty()) "" else TwelveKeyPreedit.rewrite(pending, null, tailReading) ?: pending
            (current.phonetics.map { it.reading } + listOf(tail).filter { it.isNotEmpty() }).joinToString("'")
        }
        val rendered = (current.selected + text).takeIf { it.isNotEmpty() }
        cached = Cached(key, rendered)
        return rendered
    }

    fun preview(raw: String, input: String): String {
        val current = state
        val frame = current.frame?.takeIf { it.matches(input, raw) }
        return if (frame != null && frame.inline.isNotEmpty()) current.selected + frame.inline
        else render(raw, input).orEmpty()
    }
}
