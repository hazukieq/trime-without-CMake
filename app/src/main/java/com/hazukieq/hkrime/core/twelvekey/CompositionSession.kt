package com.hazukieq.hkrime.core.twelvekey

/** 不依賴 Android/JNI/UI 的選擇日誌；只有明確完成選擇才能提交。 */
class CompositionSession {
    enum class Origin { BAR, PANEL, SPACE, RETURN, ISOLATED, FALLBACK }

    data class Pick(
        val text: String,
        val reading: String,
        val beforeCode: String,
        val consumedCode: String,
        val origin: Origin,
    )

    class EditorIdentity(val info: Any?, val connection: Any?) {
        fun same(other: EditorIdentity?): Boolean = other != null && info === other.info && connection === other.connection
    }

    data class Completion(val text: String, val code: String, val picks: List<Pick>)

    private data class State(
        val remaining: String = "",
        val journal: List<Pick> = emptyList(),
        val completion: Completion? = null,
        val owner: EditorIdentity? = null,
    )
    @Volatile private var state = State()
    val remaining: String get() = state.remaining
    val picks: List<Pick> get() = state.journal.toList()
    val selectedText: String get() = state.journal.joinToString("") { it.text }
    val active: Boolean get() = state.let { it.remaining.isNotEmpty() || it.journal.isNotEmpty() }
    val canUndo: Boolean get() = state.journal.isNotEmpty()

    @Synchronized fun bindEditor(editor: EditorIdentity): Boolean {
        val old = state
        if (active && old.owner != null && !old.owner.same(editor)) return false
        state = old.copy(owner = editor)
        return true
    }

    fun belongsTo(editor: EditorIdentity): Boolean = state.owner?.same(editor) != false

    @Synchronized fun input(code: String) {
        val old = state
        state = old.copy(remaining = code, completion = old.completion.takeIf { code == old.remaining })
    }

    /** consumedEnd 是實際原碼的偏移，必須由選擇解析器證實，不能以空尾碼猜測。 */
    @Synchronized fun pick(text: String, reading: String, consumedEnd: Int, origin: Origin): Boolean {
        val old = state
        if (text.isEmpty() || old.completion != null || consumedEnd !in 1..old.remaining.length) return false
        val before = old.remaining
        var end = consumedEnd
        while (end < before.length && (before[end] == '\'' || before[end].isWhitespace())) end++
        val journal = old.journal + Pick(text, reading, before, before.substring(0, end), origin)
        val remaining = before.substring(end)
        state = State(remaining, journal, if (remaining.isEmpty()) complete(journal) else null, old.owner)
        return true
    }

    /** 用戶刪光尾碼後的明確確認；單純刷新不會調用。 */
    @Synchronized fun confirmSelected(): Boolean {
        val old = state
        if (old.remaining.isNotEmpty() || old.journal.isEmpty()) return false
        state = old.copy(completion = complete(old.journal))
        return true
    }

    private fun complete(journal: List<Pick>) = Completion(
        journal.joinToString("") { it.text }, journal.joinToString("") { it.consumedCode }, journal.toList(),
    )

    @Synchronized fun undo(): String? {
        val old = state
        val pick = old.journal.lastOrNull() ?: return null
        state = State(pick.beforeCode, old.journal.dropLast(1), owner = old.owner)
        return pick.beforeCode
    }

    fun completed(): Completion? = state.completion
    fun allowsCommit(text: String): Boolean = state.let {
        it.remaining.isEmpty() && it.completion?.text == text && text.isNotEmpty()
    }

    /** 只有編輯器確認成功才消耗許可，失敗時仍可重試/撤銷。 */
    @Synchronized fun committed(text: String, editor: EditorIdentity? = null): Boolean {
        if (state.owner != null && !state.owner!!.same(editor)) return false
        if (!allowsCommit(text)) return false
        state = State()
        return true
    }

    /** 許可檢查、寫入、成功 ACK 同一交易；拒絕/false/例外均保留原日誌。 */
    @Synchronized fun commitThrough(text: String, editor: EditorIdentity? = null, write: () -> Boolean): Completion? {
        if (state.owner != null && !state.owner!!.same(editor)) return null
        if (!allowsCommit(text)) return null
        val completed = state.completion ?: return null
        if (!write()) return null
        state = State()
        return completed
    }

    @Synchronized fun cancel() { state = State() }
}
