package com.hazukieq.hkrime.ime.text

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.databinding.InputRootBinding
import com.hazukieq.hkrime.ime.broadcast.IntentReceiver
import com.hazukieq.hkrime.ime.core.EditorInstance
import com.hazukieq.hkrime.ime.core.Speech
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.ime.enums.Keycode
import com.hazukieq.hkrime.ime.enums.SymbolKeyboardType
import com.hazukieq.hkrime.ime.keyboard.Event
import com.hazukieq.hkrime.ime.keyboard.Keyboard.printModifierKeyState
import com.hazukieq.hkrime.ime.keyboard.KeyboardSwitcher
import com.hazukieq.hkrime.ime.keyboard.KeyboardView
import com.hazukieq.hkrime.util.ShortcutUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.util.startsWithAsciiChar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import timber.log.Timber
import java.util.Locale

/**
 * TextInputManager is responsible for managing everything which is related to text input. All of
 * the following count as text input: character, numeric (+advanced), phone and symbol layouts.
 *
 * All of the UI for the different keyboard layouts are kept under the same container element and
 * are separated from media-related UI. The core [Trime] will pass any event defined in
 * [Trime.EventListener] through to this class.
 *
 * TextInputManager is also the hub in the communication between the system, the active editor
 * instance and the CandidateView.
 */
class TextInputManager private constructor() :
    CoroutineScope by MainScope(),
    Trime.EventListener,
    KeyboardView.OnKeyboardActionListener,
    Candidate.EventListener {
    private val trime get() = Trime.getService()
    private val prefs get() = AppPrefs.defaultInstance()
    private val activeEditorInstance: EditorInstance
        get() = trime.activeEditorInstance
    private val keyboardSwitcher: KeyboardSwitcher
        get() = trime.keyboardSwitcher
    private val imeManager: InputMethodManager
        get() = trime.imeManager
    private var intentReceiver: IntentReceiver? = null

    private var mainKeyboardView: KeyboardView? = null
    private var inputArea: android.widget.FrameLayout? = null
    private var candidatePanel: RecyclerView? = null
    private var candidatePanelAdapter: CandidatePanelAdapter? = null
    private var candidatePanelRoot: android.view.View? = null
    private var candidateGroupList: RecyclerView? = null
    private var candidateGroupAdapter: CandidateGroupAdapter? = null
    /** 左側音節組：本次點擊選中的下標（-1 表示跟隨 caret） */
    private var activeGroupIndex = -1
    private var activeGroupStartByte = 0
    private var activeGroupText = ""
    private var lastGroupText: String? = null
    private var panelItems: MutableList<CandidatePanelAdapter.Item> = mutableListOf()
    /** 面板一次最多收集的頁數，防止極端情況下卡住 */
    private val panelMaxPages = 20
    var candidateRoot: ScrollView? = null
    var candidateView: Candidate? = null

    var locales: Array<Locale> = Array(2) { Locale.getDefault() }

    var needSendUpRimeKey: Boolean = false
    var shouldUpdateRimeOption: Boolean = true
    var performEnterAsLineBreak: Boolean = false
    var isComposable: Boolean = false
    var shouldResetAsciiMode: Boolean = false

    companion object {
        /** Delimiter regex for key property group, their format like `{property_1: value_1, property_2: value_2}` */
        private val DELIMITER_PROPERTY_GROUP = """^(\{[^{}]+\}).*$""".toRegex()
        /** Delimiter regex for property key tag, its format like `Escape: ` following a property group like above */
        private val DELIMITER_PROPERTY_KEY = """^((\{Escape\})?[^{}]+).*$""".toRegex()
        /** Delimiter regex to split language/locale tags. */
        private val DELIMITER_SPLITTER = """[-_]""".toRegex()
        private var instance: TextInputManager? = null

        @Synchronized
        fun getInstance(): TextInputManager {
            if (instance == null) {
                instance = TextInputManager()
            }
            return instance!!
        }
    }

    init {
        trime.addEventListener(this)
    }

    /**
     * Non-UI-related setup + preloading of all required computed layouts (asynchronous in the
     * background).
     */
    override fun onCreate() {
        super.onCreate()

        intentReceiver = IntentReceiver().also {
            it.registerReceiver(trime)
        }

        val imeConfig = Config.get(trime)
        var s =
            if (imeConfig.getString("locale").isNullOrEmpty()) {
                imeConfig.getString("locale")
            } else ""
        if (s.contains(DELIMITER_SPLITTER)) {
            val lc = s.split(DELIMITER_SPLITTER)
            if (lc.size == 3) {
                locales[0] = Locale(lc[0], lc[1], lc[2])
            } else {
                locales[0] = Locale(lc[0], lc[1])
            }
        } else {
            locales[0] = Locale.getDefault()
        }

        s = if (imeConfig.getString("latin_locale").isNullOrEmpty()) {
            imeConfig.getString("latin_locale")
        } else "en_US"
        if (s.contains(DELIMITER_SPLITTER)) {
            val lc = s.split(DELIMITER_SPLITTER)
            if (lc.size == 3) {
                locales[1] = Locale(lc[0], lc[1], lc[2])
            } else {
                locales[1] = Locale(lc[0], lc[1])
            }
        } else {
            locales[0] = Locale.ENGLISH
            locales[1] = Locale(s)
        }
        // preload all required parameters
        trime.loadConfig()
    }

    override fun onInitializeInputUi(uiBinding: InputRootBinding) {
        super.onInitializeInputUi(uiBinding)
        // Initialize main keyboard view
        mainKeyboardView = uiBinding.main.mainKeyboardView.also {
            it.setOnKeyboardActionListener(this)
            it.setShowHint(!Rime.getOption("_hide_key_hint"))
            it.setShowSymbol(!Rime.getOption("_hide_key_symbol"))
            it.reset(trime)
        }
        // Initialize candidate bar
        candidateRoot = uiBinding.main.candidateView.candidateRoot.also {
            it.setPageStr(
                Runnable { trime.handleKey(KeyEvent.KEYCODE_PAGE_DOWN, 0) },
                Runnable { trime.handleKey(KeyEvent.KEYCODE_PAGE_UP, 0) },
                Runnable { toggleCandidatePanel() }
            )
            it.visibility = if (Rime.getOption("_hide_candidate")) View.GONE else View.VISIBLE
        }

        candidateView = uiBinding.main.candidateView.candidates.also {
            it.setCandidateListener(this)
            it.setShowComment(!Rime.getOption("_hide_comment"))
            it.reset(trime)
        }

        // 候選展開面板（自繪網格，不復用 liquidKeyboard）
        inputArea = uiBinding.main.inputArea
        candidatePanelRoot = uiBinding.main.candidatePanelRoot
        runCatching {
            candidatePanelRoot?.setBackgroundColor(
                com.hazukieq.hkrime.data.Config.get(trime).getColor("keyboard_back_color") ?: 0xFFFFFFFF.toInt()
            )
        }
        candidateGroupList = uiBinding.main.candidateGroupList.also { list ->
            list.layoutManager = LinearLayoutManager(trime)
            candidateGroupAdapter = CandidateGroupAdapter(trime, onPick = { selectPinyinGroup(it) })
            list.adapter = candidateGroupAdapter
        }
        candidatePanel = uiBinding.main.candidatePanel.also { panel ->
            panel.layoutManager = GridLayoutManager(trime, 4)
            candidatePanelAdapter = CandidatePanelAdapter(
                trime,
                onPick = { pageOffset, indexInPage -> pickPanelCandidate(pageOffset, indexInPage) },
            )
            panel.adapter = candidatePanelAdapter
        }
    }

    // ---------- 候選展開面板 ----------

    fun isCandidatePanelShown(): Boolean = candidatePanelRoot?.visibility == android.view.View.VISIBLE

    /** 切換展開面板；關閉鍵盤由候選欄的 ⌄ 負責 */
    fun toggleCandidatePanel() {
        if (isCandidatePanelShown()) hideCandidatePanel() else showCandidatePanel()
    }

    fun showCandidatePanel() {
        val panel = candidatePanel ?: return
        val root = candidatePanelRoot ?: return
        // 沒有任何候選（空組字）就不要展開，避免出現一塊空白面板
        val current = Rime.getCandidatesWithoutSwitch()
        if (current.isNullOrEmpty() && !Rime.hasRight()) {
            return
        }
        // 面板只展示「候選欄這一頁之外」的候選；收集後立刻把 Rime 還原到原頁，
        // 因此展開/滾動都不會改動候選欄（第一行）的內容。
        panelItems = collectCandidatePanelItems()
        if (panelItems.isEmpty()) {
            panelItems = currentPageItems() // 只有截斷、沒有下一頁時，退化成展示當前頁完整內容
        }
        candidatePanelAdapter?.submit(panelItems)
        inputArea?.layoutParams?.height = mainKeyboardView?.height
            ?: android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        inputArea?.requestLayout()
        rebuildPinyinGroups()
        mainKeyboardView?.visibility = View.GONE
        root.visibility = View.VISIBLE
        panel.scrollToPosition(0)
    }

    /** 收集「當前頁之後」的所有候選；結束後用 pageUp 還原，候選欄狀態不變。 */
    private fun collectCandidatePanelItems(): MutableList<CandidatePanelAdapter.Item> {
        val items = mutableListOf<CandidatePanelAdapter.Item>()
        if (!Rime.hasRight()) return items
        var offset = 1
        var moved = 0
        while (Rime.hasRight() && offset <= panelMaxPages) {
            trime.handleKey(KeyEvent.KEYCODE_PAGE_DOWN, 0)
            moved++
            val cs = Rime.getCandidatesWithoutSwitch() ?: break
            for (i in cs.indices) {
                items.add(
                    CandidatePanelAdapter.Item(
                        cs[i].text ?: "", cs[i].comment ?: "", offset, i
                    )
                )
            }
            offset++
        }
        // 還原到第一頁（候選欄所顯示的頁），候選欄內容與 caret 都不受影響
        repeat(moved) { trime.handleKey(KeyEvent.KEYCODE_PAGE_UP, 0) }
        trime.updateComposing()
        return items
    }

    /** 當前頁的完整候選（候選欄被截斷時，面板用來展示被截斷的部分）。 */
    private fun currentPageItems(): MutableList<CandidatePanelAdapter.Item> {
        val items = mutableListOf<CandidatePanelAdapter.Item>()
        val cs = Rime.getCandidatesWithoutSwitch() ?: return items
        for (i in cs.indices) {
            items.add(CandidatePanelAdapter.Item(cs[i].text ?: "", cs[i].comment ?: "", 0, i))
        }
        return items
    }

    fun hideCandidatePanel() {
        val root = candidatePanelRoot ?: return
        if (!isCandidatePanelShown()) return
        // 打開時已把 Rime 還原到原頁，這裡不需要再翻頁
        root.visibility = View.GONE
        mainKeyboardView?.visibility = View.VISIBLE
        inputArea?.layoutParams?.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        inputArea?.requestLayout()
        candidatePanelAdapter?.clear()
        candidateGroupAdapter?.clear()
        trime.updateComposing()
    }

    private fun pickPanelCandidate(pageOffset: Int, indexInPage: Int) {
        if (pageOffset < 0) return
        // 面板只展示「當前頁」，index 直接對應 librime 的候選序號（不再預先翻頁收集）
        Rime.selectCandidate(indexInPage)
        activeEditorInstance.commitRimeText()
        trime.updateComposing()
        if (!Rime.isComposing()) {
            hideCandidatePanel()
            return
        }
        // 等這一幀的組字狀態落定後再刷新左列/面板（否則拿到還是上一輪的 preedit）
        candidatePanel?.postDelayed({
            Rime.getContexts()
            trime.updateComposing()
            activeGroupIndex = -1
            lastGroupText = ""
            rebuildPinyinGroups()
            panelItems = currentPageItems()
            candidatePanelAdapter?.submit(panelItems)
            candidatePanel?.scrollToPosition(0)
        }, 80)
    }

    /** 除錯：逐個 caret 位置印出 librime 的分段/候選，用於確認引擎切割行為 */
    fun debugCaretScan() {
        val text = Rime.getCompositionText() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        val saved = Rime.RimeGetCaretPos()
        for (pos in 0..bytes.size) {
            val ok = Rime.RimeSetCaretPos(pos)
            Rime.getContexts()
            val c = Rime.getComposition()
            val cs = Rime.getCandidatesWithoutSwitch()
            val head = cs.take(3).joinToString("|") { (it.text ?: "") + (it.comment ?: "") }
            Timber.i(
                "caretScan pos=%s ok=%s nowCaret=%s sel=%s-%s cands=%s",
                pos, ok, Rime.RimeGetCaretPos(), c.getSelStart(), c.getSelEnd(), head
            )
        }
        Rime.RimeSetCaretPos(saved)
        Rime.getContexts()
    }

    /** 除錯：用 Rime 自己的左移鍵移動 caret，看它把光標按音節跳到哪些位置 */
    fun debugCaretProbe() {
        val text = Rime.getCompositionText() ?: return
        val len = text.toByteArray(Charsets.UTF_8).size
        val saved = Rime.RimeGetCaretPos()
        Rime.RimeSetCaretPos(len)
        Rime.getContexts()
        Timber.i("caretProbe start preedit=[%s] len=%s caret=%s", text, len, Rime.RimeGetCaretPos())
        repeat(len + 2) {
            val ok = Rime.process_key(0xff51, 0) // XK_Left
            Rime.getContexts()
            val c = Rime.getComposition()
            val cs = Rime.getCandidatesWithoutSwitch()
            val head = cs.take(2).joinToString("|") { (it.text ?: "") }
            Timber.i(
                "caretProbe segsAfterLeft=%s",
                Rime.getRimeCompositionSegments().joinToString(",")
            )
            Timber.i(
                "caretProbe left ok=%s caret=%s sel=%s-%s cands=%s",
                ok, Rime.RimeGetCaretPos(), c.getSelStart(), c.getSelEnd(), head
            )
        }
        Rime.RimeSetCaretPos(saved)
        Rime.getContexts()
    }

    /** 候選刷新後呼叫：組字結束（無候選）時自動收起展開面板，避免留下空白面板 */
    fun onCandidatesRefreshed() {
        if (!isCandidatePanelShown()) return
        if (Rime.getCandidatesWithoutSwitch().isNullOrEmpty()) {
            hideCandidatePanel()
            return
        }
        // 組字內容變了（例如剛轉換掉一段）：左側音節組與面板一起跟著更新
        lastGroupText = ""
        activeGroupIndex = -1
        rebuildPinyinGroups()
        panelItems = currentPageItems()
        candidatePanelAdapter?.submit(panelItems)
    }

    // ---------- 左側拼音音節組（方案 2：自切音節，僅顯示/導航） ----------

    private fun isCjk(c: Char): Boolean =
        c.code in 0x3400..0x9FFF || c.code in 0xF900..0xFAFF

    private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    private fun buildGroupsFromSegments(
        bytes: ByteArray,
        segs: IntArray,
    ): List<Triple<String, Int, Boolean>> {
        val out = ArrayList<Triple<String, Int, Boolean>>()
        var i = 0
        while (i + 2 < segs.size) {
            val startByte = segs[i]
            val endByte = segs[i + 1]
            val status = segs[i + 2]
            i += 3
            if (startByte < 0 || endByte <= startByte || endByte > bytes.size) continue
            val segText = String(bytes, startByte, endByte - startByte, Charsets.UTF_8)
                .trim().trim('\'', ' ')
            if (segText.isBlank()) continue
            val converted = status >= 2
            out.add(Triple(segText, startByte, converted))
        }
        return out
    }

    /** 由 preedit 重建左側音節組：邊界由 librime 的光標切分（XK_Left）取得。 */
    private fun rebuildPinyinGroups() {
        val adapter = candidateGroupAdapter ?: return
        val raw = Rime.getCompositionText() ?: run { adapter.clear(); return }
        if (raw.isEmpty()) {
            adapter.clear()
            candidateGroupList?.visibility = View.GONE
            return
        }
        // 已經轉換過的段（status >= kSelected）不再顯示在左列；左列只留「還沒處理的音節」。
        // 有轉換段時直接讀 librime 的原生分段（不按光標、避免把已轉換的段重新打開）。
        val nativeSegs = Rime.getRimeCompositionSegments()
        val nativeGroups = ArrayList<Triple<String, Int, Boolean>>()
        if (nativeSegs.size >= 3) {
            val inputBytes = (Rime.RimeGetInput() ?: "").toByteArray(Charsets.UTF_8)
            var converted = false
            for (i in 0 until nativeSegs.size / 3) {
                val st = nativeSegs[i * 3]
                val en = nativeSegs[i * 3 + 1]
                val status = nativeSegs[i * 3 + 2]
                if (en <= st || st >= inputBytes.size) continue
                val end = minOf(en, inputBytes.size)
                val text = String(inputBytes, st, end - st, Charsets.UTF_8)
                    .replace(" ", "").replace("'", "").trim()
                if (text.isEmpty()) continue
                if (status >= 2) { converted = true; continue }
                nativeGroups.add(Triple(text, st, false))
            }
            if (!converted) nativeGroups.clear()
        }
        val groups = if (nativeGroups.isNotEmpty()) {
            cachedGroups = nativeGroups
            lastGroupText = raw
            nativeGroups
        } else if (raw == lastGroupText && cachedGroups.isNotEmpty()) {
            cachedGroups
        } else {
            probeGroups(raw).also {
                cachedGroups = it
                lastGroupText = raw
                activeGroupIndex = -1
            }
        }
        if (groups.isEmpty()) {
            adapter.clear()
            candidateGroupList?.visibility = View.GONE
            return
        }
        val caretNow = Rime.RimeGetCaretPos()
        // 光標分段有時會退化成一字一段（例如剛 commit 完重打剩下的音節）→ 用字典音節表兜底
        val degenerate = groups.isNotEmpty() && groups.all { it.first.length <= 1 } && groups.size > 1
        val finalGroups = if (degenerate) {
            SyllableLexicon.split(trime, Rime.RimeGetInput()).map { (t, b) -> Triple(t, b, false) }
        } else groups
        val items = ArrayList<CandidateGroupAdapter.PinyinGroupAdapterItem>()
        var nextIndex = 0
        for ((segText, startByte, converted) in finalGroups) {
            val selected = if (activeGroupIndex >= 0) {
                activeGroupIndex == nextIndex
            } else {
                caretNow > startByte && caretNow <= startByte + utf8Len(segText)
            }
            items.add(
                CandidateGroupAdapter.PinyinGroupAdapterItem(
                    nextIndex++, segText, startByte, converted = converted,
                    incomplete = false, selected = selected
                )
            )
        }
        adapter.submit(items)
        candidateGroupList?.visibility = View.VISIBLE
    }

    private var cachedGroups: List<Triple<String, Int, Boolean>> = emptyList()

    /**
     * 用 librime 自己的光標切分拿邊界：把 caret 放到末尾，反覆按 XK_Left，
     * 它會按音節往左跳；跳到的位置就是音節邊界。探測完把 caret 還原。
     *
     * 注意：preedit 裡可能帶 caret 佔位符（"‸"），這裡直接在 preedit 的字節上切片，
     * 所以不需要做任何偏移補償，最後再把佔位符從組文本裡去掉。
     */
    private fun probeGroups(raw: String): List<Triple<String, Int, Boolean>> {
        val savedCaret = Rime.RimeGetCaretPos()
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val boundSet = sortedSetOf<Int>(bytes.size)
        Rime.RimeSetCaretPos(bytes.size)
        Rime.getContexts()
        var guard = 0
        var lastPos = Int.MAX_VALUE
        while (guard++ < 64) {
            Rime.process_key(0xff51, 0) // XK_Left
            Rime.getContexts()
            val c = Rime.RimeGetCaretPos()
            if (c <= 0) { boundSet.add(0); break }
            if (c >= lastPos) break
            boundSet.add(c)
            lastPos = c
            if (boundSet.size > 32) break
        }
        Rime.RimeSetCaretPos(savedCaret)
        Rime.getContexts()
        if (boundSet.isEmpty()) return emptyList()
        if (boundSet.first() != 0) boundSet.add(0)
        boundSet.add(bytes.size)

        val out = ArrayList<Triple<String, Int, Boolean>>()
        val bounds = boundSet.toList()
        for (i in 0 until bounds.size - 1) {
            var sb = bounds[i]
            var eb = bounds[i + 1]
            while (sb < eb && (bytes[sb] == ' '.code.toByte() || bytes[sb] == '\''.code.toByte())) sb++
            while (eb > sb && (bytes[eb - 1] == ' '.code.toByte() || bytes[eb - 1] == '\''.code.toByte())) eb--
            if (eb <= sb) continue
            var segText = String(bytes, sb, eb - sb, Charsets.UTF_8)
                .replace("\u2038", "").replace("\u2040", "").trim()
            if (segText.isBlank()) continue
            val pureLatin = segText.all { it in 'a'..'z' || it == '\'' }
            val pureCjk = segText.all { isCjk(it) }
            if (!pureLatin && !pureCjk) continue
            out.add(Triple(segText, sb, pureCjk))
        }
        return out
    }

    /**
     * 點擊左側音節組：**只更新選中態，不動 caret**。
     *
     * 光標只在右側挑中漢字（真正上屏/轉換一段）時才由 librime 自己消費。
     */
    /**
     * 點擊左側音節組：用「把 caret 移到該音節起點」的方式讓 librime 給這一節的候選
     * （不寫入、不轉換任何字）；右側候選欄/展開面板立即切成這一節的候選。
     * 只有用戶在右側挑字時才真正消費/轉換。
     */
    private fun selectPinyinGroup(group: CandidateGroupAdapter.PinyinGroupAdapterItem) {
        activeGroupIndex = group.index
        // 把 caret 移到「這一節的末尾」（= 下一節的起點），librime 就會把候選切成這一節的
        val endByte = group.startByte + group.text.toByteArray(Charsets.UTF_8).size
        moveCaretTo(endByte)
        Rime.getContexts()
        trime.updateComposing()
        rebuildPinyinGroups()
        // 這一節的候選就是當前頁：不要再做 pageDown 收集，否則 selectCandidate 的 index 會錯
        panelItems = currentPageItems()
        candidatePanelAdapter?.submit(panelItems)
        candidatePanel?.scrollToPosition(0)
    }

    /**
     * 用 librime 自己的左/右光標鍵把 caret 移到指定字節位置。
     * 只調 set_caret_pos 不會刷新候選，必須走 processKey(XK_Left/Right)。
     */
    private fun moveCaretTo(targetByte: Int) {
        var caret = Rime.RimeGetCaretPos()
        var guard = 0
        while (caret > targetByte && guard++ < 64) {
            if (!Rime.process_key(0xff51 /* XK_Left */, 0)) break
            val next = Rime.RimeGetCaretPos()
            if (next == caret) break
            caret = next
        }
        guard = 0
        while (caret < targetByte && guard++ < 64) {
            if (!Rime.process_key(0xff53 /* XK_Right */, 0)) break
            val next = Rime.RimeGetCaretPos()
            if (next == caret) break
            caret = next
        }
    }

    /**
     * 從「字典快速查找」清單裡挑一個字：只有到這一步才動 librime。
     * 先看當前候選頁有沒有它；沒有就把 caret 移到該音節再找一次；仍沒有就提示。
     */
    /** 重新收集展開面板內容（保持「面板不影響候選欄」的約定）。 */
    private fun refreshPanelItems() {
        val panel = candidatePanel ?: return
        if (!isCandidatePanelShown()) return
        panelItems = collectCandidatePanelItems()
        if (panelItems.isEmpty()) panelItems = currentPageItems()
        candidatePanelAdapter?.submit(panelItems)
        panel.scrollToPosition(0)
    }

    /**
     * Cancels all coroutines and cleans up.
     */
    override fun onDestroy() {
        intentReceiver?.unregisterReceiver(trime)
        intentReceiver = null

        candidateView?.setCandidateListener(null)
        candidateView = null

        candidateRoot = null

        mainKeyboardView?.setOnKeyboardActionListener(null)
        mainKeyboardView = null

        cancel()
        instance = null
    }

    override fun onStartInputView(instance: EditorInstance, restarting: Boolean) {
        super.onStartInputView(instance, restarting)
        Trime.getService().selectLiquidKeyboard(-1)
        panelItems.clear()
        candidatePanelRoot?.visibility = android.view.View.GONE
        isComposable = false
        performEnterAsLineBreak = false
        var tempAsciiMode = if (shouldResetAsciiMode) false else null
        var keyboardType =
            when (instance.editorInfo!!.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> {
                    tempAsciiMode = true
                    ".ascii"
                }
                else -> {
                    val inputAttrsRaw = instance.editorInfo!!.inputType
                    when (inputAttrsRaw and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME -> {
                            "number"
                        }
                        InputType.TYPE_CLASS_TEXT -> {
                            when (inputAttrsRaw and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> {
                                    null.also { performEnterAsLineBreak = true }
                                }
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> {
                                    Timber.i(
                                        "EditorInfo: " +
                                            " inputAttrsRaw" + inputAttrsRaw +
                                            "; InputType" + (inputAttrsRaw and InputType.TYPE_MASK_VARIATION)
                                    )

                                    tempAsciiMode = true
                                    ".ascii"
                                }
                                else -> null.also { isComposable = true }
                            }
                        }
                        else -> {
                            if (inputAttrsRaw <= 0) return
                            null.also { isComposable = inputAttrsRaw > 0 }
                        }
                    }
                }
            }

        keyboardSwitcher.let {
            it.resize(trime.maxWidth)
            // Select a keyboard based on the input type of the editing field.
            it.startKeyboard(keyboardType)
        }
        Rime.get(trime)

        // style/reset_ascii_mode指定了弹出键盘时是否重置ASCII状态。
        // 键盘的reset_ascii_mode指定了重置时是否重置到keyboard的ascii_mode描述的状态。
        if (shouldResetAsciiMode && keyboardSwitcher.currentKeyboard.isResetAsciiMode) {
            tempAsciiMode = keyboardSwitcher.currentKeyboard.asciiMode
        }
        tempAsciiMode?.let { Rime.setOption("ascii_mode", it) }
        isComposable = isComposable && !Rime.isEmpty()
        if (!trime.onEvaluateInputViewShown()) {
            // Show candidate view when using physical keyboard
            trime.setCandidatesViewShown(isComposable)
        }
    }

    fun onOptionChanged(option: String, value: Boolean) {
        when (option) {
            "ascii_mode" -> {
                trime.inputFeedbackManager.ttsLanguage =
                    locales[if (value) 1 else 0]
            }
            "_hide_comment" -> trime.setShowComment(!value)
            "_hide_candidate" -> {
                candidateRoot?.visibility = if (!value) View.VISIBLE else View.GONE
                trime.setCandidatesViewShown(isComposable && !value)
            }
            "_liquid_keyboard" -> trime.selectLiquidKeyboard(0)
            "_hide_key_hint" -> if (mainKeyboardView != null) mainKeyboardView!!.setShowHint(!value)
            "_hide_key_symbol" -> if (mainKeyboardView != null) mainKeyboardView!!.setShowSymbol(!value)
            else -> if (option.startsWith("_keyboard_") &&
                option.length > 10 && value
            ) {
                val keyboard = option.substring(10)
                keyboardSwitcher.switchToKeyboard(keyboard)
                trime.bindKeyboardToInputView()
            } else if (option.startsWith("_key_") && option.length > 5 && value) {
                shouldUpdateRimeOption = false // 防止在 handleRimeNotification 中 setOption
                val key = option.substring(5)
                onEvent(Event(key))
                shouldUpdateRimeOption = true
            } else if (option.startsWith("_one_hand_mode")) {
                /*
                val c = option[option.length - 1]
                if (c == '1' && value) oneHandMode = 1 else if (c == '2' && value) oneHandMode =
                    2 else if (c == '3') oneHandMode = if (value) 1 else 2 else oneHandMode = 0
                trime.loadBackground()
                trime.initKeyboard() */
            }
        }
        mainKeyboardView?.invalidateAllKeys()
    }

    override fun onPress(keyEventCode: Int) {
        trime.inputFeedbackManager?.let {
            it.keyPressVibrate()
            it.keyPressSound(keyEventCode)
            it.keyPressSpeak(keyEventCode)
        }
    }

    override fun onRelease(keyEventCode: Int) {
        Timber.d(
            "\t<TrimeInput>\tonRelease() needSendUpRimeKey=" + needSendUpRimeKey + ", keyEventcode=" + keyEventCode +
                ", Event.getRimeEvent=" + Event.getRimeEvent(keyEventCode, Rime.META_RELEASE_ON)
        )
        if (needSendUpRimeKey) {
            if (shouldUpdateRimeOption) {
                Rime.setOption("soft_cursors", prefs.keyboard.softCursorEnabled)
                Rime.setOption("_horizontal", trime.imeConfig.getBoolean("horizontal"))
                shouldUpdateRimeOption = false
            }
            // todo 释放按键可能不对
            Rime.onKey(Event.getRimeEvent(keyEventCode, Rime.META_RELEASE_ON))
            activeEditorInstance.commitRimeText()
        }
        Timber.d("\t<TrimeInput>\tonRelease() finish")
    }

    // KeyboardEvent 处理软键盘事件
    override fun onEvent(event: Event?) {
        event ?: return
        if (!event.commit.isNullOrEmpty()) {
            // Directly commit the text and don't dispatch to Rime
            activeEditorInstance.commitText(event.commit, false)
            return
        }
        if (!event.text.isNullOrEmpty()) {
            onText(event.text)
            return
        }
        when (event.code) {
            KeyEvent.KEYCODE_SWITCH_CHARSET -> { // Switch status
                Rime.toggleOption(event.toggle)
                activeEditorInstance.commitRimeText()
            }
            KeyEvent.KEYCODE_EISU -> { // Switch keyboard
                keyboardSwitcher.switchToKeyboard(event.select)
                /** Set ascii mode according to keyboard's settings, can not place into [Rime.handleRimeNotification] */
                Rime.setOption("ascii_mode", keyboardSwitcher.asciiMode)
                trime.bindKeyboardToInputView()
                trime.updateComposing()
            }
            KeyEvent.KEYCODE_LANGUAGE_SWITCH -> { // Switch IME
                when {
                    event.select!!.contentEquals(".next") -> {
                        trime.switchToNextIme()
                    }
                    event.select.isNotEmpty() -> {
                        trime.switchToPrevIme()
                    }
                    else -> {
                        imeManager.showInputMethodPicker()
                    }
                }
            }
            KeyEvent.KEYCODE_FUNCTION -> { // Command Express
                // Comments from trime.yaml:
                // %s或者%1$s爲當前字符
                // %2$s爲當前輸入的編碼
                // %3$s爲光標前字符
                // %4$s爲光標前所有字符
                val arg = String.format(
                    event.option,
                    activeEditorInstance.lastCommittedText,
                    Rime.RimeGetInput(),
                    activeEditorInstance.getTextBeforeCursor(1),
                    activeEditorInstance.getTextBeforeCursor(1024)
                )
                if (event.command == "liquid_keyboard") {
                    trime.selectLiquidKeyboard(arg)
                } else if (event.command == "paste_by_char") {
                    trime.pasteByChar()
                } else {
                    val textFromCommand = ShortcutUtils
                        .call(trime, event.command, arg)
                    if (textFromCommand != null) {
                        activeEditorInstance.commitText(textFromCommand)
                        trime.updateComposing()
                    }
                }
            }
            KeyEvent.KEYCODE_VOICE_ASSIST -> Speech(trime).startListening() // Speech Recognition
            KeyEvent.KEYCODE_SETTINGS -> { // Settings
                when (event.option) {
                    "theme" -> trime.showThemeDialog()
                    "color" -> trime.showColorDialog()
                    "schema" -> trime.showSchemaDialog()
                    "sound" -> trime.showSoundDialog()
                    else -> trime.launchSettings()
                }
            }
            KeyEvent.KEYCODE_PROG_RED -> trime.showColorDialog() // Color schemes
            KeyEvent.KEYCODE_MENU -> trime.showOptionsDialog()
            else -> onKey(event.code, event.mask or trime.keyboardSwitcher.currentKeyboard.modifer)
        }
    }

    override fun onKey(keyEventCode: Int, metaState: Int) {
        printModifierKeyState(metaState, "keyEventCode=" + keyEventCode)
        if (trime.handleKey(keyEventCode, metaState)) return
        if (Keycode.hasSymbolLabel(keyEventCode)) {
            needSendUpRimeKey = false
            activeEditorInstance.commitText(Keycode.getSymbolLabell(Keycode.valueOf(keyEventCode)))
            return
        }
        needSendUpRimeKey = false
        activeEditorInstance.sendDownUpKeyEvent(keyEventCode, metaState)
    }

    override fun onText(text: CharSequence?) {
        text ?: return
        if (!text.startsWithAsciiChar() && Rime.isComposing()) {
            Rime.commitComposition()
            activeEditorInstance.commitRimeText()
        }
        var textToParse = text
        while (textToParse!!.isNotEmpty()) {
            var target: String
            val escapeTagMatcher = DELIMITER_PROPERTY_KEY.toPattern().matcher(textToParse)
            val propertyGroupMatcher = DELIMITER_PROPERTY_GROUP.toPattern().matcher(textToParse)
            when {
                escapeTagMatcher.matches() -> {
                    target = escapeTagMatcher.group(1) ?: ""
                    Rime.onText(target)
                    if (!activeEditorInstance.commitRimeText() && !Rime.isComposing()) {
                        activeEditorInstance.commitText(target)
                    }
                    trime.updateComposing()
                }
                propertyGroupMatcher.matches() -> {
                    target = propertyGroupMatcher.group(1) ?: ""
                    onEvent(Event(target))
                }
                else -> {
                    target = textToParse.substring(0, 1)
                    onEvent(Event(target))
                }
            }
            textToParse = textToParse.substring(target.length)
        }
        needSendUpRimeKey = false
    }

    /**
     * Commits the pressed candidate and suggest the following words.
     */
    override fun onCandidatePressed(index: Int) {
        onPress(0)
        if (!Rime.isComposing()) {
            if (index >= 0) {
                Rime.toggleOption(index)
                trime.updateComposing()
            }
        } else if (prefs.keyboard.hookCandidate || index > 9) {
            if (Rime.selectCandidate(index)) {
                if (prefs.keyboard.hookCandidateCommit) {
                    // todo 找到切换高亮候选词的API，并把此处改为模拟移动候选后发送空格
                    // 如果使用了lua处理候选上屏，模拟数字键、空格键是非常有必要的
                    activeEditorInstance.commitRimeText()
                } else
                    activeEditorInstance.commitRimeText()
            }
        } else if (index == 9) {
            trime.handleKey(KeyEvent.KEYCODE_0, 0)
        } else {
            trime.handleKey(KeyEvent.KEYCODE_1 + index, 0)
        }
    }

    override fun onCandidateSymbolPressed(arrow: String) {
        when (arrow) {
            Candidate.PAGE_UP_BUTTON -> onKey(KeyEvent.KEYCODE_PAGE_UP, 0)
            Candidate.PAGE_DOWN_BUTTON -> onKey(KeyEvent.KEYCODE_PAGE_DOWN, 0)
            Candidate.PAGE_EX_BUTTON -> toggleCandidatePanel()
            // 候選為空時，右側按鈕的行為是收起鍵盤
            Candidate.HIDE_KEYBOARD_BUTTON -> Trime.getService().requestHideSelf(0)
        }
    }

    override fun onCandidateLongClicked(index: Int) {
        Rime.deleteCandidate(index)
        trime.updateComposing()
    }
}
