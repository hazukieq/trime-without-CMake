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

    private data class Undo(val pick: Pick?, val phonetics: List<PhoneticSelection>)

    private data class State(
        val remaining: String = "",
        val journal: List<Pick> = emptyList(),
        val completion: Completion? = null,
        val owner: EditorIdentity? = null,
        val undo: List<Undo> = emptyList(),
        /** 已確認拼音段（含簡拼首段）；code 是實際原碼跨度，不參與顯示長度換算。 */
        val phonetics: List<PhoneticSelection> = emptyList(),
    )
    @Volatile private var state = State()
    val remaining: String get() = state.remaining
    val picks: List<Pick> get() = state.journal.toList()
    val selectedText: String get() = state.journal.joinToString("") { it.text }
    val active: Boolean get() = state.let { it.remaining.isNotEmpty() || it.journal.isNotEmpty() }
    val canUndo: Boolean get() = state.undo.isNotEmpty()
    /** 最後一個「只確認首拼」的單元（th/ng/nz 整段），供面板顯示選中態；完整音節不算。 */
    val initial: String? get() = state.phonetics.lastOrNull()?.takeIf { it.initialOnly }?.reading
    val phonetics: List<PhoneticSelection> get() = state.phonetics.toList()

    /** 待確認原碼：已確認段只按實際原碼跨度扣減。 */
    val pending: String get() = PhoneticSelection.pending(state.remaining, state.phonetics)

    /** 最後一段是簡拼且原碼已到尾：字母行點按是「細化」，不是再確認一段。 */
    val refinable: Boolean get() {
        val last = state.phonetics.lastOrNull() ?: return false
        return last.initialOnly && pending.isEmpty() && last.code.length == state.remaining.length
    }

    @Synchronized fun bindEditor(editor: EditorIdentity): Boolean {
        val old = state
        if (active && old.owner != null && !old.owner.same(editor)) return false
        state = old.copy(owner = editor)
        return true
    }

    fun belongsTo(editor: EditorIdentity): Boolean = state.owner?.same(editor) != false

    @Synchronized fun input(code: String) {
        val old = state
        val prefix = old.phonetics.joinToString("") { it.code }
        val kept = old.phonetics.takeIf { code.startsWith(prefix) }.orEmpty()
        state = old.copy(remaining = code, completion = old.completion.takeIf { code == old.remaining },
            phonetics = kept,
            undo = if (kept.size == old.phonetics.size) old.undo
            else old.undo.take(old.undo.indexOfLast { it.pick != null } + 1))
    }

    /**
     * 確認一段：`initialOnly` 消費待確認碼的首鍵（簡拼），否則消費實際匹配的音節跨度。
     * 只鎖定讀音，不選字、不給提交許可。
     */
    @Synchronized fun confirmPhonetic(reading: String, initialOnly: Boolean): Boolean {
        val old = state
        if (old.completion != null) return false
        val pending = PhoneticSelection.pending(old.remaining, old.phonetics)
        val selection = PhoneticSelection.resolve(reading, pending, initialOnly) ?: return false
        state = old.copy(phonetics = old.phonetics + selection,
            undo = old.undo + Undo(null, old.phonetics))
        return true
    }

    /** 同一鍵的另一個首拼：替換最後一段的字母，不改變原碼跨度。 */
    @Synchronized fun refineInitial(letter: String): Boolean {
        val old = state
        val last = old.phonetics.lastOrNull() ?: return false
        if (!last.initialOnly || last.reading == letter) return false
        if (PhoneticSelection.pending(old.remaining, old.phonetics).isNotEmpty()) return false
        if (letter !in InitialDisambiguation.choices(last.code)) return false
        state = old.copy(phonetics = old.phonetics.dropLast(1) + last.copy(reading = letter),
            undo = old.undo + Undo(null, old.phonetics))
        return true
    }

    /**
     * 左列字母行統一入口：還有待確認碼就確認下一段的首拼；否則替換最後一段的字母。
     */
    @Synchronized fun onInitialLetter(letter: String): Boolean {
        val old = state
        if (old.remaining.isEmpty() || old.completion != null) return false
        val pending = PhoneticSelection.pending(old.remaining, old.phonetics)
        return if (pending.isEmpty()) refineInitial(letter)
        else confirmPhonetic(letter, initialOnly = true)
    }

    /** consumedEnd 是實際原碼的偏移，必須由選擇解析器證實，不能以空尾碼猜測。 */
    @Synchronized fun pick(text: String, reading: String, consumedEnd: Int, origin: Origin): Boolean {
        val old = state
        if (text.isEmpty() || old.completion != null || consumedEnd !in 1..old.remaining.length) return false
        val before = old.remaining
        var end = consumedEnd
        while (end < before.length && (before[end] == '\'' || before[end].isWhitespace())) end++
        var phoneticEnd = 0
        var confirmed = 0
        for (selection in old.phonetics) {
            phoneticEnd += selection.code.length
            if (phoneticEnd > end) {
                if (end > phoneticEnd - selection.code.length) return false
                break
            }
            confirmed++
        }
        val journal = old.journal + Pick(text, reading, before, before.substring(0, end), origin)
        val remaining = before.substring(end)
        state = State(remaining, journal, if (remaining.isEmpty()) complete(journal) else null, old.owner,
            undo = old.undo + Undo(journal.last(), old.phonetics),
            phonetics = old.phonetics.drop(confirmed))
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
        val action = old.undo.lastOrNull() ?: return null
        val code = action.pick?.beforeCode ?: old.remaining
        state = old.copy(remaining = code,
            journal = if (action.pick == null) old.journal else old.journal.dropLast(1),
            completion = null,
            phonetics = action.phonetics,
            undo = old.undo.dropLast(1))
        return code
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
