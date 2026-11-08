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
        Timber.i("toggleCandidatePanel: shown=%s", isCandidatePanelShown())
        if (isCandidatePanelShown()) hideCandidatePanel() else showCandidatePanel()
    }

    fun showCandidatePanel() {
        val panel = candidatePanel ?: return
        val root = candidatePanelRoot ?: return
        // 面板只展示「候選欄這一頁之外」的候選；收集後立刻把 Rime 還原到原頁，
        // 因此展開/滾動都不會改動候選欄（第一行）的內容。
        panelItems = collectCandidatePanelItems()
        if (panelItems.isEmpty()) {
            Timber.i("showCandidatePanel: no extra candidates")
            panelItems = currentPageItems() // 只有截斷、沒有下一頁時，退化成展示當前頁完整內容
        }
        candidatePanelAdapter?.submit(panelItems)
        inputArea?.layoutParams?.height = mainKeyboardView?.height
            ?: android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        inputArea?.requestLayout()
        mainKeyboardView?.visibility = View.GONE
        root.visibility = View.VISIBLE
        panel.scrollToPosition(0)
        Timber.i("showCandidatePanel: items=%s", panelItems.size)
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
        Timber.i("hideCandidatePanel")
        val root = candidatePanelRoot ?: return
        if (!isCandidatePanelShown()) return
        // 打開時已把 Rime 還原到原頁，這裡不需要再翻頁
        root.visibility = View.GONE
        mainKeyboardView?.visibility = View.VISIBLE
        inputArea?.layoutParams?.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        inputArea?.requestLayout()
        candidatePanelAdapter?.clear()
        trime.updateComposing()
    }

    private fun pickPanelCandidate(pageOffset: Int, indexInPage: Int) {
        // 面板展示的是「當前頁之後」的頁，提交時需要先翻到那一頁
        var delta = pageOffset
        while (delta > 0) {
            trime.handleKey(KeyEvent.KEYCODE_PAGE_DOWN, 0)
            if (!Rime.hasRight()) break
            delta--
        }
        if (Rime.selectCandidate(indexInPage)) {
            activeEditorInstance.commitRimeText()
        }
        trime.updateComposing()
        if (!Rime.isComposing()) {
            hideCandidatePanel()
        } else {
            // 組字還沒結束：回到第一頁並重新收集面板內容
            while (Rime.hasLeft()) {
                trime.handleKey(KeyEvent.KEYCODE_PAGE_UP, 0)
            }
            trime.updateComposing()
            panelItems = collectCandidatePanelItems()
            if (panelItems.isEmpty()) panelItems = currentPageItems()
            candidatePanelAdapter?.submit(panelItems)
            candidatePanel?.scrollToPosition(0)
        }
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
