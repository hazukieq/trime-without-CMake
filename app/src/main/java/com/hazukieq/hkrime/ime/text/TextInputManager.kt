package com.hazukieq.hkrime.ime.text

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.twelvekey.CandidateBarBinder
import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.Fallback
import com.hazukieq.hkrime.core.twelvekey.CandidatePanelController
import com.hazukieq.hkrime.core.twelvekey.TwelveKeyController
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshot
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
    private var candidatePanelRoot: android.view.View? = null

    /** 12 鍵：一次按鍵 = 一個不可變快照，候選條與面板都只讀它。 */
    private var twelveKeyController: TwelveKeyController? = null
    private var candidatePanelController: CandidatePanelController? = null
    private var candidateBarBinder: CandidateBarBinder? = null


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
        /** 非 12 鍵方案展開面板一次枚舉的候選上限（與 12 鍵快照一致）。 */
        private const val PANEL_ENUM_MAX = 600
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

        // 候選展開面板（只讀快照；12 鍵走 Snapshot，其它方案走舊的分段左列）
        inputArea = uiBinding.main.inputArea
        candidatePanelRoot = uiBinding.main.candidatePanelRoot
        runCatching {
            candidatePanelRoot?.setBackgroundColor(
                com.hazukieq.hkrime.data.Config.get(trime).getColor("keyboard_back_color") ?: 0xFFFFFFFF.toInt()
            )
        }
        candidatePanelController = CandidatePanelController(
            context = trime,
            keyboardView = mainKeyboardView,
            inputArea = inputArea,
            panelRoot = candidatePanelRoot,
            groupListView = uiBinding.main.candidateGroupList,
            grid = uiBinding.main.candidatePanel,
        ).also { controller ->
            controller.onPickCandidate = { item -> onPickPanelCandidate(item) }
            controller.onHideKeyboard = { trime.requestHideSelf(0) }
        }
        candidateBarBinder = CandidateBarBinder(candidateView)
        twelveKeyController = TwelveKeyController(
            scope = this,
            onSnapshot = { snapshot -> onTwelveKeySnapshot(snapshot) },
            // 12 鍵的 preedit 由 TwelveKeyPreedit 改寫，長度與 librime 的 sel_start/sel_end 不一致，
            // Composition.setWindow() 會在 updateComposing() 內拋 StringIndexOutOfBoundsException。
            // 這裡保證：commit buffer 先落地；updateComposing 失敗只記日誌，進程不死，快照照常重建。
            onEngineChanged = {
                val committed = runCatching { Rime.getCommit() }.getOrDefault(false)
                if (committed) {
                    val text = Rime.getCommitText()
                    Timber.i("twelvekey: commit buffer=[%s]", text)
                    runCatching { activeEditorInstance.commitText(text, false) }
                        .onFailure { Timber.e(it, "twelvekey: commitText failed") }
                } else {
                    Timber.i("twelvekey: no commit buffer after select")
                }
                runCatching { trime.updateComposing() }
                    .onFailure { Timber.e(it, "twelvekey: updateComposing failed") }
                twelveKeyController?.refresh(immediate = true)
            },
            onCommitText = { text ->
                runCatching {
                    activeEditorInstance.commitText(text)
                    trime.updateComposing()
                }.onFailure { Timber.e(it, "twelvekey: fallback commit failed") }
            },
        )
    }

    /**
     * librime session 非線程安全：與 [TwelveKeyController] 的後台枚舉共用同一把鎖，
     * 保證「UI 線程 process_key」與「Default 線程 enumerate_candidates」不會並發。
     */
    private inline fun <T> withEngineLock(block: () -> T): T =
        synchronized(TwelveKeyController.ENGINE_LOCK, block)

    // ---------- 候選展開面板 ----------

    private fun isTwelveKeyMode(): Boolean = runCatching {
        // 主題 style/keyboards 裡的名字是 hakka_jiugong（其 preset 為 jiugong_simp），兩者都接受
        val keyboard = keyboardSwitcher.getCurrentKeyboardName()
        Rime.getSchemaId() == "hakka_jiugong" &&
            (keyboard == "hakka_jiugong" || keyboard == "jiugong_simp")
    }.getOrDefault(false)

    /**
     * 12 鍵下按空格：上屏「候選條第一條」而不是引擎高亮條（引擎高亮仍是引擎順序第 0 個，
     * 學習重排後它已經不是用戶看到的第一個）。
     */
    fun commitTopCandidateIfTwelveKey(keyCode: Int): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_SPACE) return false
        if (!isTwelveKeyMode() || !Rime.isComposing()) return false
        val item = boundBarItems.firstOrNull()
            ?: twelveKeyController?.snapshot?.barItems?.firstOrNull()
            ?: return false
        withEngineLock { twelveKeyController?.selectCandidate(item.index) }
        return true
    }

    fun isCandidatePanelShown(): Boolean =
        candidatePanelController?.isShown == true ||
            candidatePanelRoot?.visibility == android.view.View.VISIBLE

    /** 切換展開面板；空候選時 ▼ 的語義是「收起鍵盤」。 */
    fun toggleCandidatePanel() {
        if (isTwelveKeyMode()) {
            // 快照通常在按鍵後 120ms 內就位；只有完全沒快照時才同步算一次（首開面板）
            if (twelveKeyController?.snapshot?.isEmpty != false && Rime.isComposing()) {
                twelveKeyController?.refreshSync()
            }
            candidatePanelController?.toggle()
            twelveKeyController?.refresh(immediate = true, full = true)
            candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
            return
        }
        if (isCandidatePanelShown()) {
            hideCandidatePanel()
            return
        }
        if (!Rime.isComposing()) {
            Trime.getService().requestHideSelf(0)
            return
        }
        showCandidatePanel()
    }

    fun showCandidatePanel() {
        if (!Rime.isComposing()) return
        if (isTwelveKeyMode()) {
            if (twelveKeyController?.snapshot?.isEmpty != false) twelveKeyController?.refreshSync()
            val snapshot = twelveKeyController?.snapshot
            if (snapshot == null || (!snapshot.hasCandidates && snapshot.fallback == null)) return
            candidatePanelController?.showFollowingBar()
            // 面板要完整分組：開面板時全量枚舉一次
            twelveKeyController?.refresh(immediate = true, full = true)
        } else {
            // 非 12 鍵方案（客語 26 鍵等「全字母」）：面板內容由 App 現算一份全局下標候選，
            // 交給同一個面板控制器顯示。之前這條路徑沒人餵內容、show() 又直接 return，
            // 導致全字母方案按 ▼ 完全打不開（用戶 2026-11-09）。
            val items = legacyPanelItems()
            if (items.isEmpty()) return
            candidatePanelController?.showLegacy(items)
        }
        candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
    }

    fun hideCandidatePanel() {
        candidatePanelController?.hide()
        candidateView?.setPanelOpen(false)
        if (Rime.isComposing()) {
            runCatching { trime.updateComposing() }
                .onFailure { Timber.e(it, "twelvekey: hide updateComposing failed") }
        }
    }

    /** 12 鍵快照落地：條與面板同源，面板保持打開直到輸入清空。 */
    private fun onTwelveKeySnapshot(snapshot: TwelveKeySnapshot) {
        if (lastKeyAtNs != 0L) {
            // 量「按鍵 → 候選條落地」延遲：debug 版才印，release 不產生字串
            if (com.hazukieq.hkrime.BuildConfig.DEBUG) {
                Timber.d(
                    "twelvekey:latency %.1fms input=[%s] items=%d",
                    (System.nanoTime() - lastKeyAtNs) / 1e6,
                    snapshot.input,
                    snapshot.barItems.size,
                )
            }
            lastKeyAtNs = 0L
        }
        boundBarItems = snapshot.barItems
        candidateBarBinder?.bind(snapshot)
        candidatePanelController?.submit(snapshot)
        if (snapshot.isEmpty || (!snapshot.hasCandidates && snapshot.fallback == null)) {
            if (candidatePanelController?.isShown == true) candidatePanelController?.hide()
        }
        // 面板可能被 submit() 自動收起（上屏後無剩餘碼），箭頭方向要跟著回到 ▼
        candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
    }

    private fun onPickPanelCandidate(item: CandidatePanelAdapter.Item) {
        if (!isTwelveKeyMode()) {
            pickLegacyPanelCandidate(item)
            return
        }
        if (item.index < 0) {
            // fallback：本地原文上屏
            twelveKeyController?.snapshot?.fallback?.let { twelveKeyController?.commitFallback(it) }
            return
        }
        twelveKeyController?.selectCandidate(item.index)
    }

    /**
     * 非 12 鍵面板點候選：走 librime 的全量下標（與 12 鍵同一套語義）。
     * index < 0 是「本地原文」兜底項：直接提交當前組合，不學習。
     */
    private fun pickLegacyPanelCandidate(item: CandidatePanelAdapter.Item) {
        if (item.index < 0) {
            withEngineLock { runCatching { Rime.commitComposition() } }
            runCatching { activeEditorInstance.commitRimeText() }
                .onFailure { Timber.e(it, "legacy panel: commitRimeText failed") }
            return
        }
        val ok = withEngineLock {
            runCatching {
                val selected = Rime.select_candidate(item.index)
                if (selected) Rime.getContexts()
                selected
            }.getOrDefault(false)
        }
        if (!ok) {
            Timber.w("legacy panel: select_candidate(%d) failed", item.index)
            return
        }
        runCatching { activeEditorInstance.commitRimeText() }
            .onFailure { Timber.e(it, "legacy panel: commitRimeText failed") }
    }

    /** 除錯：只印出分段信息，不再移動 caret / 改引擎狀態。 */
    fun debugCaretScan() {
        val input = Rime.RimeGetInput() ?: return
        Timber.i(
            "caretScan input=[%s] caret=%s segs=%s",
            input,
            Rime.RimeGetCaretPos(),
            Rime.getRimeCompositionSegments().joinToString(","),
        )
    }

    /** 除錯：列印當前快照。 */
    fun debugCaretProbe() {
        val snapshot = twelveKeyController?.snapshot
        Timber.i(
            "caretProbe snapshot input=[%s] consumedEnd=%s remaining=[%s] groups=%s",
            snapshot?.input,
            snapshot?.consumedEnd,
            snapshot?.remaining,
            snapshot?.groups?.joinToString("|") { "${it.reading}:${it.size}" },
        )
    }

    /** 候選刷新後呼叫：12 鍵重建快照；其它方案只刷新展開面板。 */
    fun onCandidatesRefreshed() {
        if (isTwelveKeyMode()) {
            // 按鍵後立即重建快照（不 debounce）；面板關閉時只枚舉候選條需要的 16 條，
            // 面板打開才全量枚舉（按鍵卡頓的另一半原因：每鍵 600 條枚舉）。
            twelveKeyController?.refresh(
                immediate = true,
                full = isCandidatePanelShown(),
            )
            return
        }
        candidateBarBinder?.clear()
        if (!isCandidatePanelShown()) return
        if (!Rime.isComposing()) {
            hideCandidatePanel()
            return
        }
        candidatePanelController?.updateLegacy(legacyPanelItems())
    }

    // ---------- 展開面板：非 12 鍵方案（全字母 26 鍵等） ----------

    /**
     * 非 12 鍵方案的右格候選：一次性全量枚舉（全局下標，點擊直接 `select_candidate(index)`，
     * 語義與 12 鍵面板一致）。沒有候選（只剩未轉換編碼）時給一條「本地原文」兜底項（index = -1）。
     */
    private fun legacyPanelItems(): List<CandidatePanelAdapter.Item> {
        val items = withEngineLock {
            runCatching {
                Rime.candidatesSnapshot(PANEL_ENUM_MAX).mapIndexedNotNull { index, row ->
                    val text = row.getOrNull(0).orEmpty()
                    if (text.isEmpty()) null
                    else CandidatePanelAdapter.Item(text, row.getOrNull(1).orEmpty(), index)
                }
            }.getOrDefault(emptyList())
        }
        if (items.isNotEmpty()) return items
        val rest = remainingCode()
        return if (rest.isEmpty()) emptyList()
        else listOf(CandidatePanelAdapter.Item(rest, "本地原文", -1))
    }

    /** 目前 preedit 裡還沒被轉換的編碼（去掉漢字/占位符/空格），例如只剩 u 時回傳 "u"。 */
    private fun remainingCode(): String {
        val preedit = runCatching { Rime.getCompositionText() }.getOrNull().orEmpty()
            .replace("\u2038", "")
        return preedit.filter { it.code < 128 && (it.isLetterOrDigit() || it == '\'') }
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
        // 新輸入框/新會話：面板狀態一律復位，否則會留下「鍵盤被 GONE、面板空的」灰塊
        hideCandidatePanel()
        Trime.getService().selectLiquidKeyboard(-1)
        twelveKeyController?.reset()
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
            // 釋放事件同樣會 process_key + getContexts，必須和後台枚舉共用引擎鎖，
            // 否則 60 鍵連打時會 native SIGSEGV（RimeGetContext 空指針，2026-11-08 崩潰日誌）。
            withEngineLock {
                if (shouldUpdateRimeOption) {
                    Rime.setOption("soft_cursors", prefs.keyboard.softCursorEnabled)
                    Rime.setOption("_horizontal", trime.imeConfig.getBoolean("horizontal"))
                    shouldUpdateRimeOption = false
                }
                // todo 释放按键可能不对
                Rime.onKey(Event.getRimeEvent(keyEventCode, Rime.META_RELEASE_ON))
                activeEditorInstance.commitRimeText()
            }
        }
        Timber.d("\t<TrimeInput>\tonRelease() finish")
    }

    // KeyboardEvent 处理软键盘事件
    /** 最近一次按鍵的時間戳（ns）：用來量「按鍵 → 候選條落地」的延遲。 */
    private var lastKeyAtNs = 0L

    /** 最近一次綁到候選條的條目（點擊下標必須按這份解析，保證所見即所得）。 */
    private var boundBarItems: List<CandidateItem> = emptyList()

    override fun onEvent(event: Event?) {
        lastKeyAtNs = System.nanoTime()
        withEngineLock { onEventLocked(event) }
    }

    private fun onEventLocked(event: Event?) {
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
        withEngineLock { onTextLocked(text) }
    }

    private fun onTextLocked(text: CharSequence?) {
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
        withEngineLock { onCandidatePressedLocked(index) }
    }

    private fun onCandidatePressedLocked(index: Int) {
        onPress(0)
        if (isTwelveKeyMode() && Rime.isComposing()) {
            onTwelveKeyCandidatePressed(index)
            return
        }
        if (isTwelveKeyMode()) {
            // 未組字：候選條上的條目是方案開關
            if (index >= 0) {
                Rime.toggleOption(index)
                trime.updateComposing()
            }
            return
        }
        if (!Rime.isComposing()) {
            if (index >= 0) {
                Rime.toggleOption(index)
                trime.updateComposing()
            }
        } else if (true) {
            if (Rime.selectCandidate(index)) {
                if (true) {
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

    /** 12 鍵候選條：index 是條目下標；fallback 是條目列表末尾的本地原文項。 */
    private fun onTwelveKeyCandidatePressed(index: Int) {
        val snapshot = twelveKeyController?.snapshot ?: return
        // 用「最近一次綁到候選條的那份列表」做下標映射：顯示與點擊必須同一份數據，
        // 否則非同步刷新/學習重排後會出現「看到的是看、上屏的卻是别字」。
        val list = if (boundBarItems.isNotEmpty()) boundBarItems else snapshot.barItems
        val item = list.getOrNull(index)
        if (item != null) {
            twelveKeyController?.selectCandidate(item.index)
            return
        }
        val fallback = snapshot.fallback
        if (fallback != null && index == list.size) {
            twelveKeyController?.commitFallback(fallback)
        }
    }

    override fun onCandidateSymbolPressed(arrow: String) {
        withEngineLock { onCandidateSymbolPressedLocked(arrow) }
    }

    private fun onCandidateSymbolPressedLocked(arrow: String) {
        when (arrow) {
            Candidate.PAGE_UP_BUTTON -> onKey(KeyEvent.KEYCODE_PAGE_UP, 0)
            Candidate.PAGE_DOWN_BUTTON -> onKey(KeyEvent.KEYCODE_PAGE_DOWN, 0)
            Candidate.PAGE_EX_BUTTON -> toggleCandidatePanel()
            // 候選為空時，右側按鈕的行為是收起鍵盤
            Candidate.HIDE_KEYBOARD_BUTTON -> Trime.getService().requestHideSelf(0)
            // Gboard 式左側菜單：方案開關（简/繁、情、联）
            Candidate.MENU_BUTTON -> trime.showSchemaSwitchDialog()
            // 展開箭頭左側：配色
            Candidate.THEME_BUTTON -> trime.showColorDialog()
        }
    }

    override fun onCandidateLongClicked(index: Int) {
        // 12 鍵候選條是全局快照，長按刪除要拿到 menu 全局下標；P0 先禁用，避免刪錯詞。
        if (isTwelveKeyMode()) return
        Rime.deleteCandidate(index)
        trime.updateComposing()
    }
}
