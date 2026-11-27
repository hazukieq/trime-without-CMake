package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class CompositionSessionTest {
    @Test fun everySurfaceSequenceCanUndoEverySelectionBeforeCommit() {
        val origins = CompositionSession.Origin.values()
        var cases = 0
        fun run(pattern: List<CompositionSession.Origin>) {
            val session = CompositionSession()
            val parts = listOf("9ai", "i4", "8i", "5i", "73")
            val words = listOf("太", "哥", "只", "里", "铺")
            session.input(parts.joinToString(""))
            pattern.forEachIndexed { index, origin ->
                assertTrue(session.pick(words[index], "", parts[index].length, origin))
                assertEquals(parts.drop(index + 1).joinToString(""), session.remaining)
                assertEquals(words.take(index + 1).joinToString(""), session.selectedText)
                if (index < 4) assertFalse(session.allowsCommit(session.selectedText))
            }
            for (index in pattern.indices.reversed()) {
                assertEquals(parts.drop(index).joinToString(""), session.undo())
                assertEquals(words.take(index).joinToString(""), session.selectedText)
                assertNull(session.completed())
            }
            assertNull(session.undo())
            assertEquals(parts.joinToString(""), session.remaining)
            cases++
        }
        fun enumerate(pattern: List<CompositionSession.Origin>) {
            if (pattern.isNotEmpty()) run(pattern)
            if (pattern.size == 5) return
            origins.forEach { enumerate(pattern + it) }
        }
        enumerate(emptyList())
        assertEquals(9330, cases)
    }

    @Test fun failedEditorWriteRetainsFullUndoJournalAndRetryPermission() {
        val s = CompositionSession()
        s.input("9aii4")
        assertTrue(s.pick("太", "tai", 3, CompositionSession.Origin.BAR))
        assertFalse(s.allowsCommit("太"))
        assertTrue(s.pick("哥", "go", 2, CompositionSession.Origin.PANEL))
        val completion = s.completed()!!
        assertTrue(s.allowsCommit("太哥"))
        assertFalse(s.allowsCommit("哥"))
        assertFalse(s.committed("错误"))
        assertEquals(completion, s.completed()) // 失敗的 InputConnection 不 ACK。
        assertEquals("i4", s.undo())
        assertEquals("太", s.selectedText)
        assertNull(s.completed())
        assertTrue(s.pick("个", "go", 2, CompositionSession.Origin.RETURN))
        assertTrue(s.committed("太个"))
        assertFalse(s.committed("太个"))
        assertFalse(s.active)
    }

    @Test fun ordinaryPunctuationPassesOnlyOutsideAnUnfinishedComposition() {
        for (key in 0..127) {
            assertFalse(CompositionInput.beforeKey("9aii4", 6, key, true).allowRawCommit)
        }
        assertTrue(CompositionInput.beforeKey("", 0, ','.code, false).allowRawCommit)
        assertFalse(CompositionInput.beforeKey("", 0, ','.code, true).allowRawCommit)
        for (key in "aei5479381wy'") {
            val intent = CompositionInput.beforeKey("", 0, key.code, false)
            assertEquals(key.toString(), intent.code)
            assertFalse(intent.allowRawCommit)
        }
        assertEquals("9ai4", CompositionInput.beforeKey("9aii4", 3, 0xff08, false).code)
        assertEquals("9a5ii4", CompositionInput.beforeKey("9aii4", 2, '5'.code, true).code)
    }

    @Test fun completionCannotWriteIntoAnotherEditorConnection() {
        val info = Any()
        val connection = Any()
        val owner = CompositionSession.EditorIdentity(info, connection)
        val other = CompositionSession.EditorIdentity(info, Any())
        val state = CompositionSession()
        assertTrue(state.bindEditor(owner))
        state.input("i4")
        assertTrue(state.pick("哥", "go", 2, CompositionSession.Origin.BAR))
        assertFalse(state.bindEditor(other))
        var writes = 0
        assertNull(state.commitThrough("哥", other) { writes++; true })
        assertEquals(0, writes)
        assertNotNull(state.completed())
        assertNotNull(state.commitThrough("哥", CompositionSession.EditorIdentity(info, connection)) { writes++; true })
        assertEquals(1, writes)
        assertTrue(state.bindEditor(other))
    }

    @Test fun actualCommitTransactionNeverCallsEditorForPartialOrUnexpectedText() {
        val s = CompositionSession()
        s.input("9aii4")
        assertTrue(s.pick("太", "tai", 3, CompositionSession.Origin.BAR))
        var writes = 0
        assertNull(s.commitThrough("太") { writes++; true })
        assertEquals(0, writes)
        assertTrue(s.pick("哥", "go", 2, CompositionSession.Origin.PANEL))
        assertNull(s.commitThrough("错误") { writes++; true })
        assertEquals(0, writes)
        assertNull(s.commitThrough("太哥") { writes++; false })
        assertNotNull(s.completed())
        try {
            s.commitThrough("太哥") { throw IllegalStateException("editor unavailable") }
            fail("must propagate editor failure")
        } catch (_: IllegalStateException) { assertNotNull(s.completed()) }
        assertNotNull(s.commitThrough("太哥") { writes++; true })
        assertEquals(2, writes)
        assertFalse(s.active)
        assertNull(s.commitThrough("太哥") { writes++; true })
        assertEquals(2, writes)
    }

    @Test fun refreshOrEmptyEngineNeverCreatesCompletion() {
        val s = CompositionSession()
        s.input("55")
        assertTrue(s.pick("客", "ka", 1, CompositionSession.Origin.ISOLATED))
        repeat(100) { assertNull(s.completed()); assertFalse(s.allowsCommit("客")) }
        s.input("") // 刪碼/取消/引擎異常都不等於確認選擇。
        assertNull(s.completed())
        assertFalse(s.allowsCommit("客"))
        assertEquals("55", s.undo())
        s.cancel()
        assertFalse(s.active)
        assertFalse(s.confirmSelected())
    }

    @Test fun separatorsRestoreExactlyAndCannotProducePartialCommit() {
        val s = CompositionSession()
        s.input("9ai'i4")
        assertTrue(s.pick("太", "tai", 3, CompositionSession.Origin.BAR))
        assertEquals("i4", s.remaining)
        assertEquals("9ai'i4", s.undo())
        assertFalse(s.pick("太", "tai", 0, CompositionSession.Origin.BAR))
        assertFalse(s.pick("太", "tai", 99, CompositionSession.Origin.PANEL))
        assertEquals("9ai'i4", s.remaining)
    }

    @Test fun duplicateAndLeadingDelimitersAreIgnoredAndConsecutiveDelimitersCollapseOnBackspace() {
        // 空輸入：分詞專用鍵忽略，長按/實體引號（allowWhenEmpty=true）放行
        assertTrue(CompositionInput.shouldIgnoreDelimiter("", 0, allowWhenEmpty = false))
        assertFalse(CompositionInput.shouldIgnoreDelimiter("", 0, allowWhenEmpty = true))
        // 組字開頭不能插引號
        assertTrue(CompositionInput.shouldIgnoreDelimiter("7i4", 0))
        // 正常碼位後允許插一次分詞符
        assertFalse(CompositionInput.shouldIgnoreDelimiter("7i4", 3))
        assertFalse(CompositionInput.shouldIgnoreDelimiter("7i45i", 3))
        // 連續重複分詞符（光標前或光標後已有 `'`）一律忽略
        assertTrue(CompositionInput.shouldIgnoreDelimiter("7i4'", 4))
        assertTrue(CompositionInput.shouldIgnoreDelimiter("7i4'5i", 4))
        assertTrue(CompositionInput.shouldIgnoreDelimiter("7i4'5i", 3))
        assertTrue(CompositionInput.shouldIgnoreDelimiter("an'", 3))

        // 單次退格：若意外存在多個連續 `'`，一次全刪
        assertEquals(0, CompositionInput.backspaceCount("7i4", 0))
        assertEquals(1, CompositionInput.backspaceCount("7i4", 3))
        assertEquals(1, CompositionInput.backspaceCount("7i4'", 4))
        assertEquals(3, CompositionInput.backspaceCount("7i4'''", 6))
        assertEquals("7i4", CompositionInput.applyBackspace("7i4'''", 6))
        assertEquals("7i45i", CompositionInput.applyBackspace("7i4'''5i", 6))
        assertEquals("7i4", CompositionInput.beforeKey("7i4'''", 6, 0xff08, false).code)
        assertEquals("an", CompositionInput.applyBackspace("an''''", 6))

        // 尾端含分詞符時，覆蓋所有實質碼位即視為全碼覆蓋
        assertTrue(CompositionInput.coversFullCode("7i4aa4", 6))
        assertTrue(CompositionInput.coversFullCode("7i4aa4'", 6))
        assertFalse(CompositionInput.coversFullCode("7i4aa4'", 3))
        assertFalse(CompositionInput.coversFullCode("7i4aa4", 0))
    }

    @Test fun backspaceDeletesTypedTailBeforeUndoingPickOrPhonetic() {
        val s = CompositionSession()
        s.input("9aii4")
        assertFalse(s.shouldUndoOnBackspace)

        // 選中「太」後未追加輸入 → 退格立刻撤銷「太」
        assertTrue(s.pick("太", "tai", 3, CompositionSession.Origin.BAR))
        assertEquals("i4", s.remaining)
        assertTrue(s.shouldUndoOnBackspace)

        // 選中「太」後繼續輸入新碼或分詞符 → 退格先刪新輸入，回到 i4 才撤銷「太」
        s.input("i4'")
        assertFalse(s.shouldUndoOnBackspace)
        s.input("i4'8i")
        assertFalse(s.shouldUndoOnBackspace)
        s.input("i4'8")
        assertFalse(s.shouldUndoOnBackspace)
        s.input("i4'")
        assertFalse(s.shouldUndoOnBackspace)
        s.input("i4")
        assertTrue(s.shouldUndoOnBackspace)

        // 回到選後邊界：撤銷「太」還原 9aii4
        assertEquals("9aii4", s.undo())
        assertEquals("", s.selectedText)
        assertFalse(s.shouldUndoOnBackspace)
    }

    @Test fun multiPickAndPhoneticRollbackPreservesEarlierConfirmedSegments() {
        val s = CompositionSession()
        s.input("9ai")
        assertTrue(s.confirmPhonetic("tai", initialOnly = false))
        assertTrue(s.shouldUndoOnBackspace)

        // 確認 tai 後加分詞符再打 i4：退格應先刪 4 / i / '，保留 tai
        s.input("9ai'")
        assertFalse(s.shouldUndoOnBackspace)
        s.input("9ai'i4")
        assertFalse(s.shouldUndoOnBackspace)
        assertEquals("i4", s.pending)

        // 確認第二段 go（code 包含前導 `'`）
        assertTrue(s.confirmPhonetic("go", initialOnly = false))
        assertEquals(2, s.phonetics.size)
        assertEquals("'i4", s.phonetics[1].code)
        assertEquals("", s.pending)
        assertTrue(s.shouldUndoOnBackspace)

        // 撤銷第二段 go → 回到剩餘 9ai'i4 且保留 tai
        assertEquals("9ai'i4", s.undo())
        assertEquals(listOf(PhoneticSelection("tai", "9ai", false)), s.phonetics)
        assertFalse(s.shouldUndoOnBackspace)

        // 繼續刪尾碼：9ai'i4 -> 9ai'i -> 9ai' -> 9ai，期間 tai 始終保留
        s.input("9ai'i")
        assertEquals(1, s.phonetics.size)
        assertFalse(s.shouldUndoOnBackspace)
        s.input("9ai'")
        assertEquals(1, s.phonetics.size)
        assertFalse(s.shouldUndoOnBackspace)
        s.input("9ai")
        assertEquals(1, s.phonetics.size)
        assertTrue(s.shouldUndoOnBackspace)

        // 回到確認時邊界 → 退格撤銷 tai
        assertEquals("9ai", s.undo())
        assertTrue(s.phonetics.isEmpty())
        assertFalse(s.canUndo)

        // 若兩段皆確認後縮短第二段碼（9ai'i4 -> 9ai'i），只丟第二段，保留第一段 tai 及其撤銷項
        assertTrue(s.confirmPhonetic("tai", initialOnly = false))
        s.input("9ai'i4")
        assertTrue(s.confirmPhonetic("go", initialOnly = false))
        s.input("9ai'i")
        assertEquals(listOf(PhoneticSelection("tai", "9ai", false)), s.phonetics)
        assertTrue(s.canUndo)
        assertEquals("9ai'i", s.undo())
        assertTrue(s.phonetics.isEmpty())
        assertFalse(s.canUndo)
    }

    @Test fun singleSyllableConfirmAllowsInPlaceSiblingSwitchWithoutStackingUndo() {
        val s = CompositionSession()
        s.input("7a3")
        assertTrue(s.confirmPhonetic("pau", initialOnly = false))
        assertEquals(listOf(PhoneticSelection("pau", "7a3", false)), s.phonetics)
        assertEquals("", s.pending)
        // 重複點同一音節為 no-op
        assertFalse(s.confirmPhonetic("pau", initialOnly = false))
        // 同段切換為 sau（原地替換，不堆積撤銷棧）
        assertTrue(s.confirmPhonetic("sau", initialOnly = false))
        assertEquals(listOf(PhoneticSelection("sau", "7a3", false)), s.phonetics)
        // 也可切回該段首拼 s（剩餘 a3 回到待確認）
        assertTrue(s.onInitialLetter("s"))
        assertEquals(listOf(PhoneticSelection("s", "7", true)), s.phonetics)
        assertEquals("a3", s.pending)
        // 單次退格直接撤回未確認狀態
        assertEquals("7a3", s.undo())
        assertTrue(s.phonetics.isEmpty())
        assertFalse(s.canUndo)
    }

    @Test fun deterministicRandomActionTracesMatchIndependentJournal() {
        val random = Random(20261120)
        data class Ref(val text: String, val before: String)
        repeat(100) {
            val s = CompositionSession()
            val journal = ArrayList<Ref>()
            var code = "9aii48i5i73"
            var allowed = false
            s.input(code)
            repeat(1000) {
                when (random.nextInt(5)) {
                    0 -> if (code.isNotEmpty() && !allowed) {
                        val end = 1 + random.nextInt(code.length)
                        val text = "字" + random.nextInt(10)
                        journal.add(Ref(text, code))
                        code = code.substring(end)
                        assertTrue(s.pick(text, "", end, CompositionSession.Origin.values()[random.nextInt(6)]))
                        allowed = code.isEmpty()
                    }
                    1 -> {
                        val expected = if (journal.isEmpty()) null else journal.removeAt(journal.lastIndex).before
                        assertEquals(expected, s.undo())
                        if (expected != null) { code = expected; allowed = false }
                    }
                    2 -> { code += "i"; allowed = false; s.input(code) }
                    3 -> if (allowed) {
                        assertTrue(s.committed(journal.joinToString("") { it.text }))
                        code = ""; journal.clear(); allowed = false
                    }
                    4 -> if (random.nextInt(10) == 0) { s.cancel(); code = ""; journal.clear(); allowed = false }
                }
                val selected = journal.joinToString("") { it.text }
                assertEquals(code, s.remaining)
                assertEquals(selected, s.selectedText)
                assertEquals(allowed, s.allowsCommit(selected))
                assertFalse(s.allowsCommit("未被选择的文字"))
            }
        }
    }
}
