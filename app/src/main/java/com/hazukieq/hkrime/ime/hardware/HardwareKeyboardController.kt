package com.hazukieq.hkrime.ime.hardware

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.WindowCompat
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.twelvekey.CompositionInput
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.KeyboardLayoutResolver
import com.hazukieq.hkrime.ime.clipboard.ClipboardStore
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.ime.keyboard.Event
import com.hazukieq.hkrime.ime.keyboard.Key

/**
 * 物理鍵盤接手模式獨立控制器（與觸屏軟鍵盤模式徹底隔離）：
 * - 按鍵直連 librime 引擎（對齊 Linux ibus-rime）：
 *   - `←` / `→` 由引擎 `navigator` 編輯 preedit 游標；
 *   - `↑` / `↓` 由引擎 `selector` 切換高亮候選（跨邊界自動翻頁）；
 *   - `-` / `=` / `PageUp` / `PageDown` / `,` / `.` 由引擎 `key_binder` + `selector` 翻頁；
 *   - `1`–`9` / `Space` / `Enter` / `Esc` / `BackSpace` / `Delete` / `Tab` / `Ctrl+...` 均由引擎原生處理；
 *   - `Shift_L` / `Shift_R` 按下與釋放直傳引擎 `ascii_composer`，中英切換時在光標旁彈出 macOS 風格狀態膠囊。
 * - 懸浮候選窗直接渲染引擎 `RimeMenu` 當前頁，並同步用戶設定的每頁候選數（3..9）至方案 `menu/page_size`。
 */
class HardwareKeyboardController(
    private val service: Trime,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val cursorRect = RectF()
    private val consumedDownKeys = HashSet<Int>()

    private val clipboardStore = ClipboardStore.create(service)
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        clipboardStore.syncFromSystem(service)
    }

    private val candidateOverlay = HardwareCandidateOverlay(service).apply {
        onSelectSlot = { slotIndex -> selectCandidateOnCurrentPage(slotIndex) }
        onNextPage = { sendEngineKey(Event.getRimeEvent(KeyEvent.KEYCODE_PAGE_DOWN, 0)) }
        setOnBoundsChanged { immediate -> requestRecomputeInsets(immediate) }
    }
    private val modeIndicatorOverlay = HardwareModeIndicatorOverlay(service)
    private val symbolOverlay = HardwareSymbolOverlay(service).apply {
        onCommitSymbol = { sym -> service.activeEditorInstance.commitText(sym, false) }
        onDismissed = { hideAnchorIfIdle() }
        setOnBoundsChanged { requestRecomputeInsets(true) }
    }
    private val clipboardOverlay = HardwareClipboardOverlay(service, clipboardStore).apply {
        onCommitText = { text -> service.activeEditorInstance.commitText(text, false) }
        onDismissed = { hideAnchorIfIdle() }
        setOnBoundsChanged { requestRecomputeInsets(true) }
    }
    private val menuOverlay = HardwareMenuOverlay(
        context = service,
        isSchemaSupported = { sid -> isSchemaSupportedForHardware(sid) },
    ).apply {
        onSwitchSchema = { schemaId -> selectHardwareSchemaById(schemaId) }
        onToggleSchemaEnabled = { schemaId, enable, switchAfter, onDone ->
            toggleHardwareSchemaEnabled(schemaId, enable, switchAfter, onDone)
        }
        onToggleSwitch = { switchIndex -> toggleHardwareSwitch(switchIndex) }
        onSelectColor = { colorKey -> selectHardwareColor(colorKey) }
        onDismissed = { hideAnchorIfIdle() }
        setOnBoundsChanged { requestRecomputeInsets(true) }
    }

    private var canvasView: android.widget.FrameLayout? = null
    private var anchorView: View? = null
    private val tmpRect = android.graphics.Rect()
    private var composable: Boolean = true
    private var pendingModeIndicatorAscii: Boolean? = null
    private var pendingToolOverlay: PendingOverlayTool? = null
    private val codeLimitNoticeGate = CompositionInput.LimitNoticeGate(CompositionInput.LIMIT_NOTICE_COOLDOWN_MS)
    private var transparentWindowKey: Long = 0L
    private var recomputeInsetsScheduled: Boolean = false
    private val recomputeInsetsRunnable = Runnable {
        recomputeInsetsScheduled = false
        service.window?.window?.decorView?.requestLayout()
    }

    private enum class PendingOverlayTool { SYMBOL, CLIPBOARD, MENU }

    // 在 Dialog.dispatchKeyEvent 進入 mWindow.superDispatchKeyEvent(ViewGroup 焦點導航) 前先接手工具懸浮層按鍵，
    // 防止 Tab / 方向鍵被 DecorView 的 FocusFinder 在 ACTION_DOWN 吞掉導致僅剩下 ACTION_UP。
    private val windowKeyListener = android.content.DialogInterface.OnKeyListener { _, keyCode, event ->
        if (!isActive() || !composable) return@OnKeyListener false
        if (!symbolOverlay.isShowing && !clipboardOverlay.isShowing && !menuOverlay.isShowing) {
            return@OnKeyListener false
        }
        if (Key.isTrimeModifierKey(keyCode)) return@OnKeyListener false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event)
            KeyEvent.ACTION_UP -> onKeyUp(event)
            else -> false
        }
    }

    private fun syncWindowKeyInterceptor() {
        runCatching { service.window?.setOnKeyListener(windowKeyListener) }
    }

    init {
        runCatching {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.addPrimaryClipChangedListener(clipboardListener)
            clipboardStore.syncFromSystem(service)
        }
    }

    private val retryShowRunnable = Runnable { refreshComposing(service.currentInputConnection) }
    private val retryIndicatorRunnable = Runnable {
        val ascii = pendingModeIndicatorAscii ?: return@Runnable
        showModeIndicatorNow(ascii)
    }
    private val retryToolRunnable = Runnable {
        val tool = pendingToolOverlay ?: return@Runnable
        showToolOverlayNow(tool)
    }

    fun hasHardwareKeyboard(): Boolean {
        val cfg = service.resources.configuration ?: return false
        return cfg.keyboard != Configuration.KEYBOARD_NOKEYS &&
            cfg.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    }

    fun isActive(): Boolean =
        AppPrefs.defaultInstance().keyboard.hwKeyboardMode &&
            hasHardwareKeyboard() &&
            isSchemaSupportedForHardware(Rime.getSchemaId())

    /**
     * 物理鍵盤接手模式僅支援 26 鍵方案（qwerty / wubi）；
     * 拒絕 12 鍵、9 鍵、筆畫、注音等非 26 鍵方案進入外設接手模式。
     */
    fun isSchemaSupportedForHardware(schemaId: String?): Boolean {
        if (schemaId.isNullOrBlank() || schemaId == ".default") return false
        val type = Rime.getSchemaKeyboardType(schemaId)
        return type == KeyboardLayoutResolver.TYPE_QWERTY || type == KeyboardLayoutResolver.TYPE_WUBI
    }

    /**
     * 創建物理鍵盤模式專屬的「全屏透明畫布」（掛載於 `InputMethod` type=2011 窗口內）：
     * - 覆寫 `onMeasure` 強制撐滿全屏（避開 `InputMethodService.setInputView` 覆蓋為 `WRAP_CONTENT` 導致高度為 0）；
     * - 零軟鍵盤視圖，不頂起宿主介面，非浮窗區域觸控全穿透；
     * - 所有外設懸浮窗（候選、狀態膠囊、符號、剪切板）直接繪製於此畫布內，
     *   在系統設定、QQ、微信、國產 OEM 系統上 100% 可見且免懸浮窗權限。
     */
    fun createCanvasView(): android.widget.FrameLayout {
        val existing = canvasView
        if (existing != null) {
            existing.requestLayout()
            return existing
        }
        return object : android.widget.FrameLayout(service) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val dm = resources.displayMetrics
                val targetW = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
                    MeasureSpec.getSize(widthMeasureSpec) > 0
                ) {
                    MeasureSpec.getSize(widthMeasureSpec)
                } else {
                    dm.widthPixels
                }
                val targetH = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
                    MeasureSpec.getSize(heightMeasureSpec) > 0
                ) {
                    MeasureSpec.getSize(heightMeasureSpec)
                } else {
                    dm.heightPixels
                }
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY),
                )
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setOnTouchListener { _, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                    if (symbolOverlay.isShowing) {
                        symbolOverlay.dismiss()
                        return@setOnTouchListener true
                    }
                    if (clipboardOverlay.isShowing) {
                        clipboardOverlay.dismiss()
                        return@setOnTouchListener true
                    }
                    if (menuOverlay.isShowing) {
                        menuOverlay.dismiss()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }.also { canvasView = it }.also { syncWindowKeyInterceptor() }
    }

    /** 退出物理鍵盤接手模式（切回觸屏軟鍵盤時呼叫）：釋放畫布附著並還原系統欄 fitsSystemWindows。 */
    fun onExitHardwareMode() {
        runCatching { service.window?.setOnKeyListener(null) }
        handler.removeCallbacks(retryShowRunnable)
        handler.removeCallbacks(retryIndicatorRunnable)
        handler.removeCallbacks(retryToolRunnable)
        handler.removeCallbacks(recomputeInsetsRunnable)
        recomputeInsetsScheduled = false
        transparentWindowKey = 0L
        pendingModeIndicatorAscii = null
        pendingToolOverlay = null
        codeLimitNoticeGate.reset()
        cursorRect.setEmpty()
        consumedDownKeys.clear()
        candidateOverlay.detach()
        modeIndicatorOverlay.dismiss()
        symbolOverlay.dismiss()
        clipboardOverlay.dismiss()
        menuOverlay.dismiss()
        val win = service.window?.window ?: return
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(win, true)
            win.decorView.findViewById<View>(android.R.id.navigationBarBackground)?.alpha = 1f
        }
    }

    /** 僅收起外設懸浮層，不干涉觸屏模式的 candidatesView / TouchPreeditPopupWindow 狀態。 */
    fun dismissOverlaysOnly() {
        if (candidateOverlay.isShowing) candidateOverlay.dismiss()
        if (modeIndicatorOverlay.isShowing) modeIndicatorOverlay.dismiss()
        if (symbolOverlay.isShowing) symbolOverlay.dismiss()
        if (clipboardOverlay.isShowing) clipboardOverlay.dismiss()
        if (menuOverlay.isShowing) menuOverlay.dismiss()
    }

    /** 螢幕方向或顯示配置變化時清掉舊光標座標並收起懸浮層，避免殘留於旋轉前座標。 */
    fun onConfigurationChanged() {
        cursorRect.setEmpty()
        transparentWindowKey = 0L
        dismissOverlaysOnly()
    }

    fun applyInsets(outInsets: InputMethodService.Insets) {
        val h = service.window?.window?.decorView?.height ?: 0
        // 關鍵：Android 12-15 的 ImeInsetsSourceProvider 會用 (frame.bottom - contentTopInsets)
        // 計算 mSourceFrame；若 contentTopInsets == h（高度為 0），mSourceFrame.isEmpty() 為 true，
        // 系統會把 InputMethod 的 insets_animation leash 設為 visible=false，導致整個畫布不可見！
        // 保留 1px 非空底邊：(h - 1).coerceAtLeast(0)，既不頂起宿主介面，又確保 mImeShowing=true。
        val topInset = (h - 1).coerceAtLeast(0)
        outInsets.contentTopInsets = topInset
        outInsets.visibleTopInsets = topInset
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        if (candidateOverlay.copyTouchableRect(tmpRect)) outInsets.touchableRegion.union(tmpRect)
        if (symbolOverlay.copyTouchableRect(tmpRect)) outInsets.touchableRegion.union(tmpRect)
        if (clipboardOverlay.copyTouchableRect(tmpRect)) outInsets.touchableRegion.union(tmpRect)
        if (menuOverlay.copyTouchableRect(tmpRect)) outInsets.touchableRegion.union(tmpRect)
    }

    private fun requestRecomputeInsets(immediate: Boolean = true) {
        handler.removeCallbacks(recomputeInsetsRunnable)
        recomputeInsetsScheduled = true
        if (immediate) {
            handler.post(recomputeInsetsRunnable)
        } else {
            // 連續打字期間防抖合併 onComputeInsets，避免每鍵觸發同步 IWindowSession.setInsets Binder IPC
            handler.postDelayed(recomputeInsetsRunnable, 140L)
        }
    }

    fun applyTheme() {
        candidateOverlay.applyTheme()
        symbolOverlay.applyTheme()
        clipboardOverlay.applyTheme()
        menuOverlay.applyTheme()
    }

    fun applyTransparentHostWindow() {
        val win = service.window?.window ?: return
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val key = (System.identityHashCode(win).toLong() shl 1) or (if (night) 1L else 0L)
        if (key == transparentWindowKey) return
        transparentWindowKey = key
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.navigationBarColor = Color.TRANSPARENT
            win.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
            win.decorView.setBackgroundColor(Color.TRANSPARENT)
            win.findViewById<View>(android.R.id.inputArea)?.setBackgroundColor(Color.TRANSPARENT)
            win.decorView.findViewById<View>(android.R.id.navigationBarBackground)?.alpha = 0f
            // 同步設定導航欄淺/深色圖標，與系統深淺模式匹配
            WindowCompat.getInsetsController(win, win.decorView).isAppearanceLightNavigationBars = !night
        }
    }

    fun onStartInput(info: EditorInfo?, ic: InputConnection?) {
        Rime.get(service)
        syncWindowKeyInterceptor()
        symbolOverlay.prewarm()
        service.activeEditorInstance.resetInlineComposingCache()
        applyTransparentHostWindow()
        consumedDownKeys.clear()
        cursorRect.setEmpty()
        handler.removeCallbacks(retryShowRunnable)
        handler.removeCallbacks(retryIndicatorRunnable)
        pendingModeIndicatorAscii = null
        codeLimitNoticeGate.reset()
        candidateOverlay.dismiss()
        modeIndicatorOverlay.dismiss()
        service.setCandidatesViewShown(false)

        ic?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        composable = resolveComposable(info)
        service.textInputManager?.isComposable = composable

        // 物理鍵盤模式下固定關閉 librime `_horizontal` 鍵位覆寫，避免觸屏主題的 `horizontal: true`
        // 導致 ↑/↓ 變成翻頁而非選詞、←/→ 搶走 navigator 游標編輯。
        synchronized(Rime.ENGINE_LOCK) {
            Rime.setOption("_horizontal", false)
        }
        // 同步物理鍵盤設定的每頁候選數（3..9）到 librime 方案 menu/page_size（已一致時為空操作）
        val desiredPageSize = AppPrefs.defaultInstance().keyboard.hwCandidateCount
        Rime.applyHardwarePageSize(desiredPageSize)

        if (composable && !Rime.isComposing()) {
            // 仿 macOS：點擊進入輸入框時在光標旁短暫顯示當前中/英狀態小圖標
            showModeIndicator(Rime.isAsciiMode())
        }
    }

    /** 引擎透過 OptionEvent（如 Shift / CapsLock / Ctrl+Shift+2）切換中英文時彈出 macOS 狀態膠囊。 */
    fun onAsciiModeChanged(asciiMode: Boolean) {
        if (!isActive() || !composable || Rime.isComposing()) return
        showModeIndicator(asciiMode)
    }

    fun onFinishInput() {
        handler.removeCallbacks(retryShowRunnable)
        handler.removeCallbacks(retryIndicatorRunnable)
        handler.removeCallbacks(retryToolRunnable)
        pendingModeIndicatorAscii = null
        pendingToolOverlay = null
        codeLimitNoticeGate.reset()
        consumedDownKeys.clear()
        candidateOverlay.dismiss()
        modeIndicatorOverlay.dismiss()
        symbolOverlay.dismiss()
        clipboardOverlay.dismiss()
        menuOverlay.dismiss()
        service.setCandidatesViewShown(false)
    }

    fun destroy() {
        runCatching { service.window?.setOnKeyListener(null) }
        handler.removeCallbacks(retryShowRunnable)
        handler.removeCallbacks(retryIndicatorRunnable)
        handler.removeCallbacks(retryToolRunnable)
        handler.removeCallbacks(recomputeInsetsRunnable)
        recomputeInsetsScheduled = false
        transparentWindowKey = 0L
        pendingModeIndicatorAscii = null
        pendingToolOverlay = null
        codeLimitNoticeGate.reset()
        consumedDownKeys.clear()
        runCatching {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.removePrimaryClipChangedListener(clipboardListener)
        }
        candidateOverlay.detach()
        modeIndicatorOverlay.dismiss()
        symbolOverlay.dismiss()
        clipboardOverlay.dismiss()
        menuOverlay.dismiss()
        canvasView = null
        anchorView = null
    }

    fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val tmp = RectF()
        val composingText = info.composingText
        var populated = false
        if (!composingText.isNullOrEmpty()) {
            val startPos = info.composingTextStart
            val endPos = startPos + composingText.length - 1
            val startRect = info.getCharacterBounds(startPos)
            val endRect = info.getCharacterBounds(endPos)
            if (startRect != null && endRect != null &&
                startRect.left.isFinite() && startRect.top.isFinite() && startRect.bottom.isFinite() &&
                endRect.left.isFinite() && endRect.top.isFinite() && endRect.bottom.isFinite()
            ) {
                tmp.top = minOf(startRect.top, endRect.top)
                tmp.left = minOf(startRect.left, endRect.left)
                tmp.bottom = maxOf(startRect.bottom, endRect.bottom)
                tmp.right = maxOf(startRect.right, endRect.right)
                populated = true
            }
        }
        if (!populated) {
            val x = info.insertionMarkerHorizontal
            val top = info.insertionMarkerTop
            val bottom = info.insertionMarkerBottom
            if (x.isFinite() && top.isFinite() && bottom.isFinite()) {
                tmp.top = top
                tmp.left = x
                tmp.bottom = bottom
                tmp.right = x
                populated = true
            }
        }
        if (!populated) return
        info.matrix.mapRect(tmp)
        val dm = service.resources.displayMetrics
        val valid = tmp.left.isFinite() && tmp.top.isFinite() && tmp.bottom.isFinite() &&
            tmp.bottom >= tmp.top &&
            !(tmp.left == 0f && tmp.top == 0f && tmp.bottom == 0f) &&
            tmp.left >= -20f && tmp.left <= dm.widthPixels + 20f &&
            tmp.bottom > 0f && tmp.top < dm.heightPixels
        if (!valid) return

        // 過濾亞像素 / 2px 內的游標抖動，避免單次按鍵觸發多次重複重算與重排
        if (!cursorRect.isEmpty &&
            kotlin.math.abs(cursorRect.left - tmp.left) < 2f &&
            kotlin.math.abs(cursorRect.top - tmp.top) < 2f &&
            kotlin.math.abs(cursorRect.bottom - tmp.bottom) < 2f
        ) {
            return
        }

        cursorRect.set(tmp)
        val anchor = resolveAttachedAnchor()
        candidateOverlay.updateCursorPosition(anchor, cursorRect)
        modeIndicatorOverlay.updateCursorPosition(anchor, cursorRect)
        symbolOverlay.updateCursorPosition(anchor, cursorRect)
        clipboardOverlay.updateCursorPosition(anchor, cursorRect)
        menuOverlay.updateCursorPosition(anchor, cursorRect)
    }

    /**
     * 按鍵直連 librime 引擎（對齊 ibus-rime）：
     * - 修飾鍵（Shift_L / Shift_R / Ctrl / Alt）：按下傳 `(keysym, 0)` 給引擎 `ascii_composer`，
     *   並返回 `false` 讓 Android 維持修飾狀態以支援組合鍵。
     * - 一般按鍵：直接轉為 X11/Rime `(keysym, mask)` 交由 librime 處理；
     *   若引擎消費（返回 `true`），則提交引擎產出的文字並刷新懸浮候選層。
     */
    fun onKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (isSystemIgnoredKey(keyCode)) return false
        if (!composable) return false

        // 外設專屬桌面懸浮工具快捷鍵（底部相鄰三鍵，避開筆記/文檔的 Ctrl+Shift+E/V/M）：
        // - Ctrl+Shift+, ：符號與表情懸浮層
        // - Ctrl+Shift+. ：剪切板歷史懸浮層
        // - Ctrl+Shift+?（/）：外設設置菜單懸浮層
        if (event.repeatCount == 0) {
            val toolKey = resolveToolShortcut(
                keyCode = keyCode,
                isCtrl = event.isCtrlPressed,
                isShift = event.isShiftPressed,
                isAlt = event.isAltPressed,
            )
            if (toolKey != null) {
                consumedDownKeys.add(keyCode)
                // 異步切換懸浮層，讓 onKeyDown 在 <1ms 內返回 true，避免阻塞 IME 導致系統丟棄 ACTION_UP
                handler.post {
                    when (toolKey) {
                        TOOL_SYMBOL ->
                            if (symbolOverlay.isShowing) symbolOverlay.dismiss() else openToolOverlay(PendingOverlayTool.SYMBOL)
                        TOOL_CLIPBOARD ->
                            if (clipboardOverlay.isShowing) clipboardOverlay.dismiss() else openToolOverlay(PendingOverlayTool.CLIPBOARD)
                        TOOL_MENU ->
                            if (menuOverlay.isShowing) menuOverlay.dismiss() else openToolOverlay(PendingOverlayTool.MENU)
                    }
                }
                return true
            }
        }

        // 若符號、剪切板或方案/開關懸浮層打開，優先由懸浮層獨佔鍵盤導航（修飾鍵放行以維持 meta 狀態）
        if (symbolOverlay.isShowing) {
            if (Key.isTrimeModifierKey(keyCode)) return false
            consumedDownKeys.add(keyCode)
            return symbolOverlay.handleKeyDown(event)
        }
        if (clipboardOverlay.isShowing) {
            if (Key.isTrimeModifierKey(keyCode)) return false
            consumedDownKeys.add(keyCode)
            return clipboardOverlay.handleKeyDown(event)
        }
        if (menuOverlay.isShowing) {
            if (Key.isTrimeModifierKey(keyCode)) return false
            consumedDownKeys.add(keyCode)
            return menuOverlay.handleKeyDown(event)
        }

        // 修飾鍵（含 Shift_L / Shift_R）：直傳給 librime ascii_composer
        if (Key.isTrimeModifierKey(keyCode)) {
            if (event.repeatCount == 0) {
                val rimeEvent = Event.getRimeEvent(keyCode, 0)
                sendEngineKey(rimeEvent, refreshIfHandled = true)
            }
            return false
        }

        // 未組字時，導航/刪除/回車/返回鍵直接交給宿主應用
        if (!Rime.isComposing() && isHostPassThroughWhenIdle(keyCode) && !hasCtrlOrAlt(event)) {
            return false
        }

        val mappedKeyCode = if (Rime.isComposing() && keyCode == KeyEvent.KEYCODE_BACK) {
            KeyEvent.KEYCODE_ESCAPE
        } else {
            keyCode
        }

        val rimeEvent = mapKeyEventToRime(event, mappedKeyCode) ?: return false
        val handled = sendEngineKey(rimeEvent, refreshIfHandled = true)
        if (handled) {
            consumedDownKeys.add(keyCode)
        }
        return handled
    }

    /**
     * 按鍵釋放直連 librime 引擎：
     * - 修飾鍵（Shift_L / Shift_R 等）：發送 `META_RELEASE_ON` 讓 `ascii_composer` 完成單擊中英切換，
     *   偵測到 `ascii_mode` 變更即在光標旁彈出 macOS 風格「中 / 英」膠囊。
     * - 已被 `onKeyDown` 消費的一般鍵：吞掉釋放事件，防止漏給宿主。
     */
    fun onKeyUp(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (isSystemIgnoredKey(keyCode)) return false
        if (!composable) return false

        if (Key.isTrimeModifierKey(keyCode)) {
            val prevAscii = Rime.isAsciiMode()
            val prevComposing = Rime.isComposing()
            val rimeEvent = Event.getRimeEvent(keyCode, Rime.META_RELEASE_ON)
            val handled = sendEngineKey(rimeEvent, refreshIfHandled = false)
            val newAscii = Rime.isAsciiMode()
            if (handled || prevComposing != Rime.isComposing()) {
                service.updateComposing()
            }
            if (newAscii != prevAscii && !Rime.isComposing()) {
                showModeIndicator(newAscii)
            }
            return false
        }

        if (consumedDownKeys.remove(keyCode)) {
            return true
        }
        val activeOverlay = symbolOverlay.isShowing || clipboardOverlay.isShowing || menuOverlay.isShowing
        if (isOrphanHostInterceptedTabUp(keyCode, wasConsumedOnDown = false, hasActiveToolOverlay = activeOverlay)) {
            notifyHostInterceptedTab()
            return true
        }
        return false
    }

    private fun notifyHostInterceptedTab() {
        when {
            symbolOverlay.isShowing ->
                symbolOverlay.showTemporaryHint("当前应用已抢占 Tab 键，请改用 [ ] 或 - = 切分类")
            menuOverlay.isShowing ->
                menuOverlay.showTemporaryHint("当前应用已抢占 Tab 键，请改用 ←→ 或 [ ] 切页")
            clipboardOverlay.isShowing ->
                clipboardOverlay.showTemporaryHint("当前应用已抢占 Tab 键，请改用 ↑↓ 或 [ ] 选择")
        }
    }

    fun refreshComposing(ic: InputConnection?) {
        handler.removeCallbacks(retryShowRunnable)
        if (!Rime.isComposing()) {
            candidateOverlay.dismiss()
            // 關鍵守衛：狀態膠囊或工具懸浮層正在顯示/等待附著時，絕不可誤殺錨點窗口。
            hideAnchorIfIdle()
            return
        }
        // 開始組字時立即收起狀態膠囊，讓位給候選懸浮層
        handler.removeCallbacks(retryIndicatorRunnable)
        pendingModeIndicatorAscii = null
        modeIndicatorOverlay.dismiss()

        // 僅當游標尚未就緒時才補拉一次；onStartInput 已開啟 CURSOR_UPDATE_MONITOR 持續監聽
        if (cursorRect.isEmpty) {
            ic?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        }
        if (!service.isInputViewShown) {
            service.requestShowSelf(0)
        }
        val anchor = resolveAttachedAnchor()
        val preedit = buildStructuredPreedit()
        val page = buildEnginePage()
        val prefs = AppPrefs.defaultInstance().keyboard
        candidateOverlay.updateAndShow(
            anchorView = anchor,
            cursorRect = cursorRect,
            preedit = preedit,
            page = page,
            vertical = prefs.hwCandidateOrientation == "vertical",
        )
        if (!candidateOverlay.isShowing) {
            handler.postDelayed(retryShowRunnable, 24L)
        }
    }

    private fun mapKeyEventToRime(event: KeyEvent, mappedKeyCode: Int): IntArray? {
        // 帶 Ctrl / Alt / Meta 組合鍵時，走標準 KeyEvent -> RimeKeycode 映射以保留修飾位（如 Ctrl+p/n/b/f/Shift+2）
        if (hasCtrlOrAlt(event) || event.isMetaPressed) {
            val rimeEvent = Event.getRimeEvent(mappedKeyCode, event.metaState)
            return if (Rime.isVoidKeycode(rimeEvent[0])) null else rimeEvent
        }
        // 非組合鍵的可列印 ASCII（0x21..0x7E：字母、數字、- = , . ' ; ! ? 等），
        // X11 / librime keysym 直接等於其 ASCII 碼位，與 ibus-rime 完全一致。
        val unicode = event.unicodeChar
        if (unicode in 0x21..0x7E) {
            return intArrayOf(unicode, 0)
        }
        // 方向鍵、翻頁鍵、空格（含 Shift+Space）、回車、退格、Delete、Home/End、Tab、Escape、F1..F12
        val rimeEvent = Event.getRimeEvent(mappedKeyCode, event.metaState)
        return if (Rime.isVoidKeycode(rimeEvent[0])) null else rimeEvent
    }

    private fun sendEngineKey(rimeEvent: IntArray, refreshIfHandled: Boolean = true): Boolean {
        val handled: Boolean
        val committed: Boolean
        synchronized(Rime.ENGINE_LOCK) {
            if (!Rime.isAsciiMode() && Rime.isComposing()) {
                val allowAppend =
                    (rimeEvent[1] and (Rime.META_RELEASE_ON or Rime.META_CTRL_ON or Rime.META_ALT_ON)) == 0
                if (CompositionInput.shouldBlockOverLimit(
                        Rime.RimeGetInput(),
                        rimeEvent[0],
                        allowAppend,
                    )
                ) {
                    notifyCodeLimitReached()
                    return true
                }
            }
            handled = Rime.onKey(rimeEvent)
            committed = service.activeEditorInstance.commitRimeText(updateUi = false)
        }
        if ((handled || committed) && refreshIfHandled) {
            service.updateComposing()
        }
        return handled
    }

    private fun selectCandidateOnCurrentPage(slotIndex: Int): Boolean {
        val ok: Boolean
        synchronized(Rime.ENGINE_LOCK) {
            ok = Rime.selectCandidate(slotIndex)
            if (ok) {
                service.activeEditorInstance.commitRimeText(updateUi = false)
            }
        }
        if (ok) {
            service.updateComposing()
        }
        return ok
    }

    private fun buildStructuredPreedit(): HardwarePreedit {
        val comp = Rime.getComposition()
        val raw = comp?.text?.takeIf { it.isNotEmpty() } ?: Rime.getComposingText().orEmpty()
        if (comp == null || raw.isEmpty()) {
            return HardwarePreedit(raw)
        }
        val start = comp.start.coerceIn(0, raw.length)
        val end = comp.end.coerceIn(start, raw.length)
        val cursorChar = utf8ByteOffsetToCharIndex(raw, comp.cursorPos)
        return HardwarePreedit(
            text = raw,
            selStart = start,
            selEnd = end,
            cursorPos = cursorChar,
        )
    }

    private fun buildEnginePage(): HardwareEnginePage {
        val cands = Rime.getCandidates() ?: return HardwareEnginePage.EMPTY
        val items = ArrayList<HardwareEnginePage.Item>(cands.size)
        for (c in cands) {
            if (c == null) continue
            items.add(
                HardwareEnginePage.Item(
                    text = c.text.orEmpty(),
                    comment = c.comment?.trim().orEmpty(),
                    openccModified = c.opencc_modified,
                ),
            )
        }
        val labels = Rime.getSelectLabels()?.toList().orEmpty()
        return HardwareEnginePage(
            items = items,
            highlightedIndex = Rime.getCandHighlightIndex(),
            pageNo = Rime.getMenuPageNo(),
            isLastPage = Rime.isMenuLastPage(),
            selectLabels = labels,
        )
    }

    private fun utf8ByteOffsetToCharIndex(text: String, byteOffset: Int): Int {
        if (byteOffset <= 0) return 0
        val bytes = text.toByteArray(Charsets.UTF_8)
        val clamped = byteOffset.coerceIn(0, bytes.size)
        return String(bytes, 0, clamped, Charsets.UTF_8).length.coerceIn(0, text.length)
    }

    private fun showModeIndicator(asciiMode: Boolean) {
        handler.removeCallbacks(retryIndicatorRunnable)
        pendingModeIndicatorAscii = asciiMode
        showModeIndicatorNow(asciiMode)
    }

    fun notifyCodeLimitReached() {
        if (!codeLimitNoticeGate.tryAcquire(SystemClock.uptimeMillis())) return
        handler.post {
            candidateOverlay.showLimitNotice(CompositionInput.MAX_CODE_LENGTH)
        }
    }

    private fun showModeIndicatorNow(asciiMode: Boolean) {
        if (!isActive() || Rime.isComposing()) {
            pendingModeIndicatorAscii = null
            return
        }
        if (!service.isInputViewShown) {
            service.requestShowSelf(0)
        }
        syncWindowKeyInterceptor()
        applyTransparentHostWindow()
        val anchor = resolveAttachedAnchor()
        pendingModeIndicatorAscii = null
        modeIndicatorOverlay.show(
            anchorView = anchor,
            cursorRect = cursorRect,
            isAsciiMode = asciiMode,
            onHidden = { hideAnchorIfIdle() },
        )
        if (!modeIndicatorOverlay.isShowing && anchor == null) {
            pendingModeIndicatorAscii = asciiMode
            handler.postDelayed(retryIndicatorRunnable, 24L)
        }
    }

    private fun openToolOverlay(tool: PendingOverlayTool) {
        handler.removeCallbacks(retryIndicatorRunnable)
        handler.removeCallbacks(retryToolRunnable)
        pendingModeIndicatorAscii = null
        modeIndicatorOverlay.dismiss()
        if (Rime.isComposing()) {
            Rime.clearComposition()
            candidateOverlay.dismiss()
            service.activeEditorInstance.updateComposingText()
        }
        if (tool != PendingOverlayTool.SYMBOL) symbolOverlay.dismiss()
        if (tool != PendingOverlayTool.CLIPBOARD) clipboardOverlay.dismiss()
        if (tool != PendingOverlayTool.MENU) menuOverlay.dismiss()

        pendingToolOverlay = tool
        showToolOverlayNow(tool)
    }

    private fun showToolOverlayNow(tool: PendingOverlayTool) {
        if (!isActive()) {
            pendingToolOverlay = null
            return
        }
        if (!service.isInputViewShown) {
            service.requestShowSelf(0)
        }
        syncWindowKeyInterceptor()
        applyTransparentHostWindow()
        val anchor = resolveAttachedAnchor()
        pendingToolOverlay = null
        when (tool) {
            PendingOverlayTool.SYMBOL -> symbolOverlay.show(anchor, cursorRect)
            PendingOverlayTool.CLIPBOARD -> clipboardOverlay.show(anchor, cursorRect)
            PendingOverlayTool.MENU -> menuOverlay.show(anchor, cursorRect)
        }
        val shown = when (tool) {
            PendingOverlayTool.SYMBOL -> symbolOverlay.isShowing
            PendingOverlayTool.CLIPBOARD -> clipboardOverlay.isShowing
            PendingOverlayTool.MENU -> menuOverlay.isShowing
        }
        if (!shown && anchor == null) {
            pendingToolOverlay = tool
            handler.postDelayed(retryToolRunnable, 24L)
        }
    }

    private fun selectHardwareSchemaById(schemaId: String) {
        if (!isSchemaSupportedForHardware(schemaId)) return
        val changed = synchronized(Rime.ENGINE_LOCK) {
            if (Rime.getSchemaId() == schemaId) return@synchronized false
            val selected = runCatching { Rime.get_selected_schema_list() }.getOrNull().orEmpty()
            val idx = selected.indexOfFirst { it["schema_id"]?.toString() == schemaId }
            val ok = if (idx >= 0) Rime.selectSchema(idx) else false
            if (ok) {
                Rime.setOption("_horizontal", false)
            }
            ok
        }
        if (changed) {
            val desiredPageSize = AppPrefs.defaultInstance().keyboard.hwCandidateCount
            Rime.applyHardwarePageSize(desiredPageSize)
            service.updateComposing()
            if (isActive() && composable && !Rime.isComposing()) {
                showModeIndicator(Rime.isAsciiMode())
            }
        }
    }

    private fun toggleHardwareSchemaEnabled(
        schemaId: String,
        enable: Boolean,
        switchAfterEnable: Boolean,
        onDone: () -> Unit,
    ) {
        if (!isSchemaSupportedForHardware(schemaId)) {
            onDone()
            return
        }
        if (enable) {
            val conf = AppPrefs.defaultInstance().conf
            val validation = com.hazukieq.hkrime.util.SchemaSpecValidator.validateSchemaFiles(
                java.io.File(conf.userDataDir),
                java.io.File(conf.sharedDataDir),
                schemaId,
            )
            if (!validation.valid) {
                timber.log.Timber.w("HardwareKeyboardController rejected %s: %s", schemaId, validation.formatMessage())
                handler.post {
                    com.blankj.utilcode.util.ToastUtils.showLong(validation.formatMessage())
                    onDone()
                }
                return
            }
        }
        Thread({
            val ids = LinkedHashSet<String>()
            synchronized(Rime.ENGINE_LOCK) {
                val selected = runCatching { Rime.get_selected_schema_list() }.getOrNull().orEmpty()
                for (m in selected) {
                    val id = m["schema_id"]?.toString()
                    if (!id.isNullOrBlank()) ids.add(id)
                }
            }
            if (enable) {
                ids.add(schemaId)
            } else {
                // 保留所有觸屏方案，且確保至少保留一個啟用的外設方案
                val supportedRemaining = ids.count { it != schemaId && isSchemaSupportedForHardware(it) }
                if (supportedRemaining >= 1) {
                    ids.remove(schemaId)
                }
            }
            if (ids.isNotEmpty()) {
                Rime.select_schemas(ids.toTypedArray())
                kotlinx.coroutines.runBlocking {
                    com.hazukieq.hkrime.util.RimeUtils.deploy(service)
                }
                if (enable && switchAfterEnable) {
                    synchronized(Rime.ENGINE_LOCK) {
                        val updated = runCatching { Rime.get_selected_schema_list() }.getOrNull().orEmpty()
                        val idx = updated.indexOfFirst { it["schema_id"]?.toString() == schemaId }
                        if (idx >= 0) {
                            Rime.selectSchema(idx)
                        }
                        Rime.setOption("_horizontal", false)
                    }
                } else {
                    synchronized(Rime.ENGINE_LOCK) {
                        Rime.setOption("_horizontal", false)
                    }
                }
                Rime.applyHardwarePageSize(AppPrefs.defaultInstance().keyboard.hwCandidateCount)
            }
            handler.post {
                service.updateComposing()
                onDone()
                if (enable && switchAfterEnable && isActive() && composable && !Rime.isComposing()) {
                    showModeIndicator(Rime.isAsciiMode())
                }
            }
        }, "hw-schema-toggle").start()
    }

    private fun toggleHardwareSwitch(switchIndex: Int) {
        synchronized(Rime.ENGINE_LOCK) {
            Rime.toggleOption(switchIndex)
            Rime.getContexts()
        }
        service.updateComposing()
    }

    private fun selectHardwareColor(colorKey: String) {
        AppPrefs.defaultInstance().looks.selectedColor = colorKey
        runCatching { com.hazukieq.hkrime.data.Config.get(service).initCurrentColors() }
        applyTheme()
        service.initKeyboard()
    }

    private fun hideAnchorIfIdle() {
        requestRecomputeInsets()
    }

    private fun resolveAttachedAnchor(): View? {
        val decor = service.window?.window?.decorView
        if (decor is android.widget.FrameLayout && decor.isAttachedToWindow && decor.width > 0 && decor.height > 0) {
            decor.clipChildren = false
            decor.clipToPadding = false
            return decor
        }
        canvasView?.let { if (it.isAttachedToWindow) return it }
        return null
    }

    private fun resolveComposable(info: EditorInfo?): Boolean {
        if (info == null || Rime.isEmpty()) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPasswordOrEmail = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        return !isPasswordOrEmail
    }

    private fun hasCtrlOrAlt(event: KeyEvent): Boolean =
        event.isCtrlPressed || event.isAltPressed

    private fun isSystemIgnoredKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_BRIGHTNESS_UP,
        KeyEvent.KEYCODE_BRIGHTNESS_DOWN -> true
        else -> false
    }

    private fun isHostPassThroughWhenIdle(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_TAB -> true
        else -> false
    }

    companion object {
        const val TOOL_SYMBOL = "SYMBOL"
        const val TOOL_CLIPBOARD = "CLIPBOARD"
        const val TOOL_MENU = "MENU"

        /**
         * 解析外設浮窗工具快捷鍵（連續三鍵，棄用易與富文本編輯器衝突的 Ctrl+Shift+E/V/M）：
         * - `Ctrl + Shift + ,`：符號與表情懸浮層
         * - `Ctrl + Shift + .`：剪切板歷史懸浮層
         * - `Ctrl + Shift + ?`（`KEYCODE_SLASH`）：外設設置菜單懸浮層
         */
        @JvmStatic
        fun resolveToolShortcut(
            keyCode: Int,
            isCtrl: Boolean,
            isShift: Boolean,
            isAlt: Boolean,
        ): String? {
            if (!isCtrl || !isShift || isAlt) return null
            return when (keyCode) {
                KeyEvent.KEYCODE_COMMA -> TOOL_SYMBOL
                KeyEvent.KEYCODE_PERIOD -> TOOL_CLIPBOARD
                KeyEvent.KEYCODE_SLASH -> TOOL_MENU
                else -> null
            }
        }

        /**
         * 檢測宿主應用（如富文本筆記/文檔編輯器）是否在 `onKeyPreIme` 階段搶佔了 `Tab` 的 `ACTION_DOWN`：
         * 當懸浮工具層處於開啟狀態，卻僅收到孤立的 `KEYCODE_TAB` `ACTION_UP`（未曾收到對應 `ACTION_DOWN`）時返回 `true`。
         */
        @JvmStatic
        fun isOrphanHostInterceptedTabUp(
            keyCode: Int,
            wasConsumedOnDown: Boolean,
            hasActiveToolOverlay: Boolean,
        ): Boolean = keyCode == KeyEvent.KEYCODE_TAB && !wasConsumedOnDown && hasActiveToolOverlay
    }
}
