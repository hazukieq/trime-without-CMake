package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.core.graphics.ColorUtils
import com.blankj.utilcode.util.BarUtils
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.twelvekey.PreeditPresentation
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.data.KeyboardLayoutResolver
import com.hazukieq.hkrime.databinding.CompositionRootBinding
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

/** 觸屏模式懸浮編碼條展示邏輯產出的不可變視圖狀態（與 Android View / PopupWindow 解耦）。 */
data class TouchPreeditDisplayState(
    val text: String,
    val highlightStart: Int,
    val highlightEnd: Int,
) {
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        @JvmField
        val EMPTY = TouchPreeditDisplayState("", 0, 0)
    }
}

/**
 * 觸屏軟鍵盤模式專用的懸浮編碼條（Android `PopupWindow`）：
 * - 集中管理觸屏編碼條的生命週期、固定位置、系統鎖定幾何約束與數據渲染；
 * - 與外設鍵盤模式（`ime/hardware` 的 `Hardware*Overlay`）徹底隔離，不承載物理鍵盤懸浮窗；
 * - 所有佈局/尺寸/位置/觸摸滾動策略均由系統鎖定，不再對主題開放自定義參數；
 * - 主題僅提供配色（`text_back_color`、`text_color`、`hilited_text_color`、`hilited_back_color`）與字體。
 */
class TouchPreeditPopupWindow(private val appContext: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var binding: CompositionRootBinding? = null
    private var popupWindow: PopupWindow? = null
    private var anchorRef: WeakReference<View>? = null

    var isEnabled: Boolean = false
        private set

    private var edgeMarginPx: Int = 0
    private var bottomGapPx: Int = 0
    private var pendingState: TouchPreeditDisplayState = TouchPreeditDisplayState.EMPTY
    private var limitHintActive: Boolean = false

    private val hideLimitHintRunnable = Runnable {
        limitHintActive = false
        val b = binding ?: return@Runnable
        if (b.codeLimitHint.visibility == View.GONE) return@Runnable
        b.codeLimitHint.visibility = View.GONE
        if (isEnabled && !pendingState.isEmpty) {
            handler.removeCallbacks(showOrUpdateRunnable)
            handler.post(showOrUpdateRunnable)
        }
    }

    private val showOrUpdateRunnable = Runnable {
        val win = popupWindow ?: return@Runnable
        val b = binding ?: return@Runnable
        val anchor = anchorRef?.get() ?: return@Runnable
        if (!isEnabled || anchor.windowToken == null || pendingState.isEmpty) return@Runnable

        b.compositionRoot.measure(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val maxW = PreeditScrollView.resolveFixedMaxWidthPx(appContext)
        val popupW = min(b.compositionRoot.measuredWidth, maxW)
        val popupH = b.compositionRoot.measuredHeight
        win.width = popupW
        win.height = popupH

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = edgeMarginPx
        var y = loc[1] - popupH - bottomGapPx
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            y -= BarUtils.getStatusBarHeight()
        }
        try {
            if (!win.isShowing) {
                win.showAtLocation(anchor, Gravity.START or Gravity.TOP, x, y)
            } else {
                win.update(x, y, popupW, popupH)
            }
        } catch (e: WindowManager.BadTokenException) {
            isEnabled = false
            Timber.w(e, "無法顯示候選懸浮窗（系統窗口權限限制），本次會話已停用懸浮窗")
            if (win.isShowing) win.dismiss()
        }
    }

    fun create(overlayContext: Context, windowLayoutType: Int) {
        destroy()
        val density = appContext.resources.displayMetrics.density.let { if (it > 0f) it else 1f }
        edgeMarginPx = (FIXED_EDGE_MARGIN_DP * density).roundToInt()
        bottomGapPx = (FIXED_BOTTOM_GAP_DP * density).roundToInt()

        val b = CompositionRootBinding.inflate(LayoutInflater.from(overlayContext))
        val padH = (FIXED_PADDING_H_DP * density).roundToInt()
        val padV = (FIXED_PADDING_V_DP * density).roundToInt()
        val textPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            FIXED_TEXT_SIZE_SP,
            appContext.resources.displayMetrics,
        ).roundToInt()
        b.compositions.applyFixedMetrics(textPx, FIXED_LETTER_SPACING, padH, padV)
        b.codeLimitHint.setPadding(padH, (2f * density).roundToInt(), padH, 0)
        b.codeLimitHint.visibility = View.GONE
        limitHintActive = false

        val win = PopupWindow(b.compositionRoot).apply {
            isTouchable = true
            isClippingEnabled = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this.windowLayoutType = windowLayoutType
                setIsLaidOutInScreen(true)
            }
        }
        binding = b
        popupWindow = win
        hide()
    }

    fun reset(config: Config, prefEnabled: Boolean) {
        isEnabled = prefEnabled && canShowFloatingWindow(appContext)
        binding?.compositions?.visibility = if (isEnabled) View.VISIBLE else View.GONE
        applyTheme(config)
        hide()
    }

    fun applyTheme(config: Config) {
        val win = popupWindow
        if (win != null) {
            val bg = config.getDrawable_("text_back_color")
                ?: GradientDrawable().apply { setColor(Color.WHITE) }
            win.setBackgroundDrawable(bg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                win.elevation = 0f
            }
        }
        val b = binding ?: return
        val textColor = config.getColor("text_color")
        val hilitedTextColor = config.getColor("hilited_text_color")
        val hilitedBackColor = config.getColor("hilited_back_color")
        val backColor = config.getColor("back_color")
        val tfText: Typeface? = config.getFont("text_font")
        b.compositions.applyThemeStyle(textColor, hilitedTextColor, hilitedBackColor, backColor, tfText)
        val hintColor = if (ColorUtils.calculateLuminance(textColor) > 0.5) {
            0xFFFBBF24.toInt()
        } else {
            0xFFD97706.toInt()
        }
        b.codeLimitHint.setTextColor(hintColor)
        if (tfText != null) b.codeLimitHint.typeface = tfText
    }

    fun showLimitNotice(limit: Int): Boolean {
        val b = binding ?: return false
        val anchor = anchorRef?.get() ?: return false
        if (!isEnabled || anchor.windowToken == null || pendingState.isEmpty) return false
        limitHintActive = true
        b.codeLimitHint.text = "已达 $limit 键编码上限"
        b.codeLimitHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideLimitHintRunnable)
        handler.postDelayed(hideLimitHintRunnable, LIMIT_NOTICE_DURATION_MS)
        handler.removeCallbacks(showOrUpdateRunnable)
        handler.post(showOrUpdateRunnable)
        return true
    }

    fun update(anchor: View?) {
        val b = binding
        if (!isEnabled || anchor == null || b == null) {
            hide()
            return
        }
        val state = buildLiveDisplayState()
        if (state.isEmpty) {
            hide()
            return
        }
        anchorRef = WeakReference(anchor)
        pendingState = state
        b.compositionRoot.visibility = View.VISIBLE
        b.compositions.visibility = View.VISIBLE
        b.compositions.render(state)
        handler.removeCallbacks(showOrUpdateRunnable)
        handler.post(showOrUpdateRunnable)
    }

    fun hide() {
        handler.removeCallbacks(showOrUpdateRunnable)
        handler.removeCallbacks(hideLimitHintRunnable)
        limitHintActive = false
        binding?.codeLimitHint?.visibility = View.GONE
        pendingState = TouchPreeditDisplayState.EMPTY
        popupWindow?.let { win ->
            if (win.isShowing) win.dismiss()
        }
        binding?.compositions?.resetScrollState()
    }

    fun destroy() {
        handler.removeCallbacks(showOrUpdateRunnable)
        handler.removeCallbacks(hideLimitHintRunnable)
        limitHintActive = false
        pendingState = TouchPreeditDisplayState.EMPTY
        popupWindow?.let { win ->
            if (win.isShowing) win.dismiss()
        }
        popupWindow = null
        binding = null
        anchorRef = null
    }

    private fun buildLiveDisplayState(): TouchPreeditDisplayState {
        val comp = Rime.getComposition() ?: return TouchPreeditDisplayState.EMPTY
        val raw = comp.text.orEmpty()
        if (raw.isEmpty()) return TouchPreeditDisplayState.EMPTY
        val caps = KeyboardLayoutResolver.capabilitiesOf(
            Rime.getSchemaKeyboardType(Rime.getSchemaId()),
        )
        val materialize = caps.materializePreeditFromCandidate && !caps.useTwelveKeySession
        val topComment = if (materialize) {
            Rime.getCandidates()?.firstOrNull()?.comment
        } else {
            null
        }
        return resolveDisplayState(
            rawText = raw,
            rawStart = comp.start,
            rawEnd = comp.end,
            materializeFromCandidate = materialize,
            topCandidateComment = topComment,
        )
    }

    companion object {
        private const val FIXED_TEXT_SIZE_SP = 14f
        private const val FIXED_LETTER_SPACING = 0.12f
        private const val FIXED_PADDING_H_DP = 5
        private const val FIXED_PADDING_V_DP = 5
        private const val FIXED_EDGE_MARGIN_DP = 3
        private const val FIXED_BOTTOM_GAP_DP = 0
        private const val LIMIT_NOTICE_DURATION_MS = 1600L

        @JvmStatic
        fun canShowFloatingWindow(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /** 純邏輯計算 preedit 顯示字串與高亮區間，便於單元測試與視圖分離。 */
        @JvmStatic
        fun resolveDisplayState(
            rawText: String?,
            rawStart: Int,
            rawEnd: Int,
            materializeFromCandidate: Boolean,
            topCandidateComment: String?,
        ): TouchPreeditDisplayState {
            val raw = rawText.orEmpty()
            if (raw.isEmpty()) return TouchPreeditDisplayState.EMPTY
            val rStart = rawStart.coerceIn(0, raw.length)
            var rEnd = rawEnd.coerceIn(rStart, raw.length)
            var display = raw
            if (materializeFromCandidate && !topCandidateComment.isNullOrBlank()) {
                val comment = topCandidateComment.trim()
                if (!comment.startsWith("~")) {
                    val prefix = raw.substring(0, rStart)
                    val active = raw.substring(rStart, rEnd)
                    val tail = raw.substring(rEnd)
                    val materialized = PreeditPresentation.materializeSegmentedPreedit(active, comment)
                    display = prefix + materialized + tail
                    rEnd = rStart + materialized.length
                }
            }
            return TouchPreeditDisplayState(
                text = display,
                highlightStart = rStart.coerceIn(0, display.length),
                highlightEnd = rEnd.coerceIn(rStart.coerceIn(0, display.length), display.length),
            )
        }
    }
}
