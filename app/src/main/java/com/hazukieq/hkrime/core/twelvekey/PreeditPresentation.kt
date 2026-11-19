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

    private data class State(val selected: String = "", val frame: Frame? = null)
    @Volatile private var state = State()
    private data class RenderKey(val state: State, val raw: String, val input: String,
        val engineText: String?, val engineComment: String?)
    private data class Cached(val key: RenderKey, val text: String?)
    @Volatile private var cached: Cached? = null

    @Synchronized
    fun publish(snapshot: TwelveKeySnapshot, selected: String): Boolean {
        val first = snapshot.barItems.firstOrNull()
        val next = State(selected, if (snapshot.isEmpty) null else Frame(
            snapshot.input, snapshot.rawPreedit, first?.text.orEmpty(), first?.comment.orEmpty(), snapshot.inlineText,
        ))
        val changed = next != state
        state = next
        return changed
    }

    @Synchronized
    fun invalidate(selected: String) { state = State(selected) }

    fun render(raw: String?, input: String, engineText: String? = null, engineComment: String? = null): String? {
        val current = state
        if (raw.isNullOrEmpty()) return current.selected.takeIf { it.isNotEmpty() }
        val key = RenderKey(current, raw, input, engineText, engineComment)
        cached?.takeIf { it.key == key }?.let { return it.text }
        val frame = current.frame?.takeIf { it.matches(input, raw) }
        val rewritten = TwelveKeyPreedit.rewrite(raw, frame?.text ?: engineText, frame?.comment ?: engineComment)
        val text = rewritten ?: raw.replace("\u2038", "")
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
