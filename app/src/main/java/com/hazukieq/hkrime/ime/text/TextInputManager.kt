package com.hazukieq.hkrime.ime.text

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import com.hazukieq.hkrime.core.twelvekey.CandidateSelection
import com.hazukieq.hkrime.core.twelvekey.CompositionInput
import com.hazukieq.hkrime.core.twelvekey.CompositionSession
import com.hazukieq.hkrime.core.twelvekey.PreeditPresentation
import com.hazukieq.hkrime.core.twelvekey.CandidateBarBinder
import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.Fallback
import com.hazukieq.hkrime.core.twelvekey.ISOLATED_BACK_SEGMENT
import com.hazukieq.hkrime.core.twelvekey.ISOLATED_CHAR_SEGMENT
import com.hazukieq.hkrime.core.twelvekey.ISOLATED_SEGMENT
import com.hazukieq.hkrime.core.twelvekey.SYNTHETIC_SEGMENT
import com.hazukieq.hkrime.core.twelvekey.CandidatePanelController
import com.hazukieq.hkrime.core.twelvekey.TwelveKeyController
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshot
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.util.DiagLog
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.databinding.InputRootBinding
import com.hazukieq.hkrime.ime.broadcast.IntentReceiver
import com.hazukieq.hkrime.ime.core.EditorInstance
import com.hazukieq.hkrime.ime.core.Speech
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.ime.enums.Keycode
import com.hazukieq.hkrime.ime.keyboard.Event
import com.hazukieq.hkrime.ime.keyboard.Keyboard.printModifierKeyState
import com.hazukieq.hkrime.ime.keyboard.KeyboardSwitcher
import com.hazukieq.hkrime.ime.keyboard.KeyboardView
import com.hazukieq.hkrime.ime.edit.EditPanelController
import com.hazukieq.hkrime.ime.symbol.SymbolPanelController
import com.hazukieq.hkrime.util.ShortcutUtils
import com.hazukieq.hkrime.util.startsWithAsciiChar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var symbolPanelController: SymbolPanelController? = null
    private var editPanelController: EditPanelController? = null
    private var legacyExpandJob: kotlinx.coroutines.Job? = null
    private var schemaWarmupJob: kotlinx.coroutines.Job? = null
    private var candidateBarBinder: CandidateBarBinder? = null


    var candidateRoot: android.view.View? = null
    var candidateView: Candidate? = null

    var locales: Array<Locale> = Array(2) { Locale.getDefault() }

    var needSendUpRimeKey: Boolean = false
    var shouldUpdateRimeOption: Boolean = true
    var performEnterAsLineBreak: Boolean = false
    var isComposable: Boolean = false
    var shouldResetAsciiMode: Boolean = false

    companion object {
        private const val FAST_LEGACY_PANEL_CANDIDATES = 60
        /** Delimiter regex for key property group, their format like `{property_1: value_1, property_2: value_2}` */
        private val DELIMITER_PROPERTY_GROUP = """^(\{[^{}]+\}).*$""".toRegex()
        /** Delimiter regex for property key tag, its format like `Escape: ` following a property group like above */
        private val DELIMITER_PROPERTY_KEY = """^((\{Escape\})?[^{}]+).*$""".toRegex()
        /** Delimiter regex to split language/locale tags. */
        private val DELIMITER_SPLITTER = """[-_]""".toRegex()
        /** 逐字組詞接續時間窗（毫秒）：超過即視為新詞。 */
        private const val RUN_WINDOW_MS = 8_000L
        /** 接續組詞的最大字數，避免整句被併成一條詞。 */
        private const val MAX_RUN_LENGTH = 8

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
        // 排查「引擎為什麼給不出某個詞」：/sdcard/hkrime/dbg_candidates.txt 裡寫一行碼串，
        // 下次輸入法起來就把引擎候選寫到 dbg_candidates.log 並把請求文件改名 .done。
        // 為什麼用文件而不是廣播：設置界面崩潰排查時 IME 進程還沒起來、動態廣播收不到。
        // Initialize main keyboard view
        mainKeyboardView = uiBinding.main.mainKeyboardView.also {
            it.setOnKeyboardActionListener(this)
            it.setShowHint(!Rime.getOption("_hide_key_hint"))
            it.setShowSymbol(!Rime.getOption("_hide_key_symbol"))
            it.reset(trime)
        }
        // Initialize candidate bar
        // 固定候選欄現在是普通容器：不滾動、不左右滑（避免左滑誤開展開面板）。
        candidateRoot = uiBinding.main.candidateView.candidateRoot.also {
            it.visibility = if (Rime.getOption("_hide_candidate")) View.GONE else View.VISIBLE
        }

        candidateView = uiBinding.main.candidateView.candidates.also {
            it.setCandidateListener(this)
            // 固定候選條關閉讀音提示，讀音僅用於面板分組。
            it.setShowComment(false)
            it.reset(trime)
        }

        // 候選展開面板（所有方案都只讀快照：12 鍵由引擎按鍵驅動，全字母方案開面板時現算）
        inputArea = uiBinding.main.inputArea
        candidatePanelRoot = uiBinding.main.candidatePanelRoot
        candidatePanelController = CandidatePanelController(
            context = trime,
            keyboardView = mainKeyboardView,
            inputArea = inputArea,
            panelRoot = candidatePanelRoot,
            groupListView = uiBinding.main.candidateGroupList,
            grid = uiBinding.main.candidatePanel,
            backspaceView = uiBinding.main.panelBackspace,
            topDivider = uiBinding.main.panelTopDivider,
            railDivider = uiBinding.main.panelRailDivider,
        ).also { controller ->
            controller.onPickCandidate = { item -> onPickPanelCandidate(item) }
            controller.onConfirmPhonetic = { reading, initialOnly, key -> confirmTwelveKeyPhonetic(reading, initialOnly, key) }
            controller.onInitialLetter = { letter -> onInitialLetter(letter) }
            controller.onLoadAll = { loadAllPanelCandidates() }
            controller.onHideKeyboard = { trime.requestHideSelf(0) }
            // 面板右下的退格鍵：與鍵盤退格走同一條鏈路（引擎刪除 + 通知刷新面板）
            controller.onBackspace = { onKey(KeyEvent.KEYCODE_DEL, 0) }
        }
        val symBinding = uiBinding.main.symbolPanel
        symbolPanelController = SymbolPanelController(
            context = trime,
            keyboardView = uiBinding.main.mainKeyboardView,
            inputArea = uiBinding.main.inputArea,
            panelRoot = symBinding.symbolPanelRoot,
            gridView = symBinding.symbolGrid,
            tabsView = symBinding.symbolTabs,
            btnBack = symBinding.symbolBtnBack,
            btnUp = symBinding.symbolBtnUp,
            btnDown = symBinding.symbolBtnDown,
            btnLock = symBinding.symbolBtnLock,
            actionColumn = symBinding.symbolActionCol,
            dividerHorizontal = symBinding.symbolDividerH,
        ).also { controller ->
            controller.onCommitSymbol = { symbol ->
                activeEditorInstance.commitText(symbol, false)
            }
        }
        val editBinding = uiBinding.main.editPanel
        editPanelController = EditPanelController(
            context = trime,
            keyboardView = uiBinding.main.mainKeyboardView,
            inputArea = uiBinding.main.inputArea,
            panelRoot = editBinding.editPanelRoot,
            btnBack = editBinding.editBtnBack,
            iconBack = editBinding.editIconBack,
            titleBack = editBinding.editTitleBack,
            dpadWheel = editBinding.editDpadWheel,
            btnUp = editBinding.editBtnUp,
            btnDown = editBinding.editBtnDown,
            btnLeft = editBinding.editBtnLeft,
            btnRight = editBinding.editBtnRight,
            btnSelect = editBinding.editBtnSelect,
            btnHome = editBinding.editBtnHome,
            btnEnd = editBinding.editBtnEnd,
            btnSelectAll = editBinding.editBtnSelectAll,
            btnDelete = editBinding.editBtnDelete,
            btnCopy = editBinding.editBtnCopy,
            btnCut = editBinding.editBtnCut,
            btnPaste = editBinding.editBtnPaste,
            btnUndo = editBinding.editBtnUndo,
        ).also { controller ->
            controller.onAction = { action, selecting -> handleEditPanelAction(action, selecting) }
            controller.onSelectModeChanged = { resetEditSelectionAnchor() }
        }
        candidateBarBinder = CandidateBarBinder(candidateView)
        twelveKeyController = TwelveKeyController(
            scope = this,
            onSnapshot = { snapshot -> onTwelveKeySnapshot(snapshot) },
        )
    }

    /**
     * librime session 非線程安全：與 [TwelveKeyController] 的後台枚舉共用同一把鎖，
     * 保證「UI 線程 process_key」與「Default 線程 enumerate_candidates」不會並發。
     */
    private inline fun <T> withEngineLock(block: () -> T): T =
        synchronized(TwelveKeyController.ENGINE_LOCK, block)

    // ---------- 候選展開面板 ----------

    fun isTwelveKeyMode(): Boolean = runCatching {
        val keyboard = keyboardSwitcher.getCurrentKeyboardName()
        Rime.isTwelveKeySchema() &&
            Config.get(trime).isKeyboardLayoutOfType(
                keyboard,
                com.hazukieq.hkrime.data.KeyboardLayoutResolver.TYPE_12KEY,
            )
    }.getOrDefault(false)

    /**
     * 「用戶想要面板開著」的意圖。
     *
     * 只靠 [isCandidatePanelShown] 判斷會漏：選字/上屏那一瞬間 view 可能短暫不可見，
     * 於是 refresh(full=false) 建出一份「沒有分組」的快照，面板就只剩 `{we} … 本地原文`；
     * 再關一次再開一次才拿到分組。這裡用意圖兜住這一瞬。
     */
    private var panelWanted = false

    fun isCandidatePanelShown(): Boolean =
        candidatePanelController?.isShown == true ||
            candidatePanelRoot?.visibility == android.view.View.VISIBLE

    /**
     * 統一刷新：面板已顯示或要求顯示時傳 full=true，確保連續選字後仍有完整分組。
     */
    private fun refreshTwelveKey() {
        twelveKeyController?.appRankingEnabled = prefs.keyboard.candidateAlgorithmEnabled
        twelveKeyController?.phonetics = composition.phonetics
        twelveKeyController?.refresh(
            immediate = true,
            full = panelWanted || isCandidatePanelShown(),
        )
    }

    fun onCandidateAlgorithmChanged() {
        twelveKeyController?.appRankingEnabled = prefs.keyboard.candidateAlgorithmEnabled
        if (isTwelveKeyMode()) refreshTwelveKey()
    }

    /** 主題/配色/深淺模式切換時即時同步展開面板、符號面板與編輯面板。 */
    fun applyTheme() {
        candidatePanelController?.applyTheme()
        symbolPanelController?.applyTheme()
        editPanelController?.applyTheme()
    }

    /**
     * 切換方案後在後台預熱 librime 懶加載組件（OpenCC 簡繁/表情詞典、Translator 詞庫 mmap、Lua 翻譯器），
     * 避免用戶切完方案按下首鍵時在主線程卡頓。
     */
    fun warmUpActiveSchemaAsync() {
        schemaWarmupJob?.cancel()
        schemaWarmupJob = launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                withEngineLock {
                    if (Rime.isComposing() || Rime.isAsciiMode() || Rime.isEmpty()) return@withEngineLock
                    val schemaId = Rime.getSchemaId()
                    val autoSelect = Rime.getSchemaValueCached(schemaId, "speller/auto_select")
                    if (autoSelect == true || "true".equals(autoSelect?.toString(), ignoreCase = true)) {
                        return@withEngineLock
                    }
                    val caps = com.hazukieq.hkrime.data.KeyboardLayoutResolver.capabilitiesOf(
                        Rime.getSchemaKeyboardType(schemaId),
                    )
                    val probeChar = caps.warmupProbeChar.code
                    if (Rime.process_key(probeChar, 0)) {
                        Rime.getContexts()
                        Rime.clearComposition()
                    }
                }
            }
        }
    }

    private var editSelectionAnchor: Int = -1
    private var editSelectionFocus: Int = -1

    private fun resetEditSelectionAnchor() {
        editSelectionAnchor = -1
        editSelectionFocus = -1
    }

    private fun handleEditPanelAction(action: EditPanelController.EditAction, selecting: Boolean) {
        val ic = trime.currentInputConnection ?: return
        fun sendKey(keyCode: Int, meta: Int = 0) {
            val now = android.os.SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        fun sendShiftedKey(keyCode: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, shiftMeta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, shiftMeta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, shiftMeta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        }
        fun extendSelectionByExtractedText(dir: EditPanelController.EditAction): Boolean {
            val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                ?: return false
            val text = extracted.text ?: return false
            val len = text.length
            val selStart = extracted.selectionStart.coerceIn(0, len)
            val selEnd = extracted.selectionEnd.coerceIn(0, len)
            if (editSelectionAnchor < 0 || editSelectionAnchor > len) {
                editSelectionAnchor = selStart
                editSelectionFocus = selEnd
            } else if (selStart != editSelectionAnchor && selEnd != editSelectionAnchor) {
                editSelectionAnchor = selStart
                editSelectionFocus = selEnd
            }
            val curFocus = editSelectionFocus.coerceIn(0, len)
            val nextFocus = when (dir) {
                EditPanelController.EditAction.LEFT -> (curFocus - 1).coerceAtLeast(0)
                EditPanelController.EditAction.RIGHT -> (curFocus + 1).coerceAtMost(len)
                EditPanelController.EditAction.HOME -> {
                    val nl = text.lastIndexOf('\n', (curFocus - 1).coerceAtLeast(0))
                    if (nl >= 0) nl + 1 else 0
                }
                EditPanelController.EditAction.END -> {
                    val nl = text.indexOf('\n', curFocus)
                    if (nl >= 0) nl else len
                }
                else -> return false
            }
            editSelectionFocus = nextFocus
            return ic.setSelection(editSelectionAnchor, nextFocus)
        }

        if (!selecting) resetEditSelectionAnchor()

        when (action) {
            EditPanelController.EditAction.UP -> {
                if (selecting) {
                    resetEditSelectionAnchor()
                    sendShiftedKey(KeyEvent.KEYCODE_DPAD_UP)
                } else {
                    sendKey(KeyEvent.KEYCODE_DPAD_UP)
                }
            }
            EditPanelController.EditAction.DOWN -> {
                if (selecting) {
                    resetEditSelectionAnchor()
                    sendShiftedKey(KeyEvent.KEYCODE_DPAD_DOWN)
                } else {
                    sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
                }
            }
            EditPanelController.EditAction.LEFT -> {
                if (selecting) {
                    if (!extendSelectionByExtractedText(action)) sendShiftedKey(KeyEvent.KEYCODE_DPAD_LEFT)
                } else {
                    sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
                }
            }
            EditPanelController.EditAction.RIGHT -> {
                if (selecting) {
                    if (!extendSelectionByExtractedText(action)) sendShiftedKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                } else {
                    sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            EditPanelController.EditAction.HOME -> {
                if (selecting) {
                    if (!extendSelectionByExtractedText(action)) sendShiftedKey(KeyEvent.KEYCODE_MOVE_HOME)
                } else {
                    sendKey(KeyEvent.KEYCODE_MOVE_HOME)
                }
            }
            EditPanelController.EditAction.END -> {
                if (selecting) {
                    if (!extendSelectionByExtractedText(action)) sendShiftedKey(KeyEvent.KEYCODE_MOVE_END)
                } else {
                    sendKey(KeyEvent.KEYCODE_MOVE_END)
                }
            }
            EditPanelController.EditAction.SELECT_ALL -> {
                resetEditSelectionAnchor()
                ic.performContextMenuAction(android.R.id.selectAll)
            }
            EditPanelController.EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            EditPanelController.EditAction.CUT -> {
                resetEditSelectionAnchor()
                if (!ic.performContextMenuAction(android.R.id.cut)) {
                    sendKey(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                }
            }
            EditPanelController.EditAction.PASTE -> {
                resetEditSelectionAnchor()
                ic.performContextMenuAction(android.R.id.paste)
            }
            EditPanelController.EditAction.UNDO -> {
                resetEditSelectionAnchor()
                if (!ic.performContextMenuAction(android.R.id.undo)) {
                    sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                }
            }
            EditPanelController.EditAction.DELETE -> {
                resetEditSelectionAnchor()
                sendKey(KeyEvent.KEYCODE_DEL)
            }
        }
    }

    /**
     * 左列音節行：確認一段完整音節（消費實際匹配跨度）。
     * 字母行統一走 [onInitialLetter]：還有待確認碼就確認首拼，否則替換最後一段的首拼。
     */
    private fun confirmTwelveKeyPhonetic(reading: String, initialOnly: Boolean, contextKey: String) {
        withEngineLock {
            if (!isTwelveKeyMode()) return@withEngineLock
            twelveKeyController?.flushPendingRestoreLocked()
            val local = TwelveKeySnapshotBuilder.local()
            if (!CandidateSelection.isCurrent(contextKey, twelveKeyController?.contextKey(local).orEmpty())) {
                refreshTwelveKey()
                return@withEngineLock
            }
            if (!composition.bindEditor(currentEditorIdentity())) return@withEngineLock
            composition.input(local.remaining)
            if (!composition.confirmPhonetic(reading, initialOnly)) return@withEngineLock
            applyPhoneticChange()
        }
    }

    /** 左列字母行：確認下一段首拼或細化最後一段（同一份原碼，不另消費）。 */
    private fun onInitialLetter(letter: String) {
        withEngineLock {
            if (!isTwelveKeyMode()) return@withEngineLock
            twelveKeyController?.flushPendingRestoreLocked()
            val local = TwelveKeySnapshotBuilder.local()
            if (!composition.bindEditor(currentEditorIdentity())) return@withEngineLock
            composition.input(local.remaining)
            if (!composition.onInitialLetter(letter)) return@withEngineLock
            applyPhoneticChange()
        }
    }

    /**
     * 拼音確認/細化後立刻用緩存或樂觀快照推進左列與預覽（<1ms），
     * 同時把引擎原碼重放與全量枚舉交給後台線程無縫補齊。
     */
    private fun applyPhoneticChange() {
        val rem = composition.remaining
        val confirmed = composition.phonetics
        val full = panelWanted || isCandidatePanelShown()
        twelveKeyController?.scheduleRestore(rem, confirmed)
        PreeditPresentation.invalidate(composition.selectedText, confirmed)
        preservePanelReading = false
        val instant = twelveKeyController?.lookupCached(rem, confirmed, full)
            ?: TwelveKeySnapshotBuilder.optimisticPhonetic(
                previous = currentSnapshot,
                remaining = rem,
                phonetics = confirmed,
                appRankingEnabled = prefs.keyboard.candidateAlgorithmEnabled,
            )
        if (instant != null) {
            onTwelveKeySnapshot(instant)
        } else {
            trime.updateComposing()
        }
        refreshTwelveKey()
    }

    /** 切換展開面板；空候選時 ▼ 的語義是「收起鍵盤」。 */
    fun toggleCandidatePanel() {
        if (isTwelveKeyMode()) {
            if (panelWanted || isCandidatePanelShown()) hideCandidatePanel() else showCandidatePanel()
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
        panelWanted = true
        if (isTwelveKeyMode()) {
            // 保持鍵盤可見，完整快照到達才展示面板，不在主線程同步枚舉。
            refreshTwelveKey()
        } else {
            // 非 12 鍵方案兩段式瞬開：先同步取前 60 條立即上屏（0 空白幀），再後台補齊 600 條。
            legacyExpandJob?.cancel()
            val fastSnapshot = legacySnapshot(FAST_LEGACY_PANEL_CANDIDATES)
            if (fastSnapshot.isEmpty) return
            candidatePanelController?.submit(fastSnapshot)
            if (candidatePanelController?.isShown != true) candidatePanelController?.show()
            if (fastSnapshot.recommend.size >= FAST_LEGACY_PANEL_CANDIDATES) {
                val expectedKey = fastSnapshot.contextKey
                legacyExpandJob = launch(kotlinx.coroutines.Dispatchers.Default) {
                    val fullSnapshot = legacySnapshot(TwelveKeySnapshotBuilder.MAX_CANDIDATES)
                    if (fullSnapshot.isEmpty || fullSnapshot.contextKey != expectedKey) return@launch
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (panelWanted && isCandidatePanelShown() && !isTwelveKeyMode()) {
                            candidatePanelController?.submit(fullSnapshot, preserveReading = true)
                        }
                    }
                }
            }
        }
        candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
    }

    /** 展開面板滾動到底部點擊「全部」：後台按需枚舉至 CONFIRM_ALL_LIMIT（1000）。 */
    private fun loadAllPanelCandidates() {
        if (!panelWanted || !isCandidatePanelShown() || !Rime.isComposing()) return
        if (isTwelveKeyMode()) {
            twelveKeyController?.refresh(immediate = true, full = true, loadAll = true)
            return
        }
        legacyExpandJob?.cancel()
        legacyExpandJob = launch(kotlinx.coroutines.Dispatchers.Default) {
            val fullSnapshot = legacySnapshot(com.hazukieq.hkrime.core.twelvekey.TwelveKeyTuning.CONFIRM_ALL_LIMIT)
            if (fullSnapshot.isEmpty) return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (panelWanted && isCandidatePanelShown() && !isTwelveKeyMode()) {
                    candidatePanelController?.submit(fullSnapshot, preserveReading = true)
                }
            }
        }
    }

    fun hideCandidatePanel() {
        panelWanted = false
        legacyExpandJob?.cancel()
        legacyExpandJob = null
        candidatePanelController?.hide()
        symbolPanelController?.hide()
        editPanelController?.hide()
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
        if (snapshot.isEmpty) {
            if (!composition.active) panelWanted = false
        }
        currentSnapshot = snapshot
        val preferredChanged = PreeditPresentation.publish(snapshot, composition.selectedText)
        candidateBarBinder?.bind(snapshot)
        candidatePanelController?.submit(snapshot, preserveReading = preservePanelReading)
        preservePanelReading = false
        if (panelWanted && snapshot.groups.isNotEmpty() && candidatePanelController?.isShown != true) {
            candidatePanelController?.show()
        }
        if (snapshot.isEmpty || (!snapshot.hasCandidates && snapshot.fallback == null && snapshot.phonetics.isEmpty())) {
            if (candidatePanelController?.isShown == true) candidatePanelController?.hide()
        }
        // 面板可能被 submit() 自動收起（上屏後無剩餘碼），箭頭方向要跟著回到 ▼
        candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
        // 首選變化或上一幀未完成讀音渲染時補畫 preedit，使用已發布快照。
        if (preferredChanged && (Rime.isComposing() || composition.active)) refreshPreeditAfterSnapshot()
    }

    /**
     * 快照落地後重畫一次編碼區（preedit）：`getComposition()` 讀的正是上面剛發布的
     * 「候選條第一條」，而按鍵鏈路裡的 `updateComposing()` 在快照之前就跑了 —— 不補這一下，
     * preedit 會永遠落後一個按鍵（顯示上一條候選的讀音）。只在首條變化時做，避免每鍵重畫。
     *
     * `updateComposing()` 內部會再走 [onCandidatesRefreshed]；這裡抑制那一次，因為當前快照
     * 已經是最新的（再刷只是一次緩存命中 + 重複一次 bind）。
     */
    private fun refreshPreeditAfterSnapshot() {
        suppressSnapshotRefresh = true
        runCatching { trime.updateComposing() }
            .onFailure { Timber.e(it, "twelvekey: preedit refresh failed") }
        suppressSnapshotRefresh = false
    }

    private val composition = CompositionSession()
    private var preservePanelReading = false
    private var rawEngineCommitAllowed = false

    /** 所有 12 鍵來源先證實消費範圍，再寫同一份選擇日誌。 */
    private fun chooseTwelveKey(item: CandidateItem, contextKey: String, origin: CompositionSession.Origin) {
        withEngineLock {
            twelveKeyController?.flushPendingRestoreLocked()
            if (!composition.bindEditor(currentEditorIdentity())) {
                cancelTwelveKeyComposition()
                refreshTwelveKey()
                return@withEngineLock
            }
            val local = TwelveKeySnapshotBuilder.local()
            val actualKey = twelveKeyController?.contextKey(local).orEmpty()
            if (!CandidateSelection.isCurrent(contextKey, actualKey) || local.remaining.isEmpty()) {
                refreshTwelveKey()
                return@withEngineLock
            }
            // 標點/符號不是 12 鍵碼串，沒有可證實的碼位消費：交引擎選中並提交。
            if (!CompositionInput.isCodeString(local.input)) {
                selectEngineCandidate(item)
                return@withEngineLock
            }
            val consumed = CandidateSelection.consumedEnd(item, local.remaining, origin) ?: run {
                Timber.w("twelvekey: unresolved selection text=%s reading=%s", item.text, item.comment)
                refreshTwelveKey()
                return@withEngineLock
            }
            // 原生部分選中不應與 App 日誌並存；保留原碼，退格仍可交引擎撤回。
            if (local.consumedEnd != 0) { refreshTwelveKey(); return@withEngineLock }
            composition.input(local.remaining)
            if (!composition.pick(item.text, item.inputSpan!!.reading, consumed, origin)) return@withEngineLock
            preservePanelReading = true
            twelveKeyController?.invalidateForEngine()
            PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
            if (composition.remaining.isNotEmpty()) {
                restoreTwelveKeyCode(composition.remaining)
            } else {
                // 最後一次選擇才允許原生學習；其 buffer 在這裡消耗，不直接寫編輯器。
                if (item.index >= 0) {
                    // 快照 contextKey 已在同一引擎鎖內驗證；篩選後的 index 仍是原 menu 全局下標。
                    if (item.engineSpan != null) {
                        runCatching { Rime.select_candidate(item.index); Rime.getContexts(); Rime.getCommit() }
                    }
                }
                Rime.clearComposition()
                commitCompletedSelection()
            }
            setSegmentAnchor(composition.selectedText.lastOrNull())
            trime.updateComposing()
            refreshTwelveKey()
        }
    }

    /** 非碼串（標點/符號）候選：不記選擇日誌，交引擎選中並立即提交。 */
    private fun selectEngineCandidate(item: CandidateItem) {
        if (item.index < 0) {
            refreshTwelveKey()
            return
        }
        val ok = runCatching { Rime.select_candidate(item.index) }.getOrDefault(false)
        if (!ok) {
            Timber.w("twelvekey: engine candidate %d not selectable", item.index)
            refreshTwelveKey()
            return
        }
        runCatching { Rime.getContexts() }
        // 使用者明確點選的非碼串候選，允許本次引擎 commit buffer 直通編輯器。
        rawEngineCommitAllowed = true
        activeEditorInstance.commitRimeText()
        trime.updateComposing()
        refreshTwelveKey()
    }

    private fun commitCompletedSelection() {
        val completed = composition.completed() ?: return
        // false/例外保留完整 journal 與許可，使用者仍可退格重選或確認重試。
        runCatching { activeEditorInstance.commitText(completed.text, false) }
            .onSuccess {
                // 這輪已提交：清掉已確認序列的會話屬性，下一次按鍵建菜單不能沿用舊過濾/簡拼開關。
                Rime.setProperty(com.hazukieq.hkrime.core.twelvekey.InitialDisambiguation.PROPERTY, "")
                twelveKeyController?.phonetics = emptyList()
            }
            .onFailure { Timber.e(it, "twelvekey: editor commit failed") }
    }

    /** 編輯器寫入前的最後守衛：生產與離線測試共用 commitThrough 交易。 */
    private fun currentEditorIdentity() = CompositionSession.EditorIdentity(
        activeEditorInstance.editorInfo, activeEditorInstance.inputConnection,
    )

    fun onEditorSessionChanged() {
        resetLearnedRunIfStale()
        withEngineLock {
            val editor = currentEditorIdentity()
            if (!composition.bindEditor(editor)) {
                cancelTwelveKeyComposition()
                composition.bindEditor(editor)
            }
        }
    }

    fun commitEditorText(text: String, editor: CompositionSession.EditorIdentity, write: () -> Boolean): Boolean = withEngineLock {
        if (!editor.same(currentEditorIdentity())) return@withEngineLock false
        if (!composition.belongsTo(editor)) {
            cancelTwelveKeyComposition()
            return@withEngineLock false
        }
        if (!composition.active) {
            return@withEngineLock if (isTwelveKeyMode() && Rime.isComposing()) false else write()
        }
        val completed = composition.commitThrough(text, editor, write) ?: return@withEngineLock false
        PreeditPresentation.invalidate("")
        Rime.setProperty(com.hazukieq.hkrime.core.twelvekey.InitialDisambiguation.PROPERTY, "")
        twelveKeyController?.phonetics = emptyList()
        learnCompletedSelection(completed)
        true
    }

    private fun learnCompletedSelection(completed: CompositionSession.Completion) {
        val text = completed.text
        runCatching {
            var learned = false
            completed.picks.forEach { pick ->
                if (pick.text.isNotEmpty() && pick.reading.isNotBlank()) {
                    if (Rime.learn_phrase(pick.text, pick.reading)) learned = true
                }
            }
            val readings = completed.picks.joinToString(" ") { it.reading }
            val learnable = text.isNotEmpty() &&
                completed.picks.isNotEmpty() &&
                completed.picks.all { it.reading.isNotBlank() }
            if (learnable) {
                val now = System.currentTimeMillis()
                // 逐字上屏（每段一次提交）也要能攒成詞：同一編輯器、時間窗內接續的提交併進同一條碼串，直接寫入原生 userdb。
                val continues = completed.picks.size == 1 && runText.isNotEmpty() &&
                    now - runAt <= RUN_WINDOW_MS && runText.length + text.length <= MAX_RUN_LENGTH
                runCode = if (continues) runCode + completed.code else completed.code
                runText = if (continues) runText + text else text
                runReadings = if (continues) "$runReadings $readings" else readings
                runAt = now
                if (runText.length >= 2 && (text.length >= 2 || runText.length == 2 || completed.picks.size >= 2)) {
                    if (Rime.learn_phrase(runText, runReadings.trim())) learned = true
                }
            } else {
                resetLearnedRun()
            }
            if (learned) twelveKeyController?.invalidateForEngine()
        }.onFailure { Timber.e(it, "twelvekey: post-commit learning failed") }
    }

    /** 逐字組詞的接續緩衝：跨提交累積碼串/文字/讀音，逾時或換編輯器即斷開。 */
    private var runCode = ""
    private var runText = ""
    private var runReadings = ""
    private var runAt = 0L

    /** 只有真的離開接續時間窗才斷開逐字組詞，避免提交後的收尾流程把緩衝清掉。 */
    private fun resetLearnedRunIfStale() {
        if (System.currentTimeMillis() - runAt > RUN_WINDOW_MS) resetLearnedRun()
    }

    private fun resetLearnedRun() {
        runCode = ""
        runText = ""
        runReadings = ""
        runAt = 0L
    }

    private fun restoreTwelveKeyCode(code: String) {
        twelveKeyController?.phonetics = composition.phonetics
        Rime.setProperty(com.hazukieq.hkrime.core.twelvekey.InitialDisambiguation.PROPERTY,
            com.hazukieq.hkrime.core.twelvekey.PhoneticSelection.constraint(composition.phonetics))
        Rime.clearComposition()
        if (code.isNotEmpty()) Rime.onText(code)
        Rime.getContexts()
    }

    /** 原生 buffer 不等同於使用者完成選擇。拒絕意外提交並還原待選碼。 */
    fun consumeTwelveKeyEngineCommit(): Boolean = withEngineLock {
        val committed = Rime.getCommit()
        val allowRaw = rawEngineCommitAllowed
        rawEngineCommitAllowed = false
        if (committed && allowRaw && !composition.active) {
            activeEditorInstance.commitText(Rime.getCommitText(), false)
        } else if (committed) {
            Timber.w("twelvekey: blocked unsolicited engine commit; remaining=%s", composition.remaining)
            restoreTwelveKeyCode(composition.remaining)
        } else {
            val actual = Rime.RimeGetInput().orEmpty()
            when {
                actual.isEmpty() && composition.remaining.isNotEmpty() -> restoreTwelveKeyCode(composition.remaining)
                // 標點/符號不是碼串：不進 12 鍵選擇日誌，等用戶明確點選後直通提交。
                !CompositionInput.isCodeString(actual) -> composition.cancel()
                else -> composition.input(actual)
            }
        }
        PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
        trime.updateComposing()
        committed
    }

    fun hasTwelveKeySession(): Boolean = composition.active

    /** 在引擎處理按鍵前保存預期碼，防止 auto_commit 清空後無法恢復剛按的鍵。 */
    fun beforeTwelveKeyEngineKey(event: IntArray) {
        if (!isTwelveKeyMode() || Rime.isAsciiMode() || event.isEmpty()) return
        if (event.size > 1 && event[1] and Rime.META_RELEASE_ON != 0) return
        onEditorSessionChanged()
        twelveKeyController?.flushPendingRestoreLocked()
        if (Rime.RimeGetInput().isNullOrEmpty() && composition.remaining.isNotEmpty()) {
            restoreTwelveKeyCode(composition.remaining)
        }
        val code = Rime.RimeGetInput().orEmpty()
        val caret = runCatching { Rime.RimeGetCaretPos() }.getOrDefault(code.length)
        val intent = CompositionInput.beforeKey(code, caret, event[0], composition.canUndo)
        rawEngineCommitAllowed = intent.allowRawCommit
        composition.input(intent.code)
        twelveKeyController?.phonetics = composition.phonetics
        PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
        twelveKeyController?.invalidateForEngine()
    }

    /** 軟鍵盤與實體鍵盤共用；回車/空格只選一次，不強制提交尾碼。 */
    fun handleTwelveKeyKey(keyCode: Int): Boolean {
        if (!isTwelveKeyMode() || (!composition.active && !Rime.isComposing())) return false
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            cancelTwelveKeyComposition()
            trime.updateComposing()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            var prevRemaining = ""
            var restoredCode: String? = null
            var shouldUndo = false
            withEngineLock {
                twelveKeyController?.flushPendingRestoreLocked()
                prevRemaining = composition.remaining
                shouldUndo = composition.shouldUndoOnBackspace
                if (shouldUndo) {
                    restoredCode = composition.undo()
                    val rem = restoredCode ?: composition.remaining
                    if (rem != prevRemaining) {
                        // 撤銷了選字（剩餘原碼已變化）：同步還原引擎原碼並使舊緩存失效，
                        // 防止樂觀快照閃出舊尾碼候選，也避免連按退格或按鍵釋放時讀到過期輸入。
                        restoreTwelveKeyCode(rem)
                        twelveKeyController?.invalidateForEngine()
                    } else {
                        // 僅撤銷拼音確認（原碼未變）：保留瞬時緩存/樂觀切換體驗，後台重放約束。
                        twelveKeyController?.scheduleRestore(rem, composition.phonetics)
                    }
                    PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
                    preservePanelReading = true
                }
            }
            if (!shouldUndo) return false
            val rem = restoredCode ?: composition.remaining
            val confirmed = composition.phonetics
            val full = panelWanted || isCandidatePanelShown()
            if (rem != prevRemaining) {
                trime.updateComposing()
                refreshTwelveKey()
                return true
            }
            val instant = twelveKeyController?.lookupCached(rem, confirmed, full)
                ?: TwelveKeySnapshotBuilder.optimisticPhonetic(
                    previous = currentSnapshot,
                    remaining = rem,
                    phonetics = confirmed,
                    appRankingEnabled = prefs.keyboard.candidateAlgorithmEnabled,
                )
            if (instant != null) {
                onTwelveKeySnapshot(instant)
            } else {
                trime.updateComposing()
            }
            refreshTwelveKey()
            return true
        }
        if (keyCode != KeyEvent.KEYCODE_SPACE && keyCode != KeyEvent.KEYCODE_ENTER) return false
        if (composition.remaining.isEmpty() && composition.confirmSelected()) {
            commitCompletedSelection()
            trime.updateComposing()
            refreshTwelveKey()
            return true
        }
        val origin = if (keyCode == KeyEvent.KEYCODE_SPACE) CompositionSession.Origin.SPACE else CompositionSession.Origin.RETURN
        val item = currentSnapshot.barItems.firstOrNull()
        if (item != null) chooseTwelveKey(item, currentSnapshot.contextKey, origin) else refreshTwelveKey()
        return true
    }

    /**
     * 12 鍵與 26 鍵共用的組字期特殊鍵保護：
     * 1) 忽略開頭或連續重複的分詞符 `'`；
     * 2) 若光標前意外存在多個連續 `'`，單次退格一併刪除。
     */
    fun handleComposingSpecialKey(keyCode: Int, metaState: Int): Boolean {
        if (metaState != 0) return false
        if (keyCode == KeyEvent.KEYCODE_APOSTROPHE && (Rime.isComposing() || composition.active)) {
            return withEngineLock {
                if (isTwelveKeyMode()) twelveKeyController?.flushPendingRestoreLocked()
                val input = Rime.RimeGetInput().orEmpty()
                val caret = runCatching { Rime.RimeGetCaretPos() }.getOrDefault(input.length)
                CompositionInput.shouldIgnoreDelimiter(input, caret, allowWhenEmpty = false)
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && Rime.isComposing()) {
            return withEngineLock {
                if (isTwelveKeyMode()) twelveKeyController?.flushPendingRestoreLocked()
                val input = Rime.RimeGetInput().orEmpty()
                val caret = runCatching { Rime.RimeGetCaretPos() }.getOrDefault(input.length)
                val count = CompositionInput.backspaceCount(input, caret)
                if (count <= 1) return@withEngineLock false
                if (isTwelveKeyMode() && !Rime.isAsciiMode()) {
                    onEditorSessionChanged()
                    rawEngineCommitAllowed = false
                    composition.input(CompositionInput.applyBackspace(input, caret))
                    twelveKeyController?.phonetics = composition.phonetics
                    PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
                    twelveKeyController?.invalidateForEngine()
                }
                val backEvent = Event.getRimeEvent(KeyEvent.KEYCODE_DEL, 0)
                repeat(count) {
                    if (!Rime.isComposing()) return@repeat
                    Rime.onKey(backEvent)
                }
                activeEditorInstance.commitRimeText()
                true
            }
        }
        return false
    }

    fun cancelTwelveKeyComposition() {
        // 提交後的收尾也會走這裡；剛上屏 1 秒內的「接續組詞」緩衝要留著，只有真的取消才斷開。
        resetLearnedRunIfStale()
        withEngineLock {
            val hadSession = composition.active || isTwelveKeyMode()
            composition.cancel()
            twelveKeyController?.phonetics = emptyList()
            Rime.setProperty(com.hazukieq.hkrime.core.twelvekey.InitialDisambiguation.PROPERTY, "")
            rawEngineCommitAllowed = false
            PreeditPresentation.invalidate("")
            if (hadSession) Rime.clearComposition()
            twelveKeyController?.invalidateForEngine()
        }
    }

    /**
     * 孤單聯想：點字母行（d/f）後，右列列出該字母下所有可延伸單音節。
     * 只做展示替換，不改輸入。
     */
    private fun onPickIsolatedSyllable(syllable: String) {
        if (syllable.isEmpty()) return
        val chars = runCatching { TwelveKeyPreedit.charsForSyllable(syllable) }.getOrElse { emptyList() }
        val items = ArrayList<CandidatePanelAdapter.Item>(chars.size + 1)
        items.add(
            CandidatePanelAdapter.Item(
                text = "← 返回",
                comment = syllable,
                index = -1,
                segment = ISOLATED_BACK_SEGMENT,
            ),
        )
        chars.forEach { ch ->
            items.add(
                CandidatePanelAdapter.Item(
                    text = ch,
                    comment = syllable,
                    index = -1,
                    segment = ISOLATED_CHAR_SEGMENT,
                    inputSpan = CandidateSelection.bind(
                        CandidateItem(ch, syllable, -1, segment = ISOLATED_CHAR_SEGMENT),
                        currentSnapshot.remaining,
                    ).inputSpan,
                ),
            )
        }
        candidatePanelController?.showDrilldown(items)
    }

    private fun onPickPanelCandidate(item: CandidatePanelAdapter.Item) {
        if (!isTwelveKeyMode()) { pickLegacyPanelCandidate(item); return }
        if (item.contextKey != currentSnapshot.contextKey) { refreshTwelveKey(); return }
        if (item.segment == ISOLATED_SEGMENT) { onPickIsolatedSyllable(item.text); return }
        if (item.segment == ISOLATED_BACK_SEGMENT) { candidatePanelController?.restoreGrid(); return }
        val fallback = currentSnapshot.fallback
        if (fallback != null && item.index < 0 && item.segment == -1 && item.text == fallback.letters) {
            chooseTwelveKey(CandidateSelection.fallback(item.text, currentSnapshot.remaining),
                item.contextKey, CompositionSession.Origin.FALLBACK)
            return
        }
        chooseTwelveKey(
            CandidateItem(item.text, item.comment, item.index, item.segment, item.restCode, item.inputSpan, item.engineSpan),
            item.contextKey,
            if (item.segment == ISOLATED_CHAR_SEGMENT) CompositionSession.Origin.ISOLATED else CompositionSession.Origin.PANEL,
        )
    }

    /** 更新「按段選字」的關聯度錨點（前一個上屏的字）；變化時讓快照緩存失效。 */
    private fun setSegmentAnchor(anchor: Char?) {
        val controller = twelveKeyController ?: return
        if (controller.segmentAnchor == anchor) return
        controller.segmentAnchor = anchor
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
            "caretProbe snapshot input=[%s] consumedEnd=%s remaining=[%s] initial=[%s] groups=%s",
            snapshot?.input,
            snapshot?.consumedEnd,
            snapshot?.remaining,
            snapshot?.initial?.toString().orEmpty(),
            snapshot?.groups?.joinToString("|") { "${it.reading}:${it.size}" },
        )
    }

    /** 候選刷新後呼叫：12 鍵重建快照；其它方案只刷新展開面板。 */
    fun onCandidatesRefreshed() {
        val refreshT0 = System.nanoTime()
        try {
            doOnCandidatesRefreshed()
        } finally {
            val ms = (System.nanoTime() - refreshT0) / 1e6
            if (ms > 40.0) Timber.w("perf: onCandidatesRefreshed %.1fms", ms)
        }
    }

    private fun doOnCandidatesRefreshed() {
        if (!Rime.isComposing() && !composition.active) setSegmentAnchor(null)
        if (isTwelveKeyMode()) {
            // 按鍵後立即重建快照（不 debounce）；面板關閉時固定枚舉 64 條，
            // 面板打開才全量枚舉（按鍵卡頓的另一半原因：每鍵 600 條枚舉）。
            if (suppressSnapshotRefresh) {
                // 這次刷新是「快照落地後重畫 preedit」帶來的，當前快照已是最新，不必重建。
                suppressSnapshotRefresh = false
                return
            }
            refreshTwelveKey()
            return
        }
        candidateBarBinder?.clear()
        if (!isCandidatePanelShown()) return
        if (!Rime.isComposing()) {
            hideCandidatePanel()
            return
        }
        candidatePanelController?.submit(legacySnapshot())
    }

    // ---------- 展開面板：非 12 鍵方案（全字母 26 鍵等） ----------

    /**
     * 非客語 12 鍵方案（如 26 鍵 rime_ice、朙月拼音、五筆、筆畫、其它方言/語言）的展開面板快照：
     * - 絕不混入客語 12 鍵詞庫或客語音節分組；
     * - 始終保留左列：優先按引擎 comment 分組，無 comment 時按候選覆蓋的 preedit 音節/編碼跨度分組。
     */
    private fun legacySnapshot(
        maxCandidates: Int = TwelveKeySnapshotBuilder.MAX_CANDIDATES,
    ): TwelveKeySnapshot = withEngineLock {
        runCatching {
            Rime.setProperty(com.hazukieq.hkrime.core.twelvekey.InitialDisambiguation.PROPERTY, "")
            val local = TwelveKeySnapshotBuilder.local()
            if (local.isEmpty) return@runCatching TwelveKeySnapshot.EMPTY
            com.hazukieq.hkrime.core.twelvekey.GenericPanelSnapshotBuilder.build(
                local = local,
                maxCandidates = maxCandidates,
            )
        }.getOrDefault(TwelveKeySnapshot.EMPTY)
    }

    /**
     * Cancels all coroutines and cleans up.
     */
    override fun onDestroy() {
        cancelTwelveKeyComposition()
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
        if (!restarting) cancelTwelveKeyComposition()
        hideCandidatePanel()
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
                            tempAsciiMode = true
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
        // 键盘的reset_ascii_mode指定了重置时是否重置到keyboard的ascii_mode描述的状态；
        // 但输入框已明确要求强制 ASCII（数字/验证码/电话/密码）时，不得被重置回中文态。
        if (tempAsciiMode != true && shouldResetAsciiMode && keyboardSwitcher.currentKeyboard.isResetAsciiMode) {
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
                if (value && composition.active) cancelTwelveKeyComposition()
                trime.inputFeedbackManager.ttsLanguage =
                    locales[if (value) 1 else 0]
            }
            "_hide_comment" -> trime.setShowComment(!value)
            "_hide_candidate" -> {
                candidateRoot?.visibility = if (!value) View.VISIBLE else View.GONE
                trime.setCandidatesViewShown(isComposable && !value)
            }
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

            }
        }
        mainKeyboardView?.invalidateAllKeys()

        // 方案開關改變 filter 輸出，使快照緩存失效並刷新。
        twelveKeyController?.invalidateForOptions()
        PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
        if (isTwelveKeyMode()) {
            if (Rime.isComposing()) refreshTwelveKey()
        } else if (isCandidatePanelShown()) {
            candidatePanelController?.submit(legacySnapshot())
        }
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
            // 按鍵釋放也會處理引擎與上下文，必須和後台枚舉共用引擎鎖。
            withEngineLock {
                twelveKeyController?.flushPendingRestoreLocked()
                if (shouldUpdateRimeOption) {
                    Rime.setOption("soft_cursors", prefs.keyboard.softCursorEnabled)
                    Rime.setOption("_horizontal", trime.imeConfig.getBoolean("horizontal"))
                    shouldUpdateRimeOption = false
                }
                // todo 释放按键可能不对
                val handled = Rime.onKey(Event.getRimeEvent(keyEventCode, Rime.META_RELEASE_ON))
                if (handled || !isTwelveKeyMode()) {
                    activeEditorInstance.commitRimeText()
                }
            }
        }
        Timber.d("\t<TrimeInput>\tonRelease() finish")
    }

    // KeyboardEvent 处理软键盘事件
    /** 最近一次按鍵的時間戳（ns）：用來量「按鍵 → 候選條落地」的延遲。 */
    private var lastKeyAtNs = 0L


    /** 最近一次發佈的快照：候選條、面板、preedit 預覽、空格上屏全部只讀這一份。 */
    private var currentSnapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY

    /**
     * 抑制「下一次候選刷新」：快照落地後重畫 preedit 會走 `updateComposing()`，
     * 而它內部會調 [onCandidatesRefreshed] 再刷一次快照——那時快照已經是最新的，純屬重複。
     */
    private var suppressSnapshotRefresh = false

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
                if (event.toggle == "ascii_mode" && !Rime.isAsciiMode() && composition.active) {
                    cancelTwelveKeyComposition()
                }
                Rime.toggleOption(event.toggle)
                activeEditorInstance.commitRimeText()
            }
            KeyEvent.KEYCODE_EISU -> { // Switch keyboard
                if (EditPanelController.isEditSelect(event.select)) {
                    if (composition.active) cancelTwelveKeyComposition()
                    hideCandidatePanel()
                    editPanelController?.show()
                    return
                }
                val symbolTab = SymbolPanelController.mapSelectToInitialTab(event.select)
                if (symbolTab != null) {
                    if (composition.active) cancelTwelveKeyComposition()
                    hideCandidatePanel()
                    symbolPanelController?.show(symbolTab)
                    return
                }
                symbolPanelController?.hide()
                editPanelController?.hide()
                keyboardSwitcher.switchToKeyboard(event.select)
                if (!isTwelveKeyMode() && composition.active) {
                    cancelTwelveKeyComposition()
                }
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
                if (event.command == "paste_by_char") {
                    trime.pasteByChar()
                } else if (event.command == "candidate_panel") {
                    // 12 鍵 /、：在組字中切換候選面板，主題對應 Panel_toggle 事件。
                    toggleCandidatePanel()
                } else if (event.command == "symbol_panel") {
                    if (composition.active) cancelTwelveKeyComposition()
                    hideCandidatePanel()
                    symbolPanelController?.show(event.option)
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
            // 長按 meta/Menu 顯示方案切換和啟用/停用選單。
            KeyEvent.KEYCODE_MENU -> trime.showSchemaMenuDialog()
            else -> onKey(event.code, event.mask or trime.keyboardSwitcher.currentKeyboard.modifer)
        }
    }

    override fun onKey(keyEventCode: Int, metaState: Int) {
        printModifierKeyState(metaState, "keyEventCode=" + keyEventCode)
        if (trime.handleKey(keyEventCode, metaState)) return
        if (metaState == 0 && keyEventCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            needSendUpRimeKey = false
            activeEditorInstance.commitText(('0' + (keyEventCode - KeyEvent.KEYCODE_0)).toString(), false)
            return
        }
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
        if (isTwelveKeyMode()) twelveKeyController?.flushPendingRestoreLocked()
        if (!text.startsWithAsciiChar() && Rime.isComposing() && !(isTwelveKeyMode() && !Rime.isAsciiMode())) {
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
                    if (target.isNotEmpty() && target.all { it == '\'' } && (Rime.isComposing() || composition.active)) {
                        val input = Rime.RimeGetInput().orEmpty()
                        val caret = runCatching { Rime.RimeGetCaretPos() }.getOrDefault(input.length)
                        if (CompositionInput.shouldIgnoreDelimiter(input, caret, allowWhenEmpty = false)) {
                            textToParse = textToParse.substring(target.length)
                            continue
                        }
                        val consumedLen = target.length
                        target = "'"
                        if (isTwelveKeyMode() && !Rime.isAsciiMode()) {
                            onEditorSessionChanged()
                            val intent = CompositionInput.beforeKey(input, caret, '\''.code, composition.canUndo)
                            rawEngineCommitAllowed = false
                            composition.input(intent.code)
                            twelveKeyController?.phonetics = composition.phonetics
                            PreeditPresentation.invalidate(composition.selectedText, composition.phonetics)
                            twelveKeyController?.invalidateForEngine()
                        }
                        Rime.onText(target)
                        if (!activeEditorInstance.commitRimeText() && !Rime.isComposing()) {
                            activeEditorInstance.commitText(target)
                        }
                        trime.updateComposing()
                        textToParse = textToParse.substring(consumedLen)
                        continue
                    }
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
        if (isTwelveKeyMode() && (Rime.isComposing() || composition.active ||
                currentSnapshot.hasCandidates || currentSnapshot.fallback != null)) {
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
        val snapshot = currentSnapshot
        val item = snapshot.barItems.getOrNull(index)
        if (item != null) {
            chooseTwelveKey(item, snapshot.contextKey, CompositionSession.Origin.BAR)
        } else if (index == snapshot.barItems.size && snapshot.fallback != null) {
            chooseTwelveKey(
                CandidateSelection.fallback(snapshot.fallback.letters, snapshot.remaining),
                snapshot.contextKey, CompositionSession.Origin.FALLBACK,
            )
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
        // 12 鍵候選使用全局下標，當前禁用長按刪詞，避免以頁內位置誤刪。
        if (isTwelveKeyMode()) return
        Rime.deleteCandidate(index)
        trime.updateComposing()
    }
}
