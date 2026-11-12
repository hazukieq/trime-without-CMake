package com.hazukieq.hkrime.ime.text

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.TwelveKeyPreedit
import com.hazukieq.hkrime.core.twelvekey.CandidateBarBinder
import com.hazukieq.hkrime.core.twelvekey.CandidateItem
import com.hazukieq.hkrime.core.twelvekey.Fallback
import com.hazukieq.hkrime.core.twelvekey.PhraseMemory
import com.hazukieq.hkrime.core.twelvekey.SYNTHETIC_SEGMENT
import com.hazukieq.hkrime.core.twelvekey.CandidatePanelController
import com.hazukieq.hkrime.core.twelvekey.TwelveKeyController
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshot
import com.hazukieq.hkrime.core.twelvekey.TwelveKeySnapshotBuilder
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.ime.enums.InlineModeType
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


    var candidateRoot: android.view.View? = null
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
            // 固定候選欄不再顯示拼音提示（用戶 2026-11-09「关闭固定候选栏的拼音提示」；
            // 拼音只用於展開面板左列分組，所以候選條高度對所有方案都是 candidate_view_height）。
            it.setShowComment(false)
            it.reset(trime)
        }

        // 候選展開面板（所有方案都只讀快照：12 鍵由引擎按鍵驅動，全字母方案開面板時現算）
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
            backspaceView = uiBinding.main.panelBackspace,
        ).also { controller ->
            controller.onPickCandidate = { item -> onPickPanelCandidate(item) }
            controller.onHideKeyboard = { trime.requestHideSelf(0) }
            // 面板右下的退格鍵：與鍵盤退格走同一條鏈路（引擎刪除 + 通知刷新面板）
            controller.onBackspace = { onKey(KeyEvent.KEYCODE_DEL, 0) }
        }
        candidateBarBinder = CandidateBarBinder(candidateView)
        twelveKeyController = TwelveKeyController(
            scope = this,
            onSnapshot = { snapshot -> onTwelveKeySnapshot(snapshot) },
            // 12 鍵的 preedit 由 TwelveKeyPreedit 改寫，長度與 librime 的 sel_start/sel_end 不一致，
            // Composition.setWindow() 會在 updateComposing() 內拋 StringIndexOutOfBoundsException。
            // 這裡保證：commit buffer 先落地；updateComposing 失敗只記日誌，進程不死，快照照常重建。
            onEngineChanged = {
                // 引擎寫過（選字/上屏/重建）→ 之前緩存的候選快照可能已過期
                twelveKeyController?.invalidateForEngine()
                val committed = runCatching { Rime.getCommit() }.getOrDefault(false)
                if (committed) {
                    val text = drainPendingPick() + foldUncommittedPrefix(Rime.getCommitText())
                    Timber.i("twelvekey: commit buffer=[%s]", text)
                    runCatching { activeEditorInstance.commitText(text, false) }
                        .onFailure { Timber.e(it, "twelvekey: commitText failed") }
                    maybeRecordPhrase()
                } else {
                    // 多字詞被選中：buffer 空、但 preedit 前綴已有字，先記住，下一次提交一起上屏。
                    uncommittedPrefix = compositionPrefix()
                    Timber.i("twelvekey: no commit buffer after select, prefix=[%s]", uncommittedPrefix)
                }
                runCatching { trime.updateComposing() }
                    .onFailure { Timber.e(it, "twelvekey: updateComposing failed") }
                refreshTwelveKey()
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

    fun isTwelveKeyMode(): Boolean = runCatching {
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
        val item = currentSnapshot.barItems.firstOrNull()
            ?: return false
        if (holdPartialPick(item.text, item.comment, engineIndex = item.index)) return true
        withEngineLock { twelveKeyController?.selectCandidate(item.index, item.comment) }
        return true
    }

    /**
     * 12 鍵預編輯預覽文字：取「固定候選欄第一條」，讓嵌入式預覽與候選欄顯示同一個字。
     *
     * 引擎的 commit_text_preview 是引擎順序第一條；12 鍵候選條會被 SelectionMemory
     * 重排（用戶常用字優先），兩者不一致時會出現「候選欄是怕、預覽是啥」的錯位。
     */
    fun currentPreviewText(): String? {
        if (!isTwelveKeyMode() || !Rime.isComposing()) return null
        return currentSnapshot.inlineText.takeIf { it.isNotEmpty() }
    }

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
     * 12 鍵統一刷新入口：面板「想開著」時必須帶分組（full=true）。
     *
     * 之前只有 onCandidatesRefreshed 判斷 full，選字/上屏後還有幾處 `refresh(immediate = true)`
     * 會用 full=false 再刷一次，把面板的分組覆蓋成空 → 面板只剩 `{we} … 本地原文`，
     * 關掉再開才恢復（用戶 2026-11-10 反饋）。
     */
    private fun refreshTwelveKey() {
        // 暫存的字不能漏：引擎這邊已經沒有組合了（例如用戶按了回車/焦點切走），
        // 就立刻把它上屏，否則它會殘留在 preedit 裡串到下一次輸入。
        if (pendingPick.isNotEmpty() && runCatching { !Rime.isComposing() }.getOrDefault(false)) {
            flushPendingPick()
            return
        }
        twelveKeyController?.refresh(
            immediate = true,
            full = panelWanted || isCandidatePanelShown(),
        )
    }

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
        panelWanted = true
        if (isTwelveKeyMode()) {
            if (twelveKeyController?.snapshot?.isEmpty != false) twelveKeyController?.refreshSync()
            val snapshot = twelveKeyController?.snapshot
            if (snapshot == null || (!snapshot.hasCandidates && snapshot.fallback == null)) return
            candidatePanelController?.show()
            // 面板要完整分組：開面板時全量枚舉一次
            twelveKeyController?.refresh(immediate = true, full = true)
        } else {
            // 非 12 鍵方案（客語 26 鍵等「全字母」）：與 12 鍵同一套快照/分組/面板，
            // 只是快照由 App 在開面板這一刻現算（引擎自己不發 12 鍵那種通知）。
            // 之前這條路徑沒人餵內容、show() 又直接 return，導致按 ▼ 完全打不開
            // （用戶 2026-11-09）。
            val snapshot = legacySnapshot()
            if (snapshot.isEmpty) return
            candidatePanelController?.submit(snapshot)
            if (candidatePanelController?.isShown != true) candidatePanelController?.show()
        }
        candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
    }

    fun hideCandidatePanel() {
        panelWanted = false
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
        if (snapshot.isEmpty) {
            // 新一輪輸入：上一輪的逐段累積作廢（上屏時已經記過了）
            pickedText.setLength(0)
            pickedReadings.setLength(0)
            pickedCode = ""
            pickedMissingReading = false
            pickedPieces = 0
            // 本輪結束：露過面但沒被選的合成候選，各記一次曝光（弱負信號）
            flushPhraseExposure()
        } else {
            collectPhraseExposure(snapshot)
        }
        currentSnapshot = snapshot
        // preedit 讀音對齊（docs/14）：把「用戶看到的第一條候選」發布給 TwelveKeyPreedit，
        // 因為 getComposition() 裡無法知道候選條順序（那是 App 排的，不是引擎 menu 順序）。
        // 讀音只取「候選條第一條」的 comment（所見即所得）；首條覆蓋不到全部碼位時，
        // 由 TwelveKeyPreedit.rewrite() 用本地解碼把尾巴補上，保證按下的鍵全部顯示。
        val first = snapshot.barItems.firstOrNull()
        val preferredChanged = TwelveKeyPreedit.preferredText != first?.text ||
            TwelveKeyPreedit.preferredComment != first?.comment
        TwelveKeyPreedit.preferredText = first?.text
        TwelveKeyPreedit.preferredComment = first?.comment
        candidateBarBinder?.bind(snapshot)
        candidatePanelController?.submit(snapshot)
        applyInlinePreview(snapshot)
        if (snapshot.isEmpty || (!snapshot.hasCandidates && snapshot.fallback == null)) {
            if (candidatePanelController?.isShown == true) candidatePanelController?.hide()
        }
        // 面板可能被 submit() 自動收起（上屏後無剩餘碼），箭頭方向要跟著回到 ▼
        candidateView?.setPanelOpen(candidatePanelController?.isShown == true)
        // 首條變化要重畫；另外「上一幀連讀音都拿不出、直接回了原始碼」時也要補畫一次 ——
        // 用戶 2026-11-11 報 `ee5` 先顯示原碼、開關面板後才顯示 `de`：那一幀候選還沒落地，
        // 快照到了卻因為「首條沒變」跳過重畫，畫面就停在原碼上。
        val needRedraw = preferredChanged ||
            (TwelveKeyPreedit.lastRenderUsedRawCode && first?.comment.isNullOrEmpty().not())
        if (needRedraw && Rime.isComposing()) refreshPreeditAfterSnapshot()
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

    /**
     * 12 鍵 preedit 預覽與候選條同幀落地：快照一到就把第一條寫進 preedit。
     * 只在「嵌入式編輯模式 = 首選」時接管；其他模式仍由 EditorInstance 按各自來源更新。
     */
    private fun applyInlinePreview(snapshot: TwelveKeySnapshot) {
        if (!isTwelveKeyMode()) return
        if (prefs.keyboard.inlinePreedit != InlineModeType.INLINE_PREVIEW) return
        val ic = activeEditorInstance.inputConnection ?: return
        val text = if (snapshot.isEmpty || !Rime.isComposing()) "" else snapshot.inlineText
        runCatching { ic.setComposingText(text, 1) }
            .onFailure { Timber.e(it, "twelvekey: inline preview failed") }
    }


    /**
     * 聯想詞（`song g**` 這類）：整個詞直接上屏。
     *
     * 這些詞的碼不是用戶打出來的（是 App 依「第一段音節 + 下一個音節首字母」合成的），
     * 引擎沒有對應 context，所以只能整詞 commit，再把組字清掉。
     */
    private fun pickAssociationWord(text: String, reading: String = "") {
        if (text.isEmpty()) return
        // 先記下當前完整碼串：清組合後 input 就沒了，學習的 key 必須是「這次打的碼」。
        val code = withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        // 詞只覆蓋輸入前幾個音節時由 holdPartialPick 攔掉（留在 preedit）；
        // 這裡只處理「整串被這個詞覆蓋」的上屏。
        if (holdPartialPick(text, reading, code)) return
        runCatching {
            withEngineLock { Rime.clearComposition() }
            activeEditorInstance.commitText(text, false)
            setSegmentAnchor(text.lastOrNull())
            trime.updateComposing()
            refreshTwelveKey()
            // 面板聯想詞不走 librime select_candidate，引擎學不到；記進 App 學習，
            // 重複點選才能把詞排到候選條/推薦前面（用戶 2026-11-11 報「點 3 次也不動」）。
            if (code.isNotEmpty()) PhraseMemory.learnPick(code, text, reading)
        }.onFailure { Timber.e(it, "twelvekey: association pick failed") }
    }

    /**
     * 點**引擎候選**（候選條裡的詞）時也記一次 App 學習。
     *
     * librime 自己會學 userdb，但 App 的推薦/候選排序用的是 App 學習分數；
     * 用戶 2026-11-11：同一條詞點了 3 次以上，排位仍不動——所以兩套學習都記。
     * 單字不記（`learnPick` 只收 ≥2 字）。
     */
    private fun learnCandidatePick(text: String, reading: String) {
        if (text.length < 2) return
        val code = withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        if (code.isNotEmpty()) PhraseMemory.learnPick(code, text, reading)
    }

    /** 記下本輪真展示過的合成候選（候選條必算；展開面板打開時也算）。 */
    private fun collectPhraseExposure(snapshot: TwelveKeySnapshot) {
        snapshot.barItems.forEach { item ->
            if (item.segment == SYNTHETIC_SEGMENT && item.text.isNotEmpty()) {
                shownSynthetic[item.text] = item.restCode
            }
        }
        if (panelWanted || isCandidatePanelShown()) {
            snapshot.groups.forEach { group ->
                group.items.forEach { item ->
                    if (item.segment == SYNTHETIC_SEGMENT && item.text.isNotEmpty()) {
                        shownSynthetic[item.text] = item.restCode
                    }
                }
            }
        }
    }

    /** 本輪結束：露過面但沒被點選的合成候選，各 +1 曝光。 */
    private fun flushPhraseExposure() {
        val shown = shownSynthetic
        if (shown.isEmpty()) {
            pickedSynthetic.clear()
            return
        }
        val picked = pickedSynthetic
        shown.forEach { (text, code) ->
            if (!picked.contains(text) && code.isNotEmpty()) PhraseMemory.noteShown(code, text)
        }
        shown.clear()
        picked.clear()
    }

    /**
     * 點合成候選（App 側學到的整串詞，[SYNTHETIC_SEGMENT]）：整串一次上屏。
     *
     * 它不來自引擎，所以不能走 `select_candidate`；完整碼串放在 `restCode` 裡。
     * 上屏前先把暫存字 flush（保證「之前逐段選的字 + 這個詞」順序正確），再記一次學習。
     */
    /**
     * 詞只覆蓋輸入前幾個音節（`灵机一动 | 来的`）時：**不 commit**，先把詞暫存到
     * `pendingPick`，再把剩餘碼餵回引擎。
     *
     * 這樣 preedit = 暫存前綴 + 剩餘段（`灵机一动` + `loi dit`），全部選完才一起上屏。
     * 用戶 2026-11-12 明確要求：剩餘碼沒選完時只能停在 preedit；librime 的
     * `select_candidate` 遇到 `end == 輸入長度` 的候選會直接 commit 整串，不能走它。
     */
    private fun holdPartialPick(
        text: String,
        reading: String,
        input: String = "",
        engineIndex: Int = -1,
    ): Boolean {
        if (text.isEmpty()) return false
        val code = input.ifEmpty {
            withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        }
        val remaining = TwelveKeyPreedit.remainingCodeAfter(reading, code)
        if (remaining.isEmpty()) return false
        // 學習要在清組合前記（key 是這次完整碼串）。
        if (engineIndex >= 0) learnCandidatePick(text, reading) else if (code.isNotEmpty()) {
            PhraseMemory.learnPick(code, text, reading)
        }
        pendingPick.append(text)
        updatePendingPick()
        setSegmentAnchor(text.lastOrNull())
        withEngineLock { Rime.clearComposition() }
        withEngineLock { Rime.onText(remaining) }
        trime.updateComposing()
        refreshTwelveKey()
        // 組合剛重建，先再刷一次 preedit，避免悬浮窗拿到清組合前的殘留字串。
        trime.updateComposing()
        com.hazukieq.hkrime.util.DiagLog.d(
            "pick",
            "hold text=$text reading='$reading' code=$code remaining=$remaining preedit='${Rime.getCompositionText()}'",
        )
        return true
    }

    private fun commitPhrase(item: CandidateItem) {
        val code = item.restCode
        val text = item.text
        if (text.isEmpty()) return
        pickedSynthetic.add(text)
        // 引擎裡可能已有「選中未提交」的前綴（先逐段選了幾個字），它不在 pendingPick 裡：
        // 先取出來併進上屏文字，否則 clearComposition 會把它丟掉。
        val enginePrefix = uncommittedPrefix.ifEmpty { withEngineLock { compositionPrefix() } }
        uncommittedPrefix = ""
        // 合成候選只覆蓋輸入前幾個音節時，剩餘碼要在清組合後重新餵回引擎
        // （同 pickAssociationWord；用戶 2026-11-11：灵机一动 | 来的 後半丟失）。
        val input = withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        val remaining = TwelveKeyPreedit.remainingCodeAfter(item.comment, input)
        com.hazukieq.hkrime.util.DiagLog.d(
            "phrase",
            "input=$input comment=${item.comment} remaining=$remaining rest=${item.restCode}",
        )
        if (remaining.isNotEmpty()) {
            // 剩餘碼沒選完：詞留在 preedit，不能上屏（用戶 2026-11-12）。
            pendingPick.append(enginePrefix).append(text)
            updatePendingPick()
            setSegmentAnchor(text.lastOrNull())
            withEngineLock { Rime.clearComposition() }
            withEngineLock { Rime.onText(remaining) }
            if (code.isNotEmpty()) PhraseMemory.bumpPicked(code, text)
            trime.updateComposing()
            refreshTwelveKey()
            return
        }
        flushPendingPick()
        runCatching { activeEditorInstance.commitText(enginePrefix + text, false) }
            .onFailure { Timber.e(it, "twelvekey: phrase commit failed") }
        if (code.isNotEmpty()) PhraseMemory.bumpPicked(code, text)
        withEngineLock { Rime.clearComposition() }
        if (remaining.isNotEmpty()) {
            withEngineLock { runCatching { Rime.onText(remaining) } }
        }
        setSegmentAnchor(text.lastOrNull())
        trime.updateComposing()
        refreshTwelveKey()
    }

    /** 記下「這一段選了哪個字、什麼讀音」；整串上屏時一併寫進 PhraseMemory。 */
    private fun notePickedSegment(text: String, reading: String) {
        if (text.isEmpty()) return
        val input = withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        // 只認**第一次**選段時的完整碼串：選完一段後 librime 的 input 只剩剩下的碼，
        // 用它當 key 會把「以液難」記到只剩尾巴的碼串上（下次打同樣的碼就查不到）。
        val code = PhraseMemory.normalizeCode(input)
        if (pickedCode.isEmpty() && code.isNotEmpty()) pickedCode = code
        pickedPieces += 1
        pickedText.append(text)
        val r = reading.trim()
        pickedReadings.append(r).append(' ')
        if (r.isEmpty()) pickedMissingReading = true
    }

    /**
     * 逐段拼出的整串上屏後記一條「碼串 → 詞」。
     *
     * 只記「用戶自己一段段選出來的」多字串：引擎能一次裝出來的詞它自己會進 userdb，這裡再記是重複。
     */
    private fun maybeRecordPhrase() {
        val text = pickedText.toString()
        val readings = pickedReadings.toString().trim()
        val code = pickedCode
        val missing = pickedMissingReading
        val pieces = pickedPieces
        pickedText.setLength(0)
        pickedReadings.setLength(0)
        pickedCode = ""
        pickedMissingReading = false
        pickedPieces = 0
        // 只記「逐段拼出來」的（≥2 次選）：一次選中的詞是引擎自己會學的，App 再記一份是重複。
        if (text.length < 2 || code.isEmpty() || missing || pieces < 2) return
        PhraseMemory.record(code, text, readings)
        Timber.i("twelvekey: phrase record code=[%s] text=[%s] readings=[%s]", code, text, readings)
    }

    private fun onPickPanelCandidate(item: CandidatePanelAdapter.Item) {
        if (!isTwelveKeyMode()) {
            pickLegacyPanelCandidate(item)
            return
        }
        if (item.segment == 1) {
            // 引擎詞組（`灵机一动` 這種多音節詞在面板裡也有下標）：必須走 librime
            // 原生 select_candidate，選中不提交、剩餘輸入（`来的`）才會保留。
            // 只有 App 側合成的聯想詞/學習詞（index < 0）才整詞上屏。
            if (item.index >= 0) {
                // 只覆蓋前幾音節的詞：留在 preedit（不走 select_candidate，否則 auto_commit 會整串上屏）。
                if (holdPartialPick(item.text, item.comment, engineIndex = item.index)) return
                // 不要 notePickedSegment：這裡是引擎原生選中，引擎會自己管 commit buffer；
                // 再往 pendingPick 塞一份會在最終上屏時重複。
                learnCandidatePick(item.text, item.comment)
                twelveKeyController?.selectCandidate(item.index, item.comment)
                return
            }
            pickAssociationWord(item.text, item.comment)
            return
        }
        if (item.segment == SYNTHETIC_SEGMENT) {
            commitPhrase(
                CandidateItem(
                    text = item.text,
                    comment = item.comment,
                    index = -1,
                    segment = SYNTHETIC_SEGMENT,
                    restCode = item.restCode,
                ),
            )
            return
        }
        if (item.segment >= 0) {
            notePickedSegment(item.text, item.comment)
            // 段組字：優先解析成引擎候選下標，走 librime 原生 select_candidate ——
            // 引擎自己會「選中不提交」，preedit 保留、全部選完才上屏、退格可反悔（用戶 2026-11-10 要求）。
            // 引擎確實沒有這個字（字表有、詞庫沒收）時才退回 App 側兜底。
            val engineIndex = resolveEngineIndexForSegment(item.text, item.comment)
            if (engineIndex >= 0) {
                learnCandidatePick(item.text, item.comment)
                twelveKeyController?.selectCandidate(engineIndex, item.comment)
                return
            }
            // 引擎沒這個字，最常見的原因是「輸入還沒分段」：`88` 被當成一個詞，候選只有
            // 醫院/這種…，單字（以/夜/就…）根本不在裡面。先按段插 `'` 重餵引擎再試一次：
            // 命中就交給引擎「選中不提交」，逐段選字全程留在 preedit（用戶 2026-11-11：
            // 「用户都没选完时不应该只在preedit 吗？怎么能突然commit」）。
            if (resegmentAndSelect(item)) return
            // 引擎重分段後仍然沒有這個字（詞庫確實沒收）：退回 App 側兜底（會逐段上屏）。
            pickSegmentedCandidate(item.text, item.restCode)
            return
        }
        if (item.index < 0) {
            // fallback：本地原文上屏
            twelveKeyController?.snapshot?.fallback?.let { twelveKeyController?.commitFallback(it) }
            return
        }
        notePickedSegment(item.text, item.comment)
        twelveKeyController?.selectCandidate(item.index, item.comment)
    }

    /**
     * 把「段組裡的一個字」解析成 librime 的**全局候選下標**。
     *
     * 命中就交給引擎原生 `select_candidate`：選中不提交、preedit 保留已選段、全部選完才上屏，
     * 退格由引擎自己反悔，學習也走 librime —— 不需要 App 在旁邊維護一份「待上屏的字」。
     * 只有引擎候選裡確實沒有這個字（字典有、詞庫沒收）時返回 -1，調用方再退回 App 側兜底。
     */
    private fun resolveEngineIndexForSegment(text: String, syllable: String): Int {
        if (text.isEmpty()) return -1
        val rows = withEngineLock {
            runCatching { Rime.candidatesSnapshot(TwelveKeySnapshotBuilder.MAX_CANDIDATES) }.getOrNull()
        } ?: return -1
        var looseMatch = -1
        for (i in rows.indices) {
            val row = rows[i] ?: continue
            if ((row.getOrNull(0) ?: "") != text) continue
            if (looseMatch < 0) looseMatch = i
            val comment = row.getOrNull(1)?.trim().orEmpty()
            if (syllable.isNotEmpty() && (comment == syllable || comment.startsWith(syllable))) {
                return i
            }
        }
        return looseMatch
    }

    /**
     * 把「還沒分段的輸入」按段插 `'` 重新餵給引擎，再把這個字交給引擎選中。
     *
     * 為什麼需要：librime 對 `88` 這種未分段輸入只給「組詞」候選，單字（以/夜/就…）不在候選裡，
     * 於是面板段組點擊會落到 App 兜底——兜底是「上屏這個字 + 其餘碼重餵」，用戶會看到字先上屏了。
     * 插 `'` 後引擎按段給單字候選，命中就能走原生 `select_candidate`：選中不提交、全部選完才上屏、
     * 退格由引擎反悔、學習也走 librime。
     *
     * 返回 true = 已經由引擎選中（調用方直接 return）；false = 沒命中，調用方退回兜底。
     */
    private fun resegmentAndSelect(item: CandidatePanelAdapter.Item): Boolean {
        val input = withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        if (input.isEmpty() || input.contains('\'')) return false
        val rest = item.restCode
        if (rest.isEmpty() || input.length <= rest.length || !input.endsWith(rest)) return false
        val head = input.dropLast(rest.length)
        if (head.isEmpty()) return false
        val segmented = head + "'" + rest
        return runCatching {
            withEngineLock { Rime.clearComposition() }
            withEngineLock { Rime.onText(segmented) }
            val engineIndex = resolveEngineIndexForSegment(item.text, item.comment)
            if (engineIndex < 0) {
                // 沒命中：把輸入還原成沒分段的形式，讓後續兜底走原路徑。
                withEngineLock { Rime.clearComposition() }
                withEngineLock { Rime.onText(input) }
                false
            } else {
                twelveKeyController?.selectCandidate(engineIndex, item.comment)
                true
            }
        }.getOrElse {
            Timber.e(it, "twelvekey: resegment failed")
            false
        }
    }

    /**
     * 分詞（`'`）後的逐段選字：上屏這一段的字，然後把剩下的段重新餵回引擎，
     * 讓下一段繼續出候選。只處理第一個待選段（分詞是逐段進行的）。
     */
    private fun pickSegmentedCandidate(text: String, restCode: String = "") {
        val input = withEngineLock { runCatching { Rime.RimeGetInput() }.getOrNull() }.orEmpty()
        // restCode 由快照算好（多音節但沒按分詞鍵的情況，碼串裡沒有 `'` 可以切）。
        val rest: List<String> = if (restCode.isNotEmpty()) {
            listOf(restCode)
        } else {
            val segments = input.split('\'').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return
            segments.drop(1)
        }
        runCatching {
            // 先把「已選中但未提交」的前綴（如「江蘇」）並進來，再清組合重建，
            // 否則 clearComposition 會把它丟掉（用戶 2026-11-10 報的問題）。
            // 前綴已在 onEngineChanged 記住時優先用它（那是 librime 真正選中的段），
            // 用完立刻清空，避免下一次（尤其是走引擎選詞那條路）被重複拼一次。
            val remembered = uncommittedPrefix
            uncommittedPrefix = ""
            val prefix = remembered.ifEmpty { withEngineLock { compositionPrefix() } }
            // 用戶 2026-11-11：兜底**不能**立刻上屏（「用戶都沒選完時不應該只在 preedit 嗎？
            // 怎麼能突然 commit」）。選中的字先暫存到 pendingPick，由 Rime.getComposition()
            // 拼在 preedit 前面；整串選完（rest 空）才一次性 commit。
            pendingPick.append(prefix).append(text)
            updatePendingPick()
            withEngineLock { Rime.clearComposition() }
            // 記住剛上屏的字：下一段的候選條按「與它的關聯度」排序（區 → 域）。
            setSegmentAnchor(text.firstOrNull())
            if (rest.isNotEmpty()) {
                withEngineLock { Rime.onText(rest.joinToString("'")) }
                trime.updateComposing()
                refreshTwelveKey()
            } else {
                // 最後一段也選完了 → 現在才上屏（含前面暫存的字）。
                flushPendingPick()
            }
        }.onFailure { Timber.e(it, "twelvekey: segmented pick failed") }
    }

    /** 把 App 側暫存前綴同步給 preedit 顯示（Rime.getComposition() 會拼在最前面）。 */
    private fun updatePendingPick() {
        TwelveKeyPreedit.pendingPrefix = pendingPick.toString()
    }

    /**
     * 把暫存的字一次性上屏。
     *
     * 只在「最後一段也選完」或「引擎自己 commit」時調用 —— 用戶 2026-11-11 的要求：
     * 逐段選字時字只能待在 preedit，全部選完才上屏。
     */
    private fun flushPendingPick() {
        if (pendingPick.isEmpty()) return
        val text = pendingPick.toString()
        pendingPick.setLength(0)
        updatePendingPick()
        runCatching { activeEditorInstance.commitText(text, false) }
            .onFailure { Timber.e(it, "twelvekey: flush pending failed") }
        maybeRecordPhrase()
        trime.updateComposing()
        refreshTwelveKey()
    }

    /** 取出並清空暫存的字（引擎 commit 時用，保證「暫存 + 引擎提交」一次上屏）。 */
    private fun drainPendingPick(): String {
        if (pendingPick.isEmpty()) return ""
        val text = pendingPick.toString()
        pendingPick.setLength(0)
        updatePendingPick()
        return text
    }

    /** librime preedit 裡已選前綴（不含碼串）。調用方需持有引擎鎖。 */
    private fun compositionPrefix(): String =
        runCatching { Rime.getCompositionPrefix() }.getOrDefault("").orEmpty()

    /**
     * 把「已選未提交前綴」並進上屏文字。
     *
     * librime 有時 commit buffer 已包含前綴（整串一次提交），此時不能重複拼接，用 startsWith 判斷。
     */
    private fun foldUncommittedPrefix(commitText: String): String {
        val prefix = uncommittedPrefix
        uncommittedPrefix = ""
        if (prefix.isEmpty() || commitText.startsWith(prefix)) return commitText
        return prefix + commitText
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
            "caretProbe snapshot input=[%s] consumedEnd=%s remaining=[%s] groups=%s",
            snapshot?.input,
            snapshot?.consumedEnd,
            snapshot?.remaining,
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
        // 組字結束（上屏/清空）→ 清掉「按段選字」的錨點，下一輪重新記。
        // 分段不再依賴用戶按 `'`：面板按碼位自動拆段（見 TwelveKeySnapshotBuilder.splitParts）。
        if (!Rime.isComposing()) {
            setSegmentAnchor(null)
            // 組字結束（上屏/清空/取消）→ 未提交前綴已無意義，避免後續一次性誤上屏。
            uncommittedPrefix = ""
        }
        if (isTwelveKeyMode()) {
            // 按鍵後立即重建快照（不 debounce）；面板關閉時只枚舉候選條需要的 16 條，
            // 面板打開才全量枚舉（按鍵卡頓的另一半原因：每鍵 600 條枚舉）。
            if (suppressSnapshotRefresh) {
                // 這次刷新是「快照落地後重畫 preedit」帶來的，當前快照已是最新，不必重建。
                suppressSnapshotRefresh = false
                return
            }
            twelveKeyController?.refresh(
                immediate = true,
                full = panelWanted || isCandidatePanelShown(),
            )
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
     * 非 12 鍵方案現算一份快照：候選按 comment（spelling_hints 給的精確讀音）分組，
     * 面板左列音節索引與 12 鍵完全一樣；點擊照舊走 `select_candidate(全局下標)`。
     *
     * - `applyLearning = false`：候選條由 librime 直接提供，面板必須保持引擎順序；
     * - `mapFallbackLetters = false`：輸入的就是字母，兜底原樣上屏。
     */
    private fun legacySnapshot(): TwelveKeySnapshot = withEngineLock {
        runCatching {
            val local = TwelveKeySnapshotBuilder.local()
            if (local.isEmpty) TwelveKeySnapshot.EMPTY
            else TwelveKeySnapshotBuilder.build(
                local,
                TwelveKeySnapshotBuilder.MAX_CANDIDATES,
                applyLearning = false,
                mapFallbackLetters = false,
            )
        }.getOrDefault(TwelveKeySnapshot.EMPTY)
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
    
        // 方案開關（情/聯/簡繁…）會改變 filter 輸出：清快照緩存並立刻重建，
        // 否則固定候選欄與展開面板都會顯示舊候選（用戶 2026-11-09）。
        twelveKeyController?.invalidateForOptions()
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
    /** 最近一次發佈的快照：候選條、面板、preedit 預覽、空格上屏全部只讀這一份。 */
    private var currentSnapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY

    /**
     * 抑制「下一次候選刷新」：快照落地後重畫 preedit 會走 `updateComposing()`，
     * 而它內部會調 [onCandidatesRefreshed] 再刷一次快照——那時快照已經是最新的，純屬重複。
     */
    private var suppressSnapshotRefresh = false

    /**
     * librime context 裡「已選中但尚未提交」的前綴（例如先選了一個多字詞「江蘇」）。
     *
     * 多字詞被 `select_candidate` 選中時不會產生 commit buffer：TextInputManager 只能看到
     * preedit 前綴（`Rime.getCompositionPrefix()`）。之後的分段選字會 `clearComposition()`
     * 重建組合，若不把這個前綴一併上屏就會被丟掉（用戶 2026-11-10 報「江蘇沒上屏」）。
     */
    private var uncommittedPrefix: String = ""

    /** App 側「已選但還沒上屏」的字（兜底路徑用；見 flushPendingPick）。 */
    private val pendingPick = StringBuilder()

    // ---- 逐段拼詞的累積（記進 PhraseMemory：文字 + 各段讀音 + 完整碼串）----
    private val pickedText = StringBuilder()
    private val pickedReadings = StringBuilder()
    private var pickedCode: String = ""
    private var pickedMissingReading = false

    /** 一共「分幾次選」把這串拼出來：≥2 才算逐段拼出的詞（一次選中的詞由引擎自己學）。 */
    private var pickedPieces: Int = 0

    // ---- 合成候選的曝光統計（誤造詞判定用，見 PhraseMemory.score）----
    /** 本輪真出現在候選條/展開面板上的合成候選：文字 → 完整碼串。 */
    private val shownSynthetic = LinkedHashMap<String, String>()
    /** 本輪被點選過的合成候選文字。 */
    private val pickedSynthetic = HashSet<String>()

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
                } else if (event.command == "candidate_panel") {
                    // 12 鍵的 /、：輸入中開/關展開面板（主題 Panel_toggle_*，用戶 2026-11-09）
                    toggleCandidatePanel()
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
            // 長按 ⌘（meta/Menu）鍵：輸入方案切換 + 啟用/停用（用戶 2026-11-09）
            KeyEvent.KEYCODE_MENU -> trime.showSchemaMenuDialog()
            else -> onKey(event.code, event.mask or trime.keyboardSwitcher.currentKeyboard.modifer)
        }
    }

    override fun onKey(keyEventCode: Int, metaState: Int) {
        printModifierKeyState(metaState, "keyEventCode=" + keyEventCode)
        // 逐段選字時暫存的字還沒上屏，退格先撤它（與引擎 preedit 的退格語義一致）。
        if (keyEventCode == KeyEvent.KEYCODE_DEL && pendingPick.isNotEmpty()) {
            pendingPick.setLength(pendingPick.length - 1)
            updatePendingPick()
            setSegmentAnchor(pendingPick.lastOrNull())
            trime.updateComposing()
            refreshTwelveKey()
            return
        }
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
        val item = currentSnapshot.barItems.getOrNull(index)
        if (item != null) {
            com.hazukieq.hkrime.util.DiagLog.d(
                "pick",
                "bar idx=$index text=${item.text} seg=${item.segment} idx=${item.index} comment=${item.comment} rest=${item.restCode}",
            )
            // 聯想詞（segment = 1）：整詞上屏。
            if (item.segment == 1) {
                pickAssociationWord(item.text, item.comment)
                return
            }
            // 合成候選（App 側學到的整串詞）：整串一次上屏。
            if (item.segment == SYNTHETIC_SEGMENT) {
                commitPhrase(item)
                return
            }
            // 分詞輸入時候選條列的是「這一段能選的字」（index = -1）：
            // 直接上屏、把剩餘段餵回引擎，不進引擎選詞（用戶 2026-11-09 指示）。
            if (item.segment >= 0) {
                notePickedSegment(item.text, item.comment)
                val engineIndex = resolveEngineIndexForSegment(item.text, item.comment)
                if (engineIndex >= 0) {
                    learnCandidatePick(item.text, item.comment)
                    twelveKeyController?.selectCandidate(engineIndex, item.comment)
                    return
                }
                pickSegmentedCandidate(item.text, item.restCode)
                return
            }
            if (holdPartialPick(item.text, item.comment, engineIndex = item.index)) return
            notePickedSegment(item.text, item.comment)
            learnCandidatePick(item.text, item.comment)
            twelveKeyController?.selectCandidate(item.index, item.comment)
            return
        }
        val fallback = snapshot.fallback
        if (fallback != null && index == currentSnapshot.barItems.size) {
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
