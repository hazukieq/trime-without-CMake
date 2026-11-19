package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.TwelveKeyPreedit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** PC 真實引擎 + 生產選擇解析器/狀態機/快照/顯示模型；不以 Python 複製邏輯充當 App 驗收。 */
class NativeCompositionFlowTest {
    companion object {
        private val source = File("/tmp/hkrime-host-run/user")
        private val binary = File("../../rime-jni/host-test/build-host/coverage_test")
        @JvmStatic @BeforeClass fun load() {
            assumeTrue("需要已部署的 PC 引擎與真實設備快照", source.isDirectory && binary.isFile)
            OfflineDictionary.load(File("/tmp/hkrime-real-data"))
        }
    }

    private data class NativeState(
        var input: String = "", var preedit: String = "", var commit: String = "",
        val segments: MutableList<String> = ArrayList(), val candidates: MutableList<CandidateItem> = ArrayList(),
    )

    private class Driver : AutoCloseable {
        private val work = java.nio.file.Files.createTempDirectory("hkrime-composition-flow-").toFile()
        private val process: Process
        private val writer: java.io.PrintWriter
        private val reader: java.io.BufferedReader
        val session = CompositionSession()
        val commits = ArrayList<String>()
        var failWrite = false
        var state = NativeState()
        init {
            check(ProcessBuilder("cp", "-a", source.absolutePath + "/.", work.absolutePath).start().waitFor() == 0)
            process = ProcessBuilder(binary.absolutePath, "--shared", work.absolutePath, "--user", work.absolutePath,
                "--script", "-").redirectError(File(work, "flow.log")).start()
            writer = process.outputStream.bufferedWriter().let { java.io.PrintWriter(it, true) }
            reader = process.inputStream.bufferedReader()
            send("dump")
            PhraseMemory.clear()
            PreeditPresentation.invalidate("")
        }
        fun send(command: String): NativeState {
            writer.println(command)
            val next = NativeState()
            while (true) {
                val line = reader.readLine() ?: error("native EOF while $command")
                if (line == "END") return next.also { state = it }
                val parts = line.split('\t', limit = 4)
                when (parts[0]) {
                    "I" -> next.input = parts.getOrElse(1) { "" }
                    "P" -> next.preedit = parts.getOrElse(1) { "" }
                    "K" -> next.commit = parts.getOrElse(1) { "" }
                    "S" -> next.segments.add(line)
                    "D" -> next.candidates.add(CandidateItem(parts[2],
                        TwelveKeySnapshotBuilder.cleanCommentForSelection(parts.getOrNull(3)), parts[1].toInt()))
                }
            }
        }
        fun type(code: String) { send("clear"); send("type $code"); session.input(state.input) }
        private fun local() = TwelveKeySnapshotBuilder.Local(
            state.input, 0, state.input, state.segments.joinToString("|"), state.preedit,
        )
        fun snapshot(full: Boolean = false): TwelveKeySnapshot = TwelveKeySnapshotBuilder.build(
            local(), applyLearning = true, wantGroups = full, candidatesOverride = state.candidates,
        ).also { PreeditPresentation.publish(it, session.selectedText) }
        fun barWord(text: String): Pair<TwelveKeySnapshot, CandidateItem> {
            val native = state.candidates.take(64).firstOrNull { it.text == text }
                ?: error("word absent from bar window: $text")
            repeat(9) { SelectionMemory.bump(native.text, native.comment) }
            val snap = snapshot()
            return snap to (snap.barItems.firstOrNull { it.text == text } ?: error("not in visible bar: $text"))
        }
        fun panelSyllable(reading: String): Pair<TwelveKeySnapshot, CandidateItem> {
            val snap = snapshot(true)
            val item = foldGroupsForDisplay(snap.groups).asSequence().flatMap { it.items.asSequence() }
                .firstOrNull { it.index < 0 && it.segment == 0 && it.comment == reading }
                ?: error("dictionary syllable absent: $reading")
            return snap to item
        }
        fun choose(pair: Pair<TwelveKeySnapshot, CandidateItem>, origin: CompositionSession.Origin): Boolean {
            val (snap, item) = pair
            if (!CandidateSelection.isCurrent(snap.contextKey, local().contextKey) || state.input.isEmpty()) return false
            val consumed = CandidateSelection.consumedEnd(item, state.input, origin) ?: return false
            session.input(state.input)
            if (!session.pick(item.text, item.comment, consumed, origin)) return false
            if (session.remaining.isNotEmpty()) restore(session.remaining)
            else {
                if (item.index >= 0) send("select ${item.index}")
                send("clear")
                writeCompleted()
            }
            return true
        }
        fun writeCompleted() {
            val final = session.completed() ?: return
            assertTrue(session.allowsCommit(final.text))
            session.commitThrough(final.text) {
                if (failWrite) false else { commits.add(final.text); true }
            }
        }
        fun restore(code: String) { send("clear"); if (code.isNotEmpty()) send("type $code") }
        fun undo() { restore(session.undo() ?: error("no undo record")) }
        fun forcePrematureNativeCommit() {
            val expected = session.remaining
            val unexpected = send("commit")
            assertTrue("native must actually produce a commit", unexpected.commit.isNotEmpty())
            assertFalse(session.allowsCommit(unexpected.commit))
            restore(expected)
            assertEquals(expected, state.input)
        }
        override fun close() {
            PhraseMemory.clear()
            PreeditPresentation.invalidate("")
            writer.println("quit")
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            writer.close(); reader.close()
            ProcessBuilder("trash", work.absolutePath).start().waitFor()
        }
    }

    @Test(timeout = 90000) fun barPanelTransitionsAndEveryUndoKeepAllCodes() {
        Driver().use { d ->
            val original = "9aii48i5i73"
            d.type(original)
            val old = d.barWord("太")
            assertTrue(d.choose(old, CompositionSession.Origin.BAR))
            assertEquals("i48i5i73", d.state.input)
            assertEquals("太", d.session.selectedText)
            assertFalse(d.choose(old, CompositionSession.Origin.BAR)) // 過期/重複點擊。
            val beforeOpen = d.snapshot().barItems
            assertEquals(beforeOpen, d.snapshot(true).barItems)
            assertTrue(d.choose(d.panelSyllable("go"), CompositionSession.Origin.PANEL))
            assertTrue(d.commits.isEmpty())
            d.undo(); assertEquals("i48i5i73", d.state.input); assertEquals("太", d.session.selectedText)
            d.undo(); assertEquals(original, d.state.input); assertEquals("", d.session.selectedText)
            assertTrue(d.choose(d.panelSyllable("tai"), CompositionSession.Origin.PANEL))
            val expected = StringBuilder(d.session.selectedText)
            for (reading in listOf("go", "ji", "li", "pu")) {
                val choice = d.panelSyllable(reading)
                expected.append(choice.second.text)
                assertTrue(d.choose(choice, CompositionSession.Origin.PANEL))
                if (reading != "pu") assertTrue(d.commits.isEmpty())
            }
            assertEquals(listOf(expected.toString()), d.commits)
            assertFalse(d.session.active)
        }
    }

    @Test(timeout = 90000) fun actualNativeAutoCommitIsBlockedAndUndoStillRestoresOriginal() {
        Driver().use { d ->
            val original = "9aii48i5i73"
            d.type(original)
            assertTrue(d.choose(d.barWord("太"), CompositionSession.Origin.RETURN))
            d.forcePrematureNativeCommit()
            assertTrue(d.commits.isEmpty())
            assertEquals("太", d.session.selectedText)
            d.undo(); assertEquals(original, d.state.input)
        }
    }

    @Test(timeout = 90000) fun failedFinalWriteCanUndoAndReselectWithoutDuplicatePrefix() {
        Driver().use { d ->
            d.type("9aii4")
            assertTrue(d.choose(d.barWord("太"), CompositionSession.Origin.SPACE))
            val go = d.barWord("哥")
            d.failWrite = true
            assertTrue(d.choose(go, CompositionSession.Origin.BAR))
            assertEquals("太哥", d.session.completed()!!.text)
            assertTrue(d.commits.isEmpty())
            d.undo(); assertEquals("i4", d.state.input); assertEquals("太", d.session.selectedText)
            d.failWrite = false
            val replacement = d.panelSyllable("go")
            assertTrue(d.choose(replacement, CompositionSession.Origin.PANEL))
            assertEquals(listOf("太" + replacement.second.text), d.commits)
            assertFalse(d.choose(go, CompositionSession.Origin.BAR))
            assertEquals(1, d.commits.size)
        }
    }

    @Test(timeout = 90000) fun learnedPrefixAndIsolatedFallbackStayReversible() {
        Driver().use { d ->
            val code = "9aii48i5i73"
            d.type(code)
            repeat(4) { PhraseMemory.learnPick(code, "太哥", "tai go") }
            val snap = d.snapshot()
            val learned = snap.barItems.first { it.text == "太哥" }
            assertTrue(d.choose(snap to learned, CompositionSession.Origin.BAR))
            assertEquals("8i5i73", d.state.input)
            assertTrue(d.commits.isEmpty())
            d.undo(); assertEquals(code, d.state.input)
            d.session.cancel()
            d.type("55")
            val full = d.snapshot(true)
            assertTrue(full.groups.any { it.segment == ISOLATED_SEGMENT })
            val char = TwelveKeyPreedit.charsForSyllable("ka").first()
            val isolated = CandidateItem(char, "ka", -1, segment = ISOLATED_CHAR_SEGMENT)
            assertTrue(d.choose(full to isolated, CompositionSession.Origin.ISOLATED))
            assertEquals("5", d.state.input)
            assertTrue(d.commits.isEmpty())
            d.undo(); assertEquals("55", d.state.input)
        }
    }
}
