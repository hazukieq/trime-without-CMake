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
                val parts = line.split('\t', limit = 5)
                when (parts[0]) {
                    "I" -> next.input = parts.getOrElse(1) { "" }
                    "P" -> next.preedit = parts.getOrElse(1) { "" }
                    "K" -> next.commit = parts.getOrElse(1) { "" }
                    "S" -> next.segments.add(line)
                    "D" -> next.candidates.add(CandidateItem(parts[2],
                        TwelveKeySnapshotBuilder.cleanCommentForSelection(parts.getOrNull(3)), parts[1].toInt()))
                    "M" -> {
                        val index = parts[1].toInt()
                        val position = next.candidates.indexOfFirst { it.index == index }
                        next.candidates[position] = next.candidates[position].copy(engineSpan =
                            EngineCandidateSpan(parts[2].toInt(), parts[3].toInt(), parts.getOrElse(4) { "" }))
                    }
                }
            }
        }
        fun type(code: String) { send("clear"); send("type $code"); session.input(state.input) }
        private fun local() = TwelveKeySnapshotBuilder.Local(
            state.input, 0, state.input, state.segments.joinToString("|"), state.preedit,
        )
        fun snapshot(full: Boolean = false, learning: Boolean = true, appRankingEnabled: Boolean = true): TwelveKeySnapshot = TwelveKeySnapshotBuilder.build(
            local(), maxCandidates = if (full) 600 else 64, applyLearning = learning, wantGroups = full,
            candidatesOverride = state.candidates, initial = session.initial,
            appRankingEnabled = appRankingEnabled, phonetics = session.phonetics,
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
            if (!session.pick(item.text, item.inputSpan!!.reading, consumed, origin)) return false
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
        fun restore(code: String) {
            // 屬性在重建菜單前寫入（翻譯器建菜單時讀它決定是否保留簡拼分支）。
            send("initial " + PhoneticSelection.constraint(session.phonetics))
            send("clear"); if (code.isNotEmpty()) send("type $code")
        }
        fun confirmLetter(letter: Char) {
            check(session.onInitialLetter(letter))
            send("initial " + PhoneticSelection.constraint(session.phonetics))
            send("clear")
            if (session.remaining.isNotEmpty()) send("type " + session.remaining)
            assertEquals(session.remaining, state.input)
            assertTrue(state.commit.isEmpty())
        }
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

    @Test(timeout = 90000) fun engineModeCanBeSwitchedWithoutLosingPartialSelectionOrUndo() {
        Driver().use { d ->
            val original = "7i4aa4"
            d.type(original)
            repeat(9) { PhraseMemory.learnPick(original, "品班", "pin ban") }
            repeat(9) { SelectionMemory.bump("肾", "sin") }
            val native = d.state.candidates.toList()
            val off = d.snapshot(appRankingEnabled = false)
            val full = d.snapshot(true, appRankingEnabled = false)
            assertEquals(native.take(12).map { it.index }, off.barItems.map { it.index })
            assertEquals(off.barItems, full.barItems)
            assertEquals(native.map { it.index }, full.recommend.map { it.index })
            assertTrue(full.groups.flatMap { it.items }.all { it.index >= 0 })
            d.confirmLetter('s')
            assertTrue(d.state.candidates.isNotEmpty())
            assertTrue(d.state.candidates.all { it.engineSpan!!.sourceComment.startsWith('s') })
            val confirmedBar = d.snapshot(appRankingEnabled = false).barItems
            assertTrue(confirmedBar.isNotEmpty())
            assertTrue("確認後仍用引擎全局下標", confirmedBar.all { it.index >= 0 })
            assertEquals(confirmedBar, d.snapshot(true, appRankingEnabled = false).barItems)
            d.undo()
            val restored = d.snapshot(true, appRankingEnabled = false)
            val partial = restored.recommend.first { it.text == "肾" }
            assertTrue(d.choose(restored to partial, CompositionSession.Origin.PANEL))
            assertEquals("aa4", d.state.input)
            assertEquals("肾", d.session.selectedText)
            val picks = d.session.picks
            d.snapshot(true)
            d.snapshot(appRankingEnabled = false)
            assertEquals(picks, d.session.picks)
            assertEquals("aa4", d.session.remaining)
            assertTrue(d.commits.isEmpty())
            d.undo()
            assertEquals(original, d.state.input)
            val final = d.snapshot(appRankingEnabled = false)
            assertTrue(d.choose(final to final.barItems.first(), CompositionSession.Origin.BAR))
            assertEquals(listOf(native.first().text), d.commits)
        }
    }

    @Test(timeout = 90000) fun matchingWordRemainsFirstDespiteLearningOfPartialCharacters() {
        Driver().use { d ->
            d.type("7i4aa4")
            assertEquals("平板", d.state.candidates.first().text)
            repeat(9) { SelectionMemory.bump("肾", "sin") }
            val bar = d.snapshot()
            val full = d.snapshot(true)
            assertEquals("平板", bar.barItems.first().text)
            assertEquals("平板", bar.previewText)
            assertEquals(bar.barItems, full.barItems)
            assertEquals("pin ban", PreeditPresentation.render(d.state.preedit, d.state.input))
            val characters = bar.barItems.filter { it.text.length == 1 }
            assertTrue("segment characters remain selectable", characters.isNotEmpty())
            val character = characters.first { it.text == "肾" }
            assertTrue(d.choose(bar to character, CompositionSession.Origin.BAR))
            assertEquals("aa4", d.state.input)
            assertTrue(d.commits.isEmpty())
            d.undo()
            assertEquals("7i4aa4", d.state.input)
            val restored = d.snapshot()
            assertEquals("平板", restored.barItems.first().text)
            assertEquals("pin ban", PreeditPresentation.render(d.state.preedit, d.state.input))
            assertTrue(d.choose(restored to restored.barItems.first(), CompositionSession.Origin.BAR))
            assertEquals(listOf("平板"), d.commits)
        }
    }

    @Test(timeout = 90000) fun barPanelTransitionsAndEveryUndoKeepAllCodes() {
        Driver().use { d ->
            val original = "9aii48i5i73"
            d.type(original)
            val old = d.barWord("太")
            assertTrue(d.choose(old.first to old.second.copy(comment = "display-only unrelated text"),
                CompositionSession.Origin.BAR))
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

    @Test(timeout = 90000) fun letterConfirmUsesRealMenuAndFirstSegmentCharConsumesOnlyItsSpan() {
        Driver().use { d ->
            val original = "5i5i"
            d.type(original)
            val originalItems = d.state.candidates.toList()
            d.confirmLetter('l')
            assertTrue(d.state.candidates.isNotEmpty())
            assertTrue(d.state.candidates.all { it.engineSpan!!.sourceComment.startsWith('l') })
            assertEquals("5i5i", d.state.input)
            assertTrue(d.commits.isEmpty())
            val snap = d.snapshot(true)
            val firstChar = snap.groups.flatMap { it.items }.first { it.segment == INITIAL_CHAR_SEGMENT }
            assertEquals(1, firstChar.inputSpan?.end)
            assertTrue(d.choose(snap to firstChar, CompositionSession.Origin.PANEL))
            assertEquals("i5i", d.state.input)
            assertEquals(emptyList<PhoneticSelection>(), d.session.phonetics)
            assertTrue(d.commits.isEmpty())
            d.undo()
            assertEquals(original, d.state.input)
            assertEquals('l', d.session.initial)
            d.undo()
            assertNull(d.session.initial)
            assertEquals(originalItems, d.state.candidates)
            assertTrue(d.commits.isEmpty())
        }
    }

    @Test(timeout = 90000) fun actualFilteredNativeGlobalIndexSelectsTheDisplayedWordExactlyOnce() {
        Driver().use { d ->
            d.type("5i")
            d.confirmLetter('l')
            val snap = d.snapshot()
            val selected = snap.barItems.first { it.index > 0 && it.engineSpan != null }
            assertTrue(selected.engineSpan!!.sourceComment.startsWith('l'))
            assertTrue(d.choose(snap to selected, CompositionSession.Origin.BAR))
            assertEquals(listOf(selected.text), d.commits)
            assertFalse(d.session.active)
            d.writeCompleted()
            assertEquals(1, d.commits.size)
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
            val isolated = CandidateSelection.bind(CandidateItem(char, "ka", -1, segment = ISOLATED_CHAR_SEGMENT), "55")
            assertTrue(d.choose(full to isolated, CompositionSession.Origin.ISOLATED))
            assertEquals("5", d.state.input)
            assertTrue(d.commits.isEmpty())
            d.undo(); assertEquals("55", d.state.input)
        }
    }

    @Test(timeout = 90000) fun actualOpenccEmoticonsConsumeNativeRangesAndUndoAcrossSurfaces() {
        Driver().use { d ->
            d.send("option ywz 1")
            val code = "ia8i3"
            d.type(code + "9ai")
            val converted = d.state.candidates.filter { it.comment.contains("加油") && it.text != "加油" }
            assertTrue("真實 OpenCC 必須產生加油表情", converted.size >= 2)
            for ((n, emoji) in converted.take(2).withIndex()) {
                val full = d.snapshot(true)
                val row = full.groups.flatMap { it.items }.first { it.text == emoji.text }
                val span = requireNotNull(row.inputSpan)
                assertEquals(5, span.end)
                assertEquals("ga jiu", span.reading)
                assertTrue(d.choose(full to row, if (n == 0) CompositionSession.Origin.PANEL else CompositionSession.Origin.BAR))
                assertEquals("9ai", d.state.input)
                assertEquals(emoji.text, d.session.selectedText)
                assertTrue(d.commits.isEmpty())
                d.undo()
                assertEquals(code + "9ai", d.state.input)
            }
            d.type(code)
            val full = d.snapshot(true)
            val emoji = full.groups.flatMap { it.items }.first { it.comment.contains("加油") && it.text != "加油" }
            assertTrue(d.choose(full to emoji, CompositionSession.Origin.PANEL))
            assertEquals(listOf(emoji.text), d.commits)
            d.writeCompleted()
            assertEquals(1, d.commits.size)
        }
    }
}
