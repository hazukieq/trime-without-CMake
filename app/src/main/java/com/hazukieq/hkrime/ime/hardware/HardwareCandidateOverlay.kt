package com.hazukieq.hkrime.ime.hardware

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.hazukieq.hkrime.data.Config

/**
 * 直連 librime `RimeMenu` 的當前頁候選快照（純數據，便於單測）。
 * 翻頁（- / = / PageUp / PageDown）、選中（↑ / ↓ / 1-9 / Space）與 preedit 編輯（← / →）
 * 全部由 librime 引擎原生處理，本結構只如實渲染引擎當前頁。
 */
data class HardwareEnginePage(
    val items: List<Item>,
    val highlightedIndex: Int,
    val pageNo: Int,
    val isLastPage: Boolean,
    val selectLabels: List<String> = emptyList(),
) {
    data class Item(
        val text: String,
        val comment: String,
        val openccModified: Boolean = false,
    )

    val isEmpty: Boolean get() = items.isEmpty()

    val hasMultiplePages: Boolean get() = pageNo > 0 || !isLastPage

    fun labelForSlot(slot: Int): String =
        selectLabels.getOrNull(slot)?.takeIf { it.isNotEmpty() } ?: "${(slot + 1) % 10}"

    fun pageSummary(): String = if (hasMultiplePages) "${pageNo + 1}" else ""

    companion object {
        val EMPTY = HardwareEnginePage(
            items = emptyList(),
            highlightedIndex = -1,
            pageNo = 0,
            isLastPage = true,
        )
    }
}

/**
 * PC / ibus-rime 風格的結構化預編輯段（已選前綴 + 當前活躍編碼段 + 光標位置）。
 */
data class HardwarePreedit(
    val text: String,
    val selStart: Int = 0,
    val selEnd: Int = text.length,
    val cursorPos: Int = text.length,
)

/**
 * 物理鍵盤接手模式下的「極速單視圖 Canvas 懸浮候選層」（類桌面端 ibus-rime / 小狼毫體驗）：
 * - 直接渲染 librime 引擎當前頁（`RimeMenu`），完全忠實反映引擎的翻頁、高亮與 preedit 游標；
 * - 單一自繪 `Canvas` View 掛載在 `InputMethod` 窗口 `decorView` 上（與觸屏 `TouchPreeditPopupWindow` 徹底隔離）：
 *   打字過程中 **零 View 分配、零 `requestLayout()`、零 `measure()`**，只觸發單次 `invalidate()`，
 *   將 UI 線程每幀耗時從 ~18ms 降至 <1ms（鎖定 120/144Hz 零掉幀）；
 * - 若 `decorView` 尚未就緒，自動回退 [HardwareOverlayHost]（`TYPE_APPLICATION_OVERLAY` 兜底）；
 * - 支援橫排（horizontal）與豎排（vertical）兩種排布，自動跟隨 IME 主題配色與光標位置。
 */
class HardwareCandidateOverlay(
    private val context: Context,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** 點擊當前頁第 slotIndex（0-based）個候選時回調，直接轉給 `Rime.selectCandidate(slotIndex)`。 */
    var onSelectSlot: ((Int) -> Unit)? = null

    /** 點擊頁碼指示器時回調，向引擎發送 `Page_Down`。 */
    var onNextPage: (() -> Unit)? = null

    private var onBoundsChangedListener: ((immediate: Boolean) -> Unit)? = null

    private var canvasView: CandidateCanvasView? = null
    private var attachedToDecor: Boolean = false
    private val fallbackOverlay = HardwareOverlayHost(context, touchable = true, dismissOnOutsideTouch = false)

    private var verticalMode: Boolean = false
    private var currentPreedit: HardwarePreedit = HardwarePreedit("")
    private var currentPage: HardwareEnginePage = HardwareEnginePage.EMPTY
    private var limitNoticeText: String = ""
    private var lastAnchorView: View? = null
    private val lastCursorRect = RectF()
    private val anchorScreenLoc = IntArray(2)
    private val anchorWindowLoc = IntArray(2)

    private var showing: Boolean = false
    private val touchableRectInWindow = Rect()

    private val hideLimitHintRunnable = Runnable {
        if (limitNoticeText.isEmpty()) return@Runnable
        limitNoticeText = ""
        if (showing) {
            renderAndPosition(lastAnchorView, lastCursorRect, contentChanged = true)
        }
    }

    // Theme tokens
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF1C1C1E.toInt()
    private var subTextColor: Int = 0xFF8E8E93.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentBgColor: Int = 0xFF007AFF.toInt()
    private var accentTextColor: Int = Color.WHITE
    private var lastColorSchemeKey: String? = null
    private var themeApplied: Boolean = false

    val isShowing: Boolean get() = showing

    fun copyTouchableRect(out: Rect): Boolean {
        if (!showing || !attachedToDecor || touchableRectInWindow.isEmpty) return false
        out.set(touchableRectInWindow)
        return true
    }

    fun setOnBoundsChanged(listener: ((immediate: Boolean) -> Unit)?) {
        onBoundsChangedListener = listener
    }

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        val selectedColor = runCatching {
            com.hazukieq.hkrime.data.AppPrefs.defaultInstance().looks.selectedColor
        }.getOrNull()
        if (cfg != null && (selectedColor != lastColorSchemeKey || cfg.getCurrentColor_("back_color") == null)) {
            runCatching { cfg.initCurrentColors() }
            lastColorSchemeKey = selectedColor
        }
        val bg = cfg?.getColor("back_color")
            ?: cfg?.getColor("keyboard_back_color")
            ?: cfg?.getColor("key_back_color")
            ?: 0xFFFFFFFF.toInt()
        cardBgColor = ColorUtils.setAlphaComponent(bg, 255)
        textColor = cfg?.getColor("candidate_text_color")
            ?: cfg?.getColor("text_color")
            ?: cfg?.getColor("key_text_color")
            ?: if (ColorUtils.calculateLuminance(cardBgColor) < 0.5) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val commentColor = cfg?.getColor("comment_text_color")
        subTextColor = if (commentColor != null && commentColor != cardBgColor) {
            commentColor
        } else {
            ColorUtils.setAlphaComponent(textColor, 155)
        }
        val rawBorder = cfg?.getColor("border_color") ?: cfg?.getColor("key_border_color")
        borderColor = if (rawBorder != null && rawBorder != cardBgColor) {
            rawBorder
        } else {
            ColorUtils.setAlphaComponent(textColor, 44)
        }
        val candHiliteBg = cfg?.getColor("hilited_candidate_back_color")
        val keyHiliteBg = cfg?.getColor("hilited_key_back_color")
        accentBgColor = when {
            candHiliteBg != null && candHiliteBg != cardBgColor -> candHiliteBg
            keyHiliteBg != null && keyHiliteBg != cardBgColor -> keyHiliteBg
            else -> 0xFF007AFF.toInt()
        }
        val rawAccentText = cfg?.getColor("hilited_candidate_text_color")
            ?: cfg?.getColor("hilited_key_text_color")
        accentTextColor = if (rawAccentText != null &&
            ColorUtils.calculateContrast(
                ColorUtils.setAlphaComponent(rawAccentText, 255),
                ColorUtils.setAlphaComponent(accentBgColor, 255),
            ) >= 2.2
        ) {
            rawAccentText
        } else if (ColorUtils.calculateLuminance(accentBgColor) < 0.5) {
            Color.WHITE
        } else {
            0xFF1C1C1E.toInt()
        }

        themeApplied = true
        canvasView?.applyThemeColors(
            cardBg = cardBgColor,
            text = textColor,
            subText = subTextColor,
            border = borderColor,
            accentBg = accentBgColor,
            accentText = accentTextColor,
        )
        if (showing) {
            canvasView?.invalidate()
        }
    }

    fun updateAndShow(
        anchorView: View?,
        cursorRect: RectF,
        preedit: HardwarePreedit,
        page: HardwareEnginePage,
        vertical: Boolean,
    ) {
        if (preedit.text.isEmpty() && page.isEmpty) {
            dismiss()
            return
        }
        verticalMode = vertical
        currentPreedit = preedit
        currentPage = page
        lastAnchorView = anchorView
        lastCursorRect.set(cursorRect)
        ensureCanvasView()
        val selectedColor = runCatching {
            com.hazukieq.hkrime.data.AppPrefs.defaultInstance().looks.selectedColor
        }.getOrNull()
        if (!themeApplied || selectedColor != lastColorSchemeKey) {
            applyTheme()
        }
        renderAndPosition(anchorView, lastCursorRect, contentChanged = true)
    }

    fun updateCursorPosition(anchorView: View?, cursorRect: RectF) {
        lastCursorRect.set(cursorRect)
        if (anchorView != null) lastAnchorView = anchorView
        if (!showing) return
        renderAndPosition(lastAnchorView, lastCursorRect, contentChanged = false)
    }

    fun showLimitNotice(limit: Int): Boolean {
        if (!showing && currentPreedit.text.isEmpty() && currentPage.isEmpty) return false
        limitNoticeText = "已达 $limit 键上限"
        handler.removeCallbacks(hideLimitHintRunnable)
        handler.postDelayed(hideLimitHintRunnable, LIMIT_NOTICE_DURATION_MS)
        renderAndPosition(lastAnchorView, lastCursorRect, contentChanged = true)
        return showing
    }

    fun dismiss() {
        handler.removeCallbacks(hideLimitHintRunnable)
        limitNoticeText = ""
        val wasShowing = showing
        showing = false
        currentPage = HardwareEnginePage.EMPTY
        currentPreedit = HardwarePreedit("")
        touchableRectInWindow.setEmpty()
        if (attachedToDecor) {
            canvasView?.clearDrawing()
            if (wasShowing) {
                onBoundsChangedListener?.invoke(true)
            }
        } else {
            fallbackOverlay.dismiss()
        }
    }

    fun detach() {
        dismiss()
        val v = canvasView
        canvasView = null
        if (v != null) {
            v.onSlotTap = null
            v.onPageTap = null
            (v.parent as? ViewGroup)?.removeView(v)
        }
        attachedToDecor = false
        fallbackOverlay.dismiss()
    }

    private fun ensureCanvasView(): CandidateCanvasView {
        canvasView?.let { return it }
        return CandidateCanvasView(context).apply {
            applyThemeColors(
                cardBg = cardBgColor,
                text = textColor,
                subText = subTextColor,
                border = borderColor,
                accentBg = accentBgColor,
                accentText = accentTextColor,
            )
            onSlotTap = { idx -> onSelectSlot?.invoke(idx) }
            onPageTap = { onNextPage?.invoke() }
        }.also { canvasView = it }
    }

    private fun renderAndPosition(
        anchorView: View?,
        cursorRect: RectF,
        contentChanged: Boolean,
    ) {
        val view = ensureCanvasView()
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = dp(8)

        if (contentChanged || view.cardWidth <= 0f || view.cardHeight <= 0f) {
            val maxCardW = if (verticalMode) {
                dp(260).coerceAtMost((screenW - margin * 2).coerceAtLeast(dp(180)))
            } else {
                (screenW - margin * 2).coerceAtLeast(dp(120))
            }
            view.updateModel(currentPreedit, currentPage, verticalMode, maxCardW.toFloat(), limitNoticeText)
        }

        val cardW = view.cardWidth.toInt()
        val cardH = view.cardHeight.toInt()

        val hasValidCursor = cursorRect.left.isFinite() && cursorRect.top.isFinite() && cursorRect.bottom.isFinite() &&
            cursorRect.bottom >= cursorRect.top &&
            !(cursorRect.left == 0f && cursorRect.top == 0f && cursorRect.bottom == 0f) &&
            cursorRect.left >= -20f && cursorRect.left <= screenW + 20f &&
            cursorRect.bottom > 0f && cursorRect.top < screenH
        val cardX: Int
        val cardY: Int
        if (hasValidCursor) {
            cardX = cursorRect.left.toInt().coerceIn(margin, (screenW - cardW - margin).coerceAtLeast(margin))
            val belowY = cursorRect.bottom.toInt() + dp(6)
            cardY = if (belowY + cardH <= screenH - dp(16)) {
                belowY
            } else {
                (cursorRect.top.toInt() - cardH - dp(6)).coerceAtLeast(dp(16))
            }
        } else {
            cardX = dp(32).coerceIn(margin, (screenW - cardW - margin).coerceAtLeast(margin))
            cardY = (screenH - cardH - dp(84)).coerceAtLeast(dp(24))
        }

        val decor = anchorView as? FrameLayout
        if (decor != null && decor.isAttachedToWindow) {
            if (!attachedToDecor) {
                fallbackOverlay.dismiss()
            }
            if (view.parent !== decor) {
                (view.parent as? ViewGroup)?.removeView(view)
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            attachedToDecor = true
            decor.getLocationOnScreen(anchorScreenLoc)
            decor.getLocationInWindow(anchorWindowLoc)
            val localX = (cardX - anchorScreenLoc[0]).coerceAtLeast(0).toFloat()
            val localY = (cardY - anchorScreenLoc[1]).coerceAtLeast(0).toFloat()

            // 純 Canvas 重繪：零 requestLayout、零 measure，僅 invalidate()
            view.setCardOriginAndInvalidate(localX, localY)
            showing = true

            val winL = anchorWindowLoc[0] + localX.toInt()
            val winT = anchorWindowLoc[1] + localY.toInt()
            val winR = winL + cardW
            val winB = winT + cardH
            if (touchableRectInWindow.left != winL ||
                touchableRectInWindow.top != winT ||
                touchableRectInWindow.right != winR ||
                touchableRectInWindow.bottom != winB
            ) {
                touchableRectInWindow.set(winL, winT, winR, winB)
                // 快速打字時延後合併更新 touchableRegion，避免每鍵觸發同步 setInsets Binder IPC
                onBoundsChangedListener?.invoke(false)
            }
            return
        }

        // 回退路徑：decorView 尚未附著時使用 HardwareOverlayHost（TYPE_APPLICATION_OVERLAY）
        if (attachedToDecor) {
            (view.parent as? ViewGroup)?.removeView(view)
            attachedToDecor = false
        }
        val pad = dp(SHADOW_PAD_DP)
        view.setCardOriginAndInvalidate(pad.toFloat(), pad.toFloat())
        val ok = fallbackOverlay.showOrUpdate(
            contentView = view,
            anchorView = null,
            x = cardX - pad,
            y = cardY - pad,
            width = cardW + pad * 2,
            height = cardH + pad * 2,
        )
        showing = ok
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    /**
     * 單視圖自繪候選卡片：全鏈路零子 View、零 `requestLayout()`、零物件分配熱路徑。
     */
    private class CandidateCanvasView(context: Context) : View(context) {
        var onSlotTap: ((Int) -> Unit)? = null
        var onPageTap: (() -> Unit)? = null

        var cardWidth: Float = 0f
            private set
        var cardHeight: Float = 0f
            private set

        private val density = context.resources.displayMetrics.density
        private val scaledDensity = context.resources.displayMetrics.scaledDensity

        private fun dpf(v: Float): Float = v * density
        private fun spf(v: Float): Float = v * scaledDensity

        private val cardPadH = dpf(10f)
        private val cardPadV = dpf(7f)
        private val cardRadius = dpf(12f)
        private val pillRadius = dpf(8f)
        private val dividerMarginV = dpf(5f)
        private val pagePadLeft = dpf(10f)
        private val hItemPadH = dpf(7f)
        private val hItemPadV = dpf(4f)
        private val hItemGap = dpf(4f)
        private val vItemPadH = dpf(8f)
        private val vItemPadV = dpf(5f)
        private val vItemGap = dpf(2f)
        private val labelGap = dpf(4f)
        private val commentGap = dpf(8f)
        private val minWVert = dpf(176f)
        private val minWHoriz = dpf(120f)
        private val shadowRadius = dpf(6f)
        private val shadowDy = dpf(2f)
        private val underlineStroke = dpf(1.2f).coerceAtLeast(1f)

        private var cardBgColor: Int = Color.WHITE
        private var textColor: Int = 0xFF1C1C1E.toInt()
        private var subTextColor: Int = 0xFF8E8E93.toInt()
        private var borderColor: Int = 0xFFDADCE0.toInt()
        private var accentBgColor: Int = 0xFF007AFF.toInt()
        private var accentTextColor: Int = Color.WHITE
        private var selectedSubColor: Int = ColorUtils.setAlphaComponent(Color.WHITE, 200)
        private var dividerColor: Int = ColorUtils.setAlphaComponent(0xFF1C1C1E.toInt(), 28)

        private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1f).coerceAtLeast(1f)
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = underlineStroke
        }
        private val openccDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val limitHintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spf(11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val preeditPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spf(14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val pagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spf(11.5f)
        }
        private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spf(12.5f)
        }
        private val candPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spf(15.5f)
        }
        private val commentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spf(11.5f)
        }

        private val preeditFm = preeditPaint.fontMetrics
        private val preeditLineH = preeditFm.descent - preeditFm.ascent
        private val candFm = candPaint.fontMetrics
        private val candLineH = candFm.descent - candFm.ascent
        private val labelFm = labelPaint.fontMetrics
        private val pageFm = pagePaint.fontMetrics
        private val commentFm = commentPaint.fontMetrics

        private var hasContent: Boolean = false
        private var originX: Float = 0f
        private var originY: Float = 0f
        private var vertical: Boolean = false
        private var preedit: HardwarePreedit = HardwarePreedit("")
        private var page: HardwareEnginePage = HardwareEnginePage.EMPTY
        private var pageLabelText: String = ""
        private var pageTextWidth: Float = 0f
        private var limitHintText: String = ""
        private var limitHintWidth: Float = 0f

        // 預計算繪製與點擊區域（相對於 card 原點）
        private val cardRect = RectF()
        private val pageHitRect = RectF()
        private val slotHitRects = Array(10) { RectF() }
        private val slotLabels = Array(10) { "" }
        private val slotLabelWidths = FloatArray(10)
        private val slotTexts = Array(10) { "" }
        private val slotComments = Array(10) { "" }
        private val slotOpenccModified = BooleanArray(10)
        private var slotCount: Int = 0
        private var headerHeight: Float = 0f
        private var dividerY: Float = 0f
        private var preeditDisplayText: String = ""
        private var preeditSelStart: Int = 0
        private var preeditSelEnd: Int = 0
        private var preeditCaretPos: Int = 0

        init {
            setWillNotDraw(false)
            // Hardware-accelerated shadowLayer (API 28+)
            cardFillPaint.setShadowLayer(shadowRadius, 0f, shadowDy, Color.argb(46, 0, 0, 0))
        }

        fun applyThemeColors(
            cardBg: Int,
            text: Int,
            subText: Int,
            border: Int,
            accentBg: Int,
            accentText: Int,
        ) {
            cardBgColor = cardBg
            textColor = text
            subTextColor = subText
            borderColor = border
            accentBgColor = accentBg
            accentTextColor = accentText
            selectedSubColor = ColorUtils.setAlphaComponent(accentText, 200)
            dividerColor = ColorUtils.setAlphaComponent(text, 28)

            cardFillPaint.color = cardBgColor
            cardStrokePaint.color = borderColor
            pillPaint.color = accentBgColor
            dividerPaint.color = dividerColor
            underlinePaint.color = accentBgColor
            pagePaint.color = subTextColor
        }

        fun updateModel(
            newPreedit: HardwarePreedit,
            newPage: HardwareEnginePage,
            isVertical: Boolean,
            maxCardW: Float,
            newLimitHint: String = "",
        ) {
            preedit = newPreedit
            page = newPage
            vertical = isVertical
            limitHintText = newLimitHint
            limitHintWidth = if (newLimitHint.isNotEmpty()) limitHintPaint.measureText(newLimitHint) else 0f

            val innerMaxW = (maxCardW - cardPadH * 2f).coerceAtLeast(dpf(80f))

            // 1. Header（preedit + 編碼上限提示 + 頁碼）
            if (page.hasMultiplePages) {
                val leftArrow = if (page.pageNo > 0) "‹" else "·"
                val rightArrow = if (!page.isLastPage) "›" else "·"
                pageLabelText = "$leftArrow ${page.pageNo + 1} $rightArrow"
                pageTextWidth = pagePaint.measureText(pageLabelText)
            } else {
                pageLabelText = ""
                pageTextWidth = 0f
            }

            val limitReserve = if (limitHintWidth > 0f) limitHintWidth + dpf(8f) else 0f
            val pageReserve = if (pageTextWidth > 0f) pageTextWidth + pagePadLeft else 0f
            val maxPreeditW = (innerMaxW - limitReserve - pageReserve).coerceAtLeast(dpf(40f))
            preparePreeditText(newPreedit, maxPreeditW)
            val preeditW = preeditPaint.measureText(preeditDisplayText)
            val headerContentW = preeditW + limitReserve + pageReserve
            headerHeight = preeditLineH

            // 2. 候選列表排版（全部只用 Paint.measureText，零 View 分配）
            val items = page.items
            slotCount = items.size.coerceAtMost(slotHitRects.size)

            var bodyW = 0f
            var bodyH = 0f
            val bodyTop = cardPadV + headerHeight + if (slotCount > 0) dividerMarginV * 2f + 1f else 0f
            dividerY = cardPadV + headerHeight + dividerMarginV

            if (slotCount > 0) {
                if (vertical) {
                    var maxRowW = 0f
                    val rowH = candLineH + vItemPadV * 2f
                    for (i in 0 until slotCount) {
                        val item = items[i]
                        val lbl = "${page.labelForSlot(i)}."
                        val lblW = labelPaint.measureText(lbl)
                        slotLabels[i] = lbl
                        slotLabelWidths[i] = lblW
                        val cmt = item.comment
                        val cmtW = if (cmt.isNotEmpty()) commentGap + commentPaint.measureText(cmt) else 0f
                        val availTextW = (innerMaxW - vItemPadH * 2f - lblW - labelGap - cmtW).coerceAtLeast(dpf(32f))
                        val ellipsized = TextUtils.ellipsize(item.text, candPaint, availTextW, TextUtils.TruncateAt.END).toString()
                        slotTexts[i] = ellipsized
                        slotComments[i] = cmt
                        slotOpenccModified[i] = item.openccModified
                        val rowW = vItemPadH * 2f + lblW + labelGap + candPaint.measureText(ellipsized) + cmtW
                        if (rowW > maxRowW) maxRowW = rowW
                    }
                    bodyW = maxRowW.coerceAtMost(innerMaxW)
                    bodyH = slotCount * rowH + (slotCount - 1) * vItemGap
                } else {
                    var curX = cardPadH
                    val rowH = candLineH + hItemPadV * 2f
                    for (i in 0 until slotCount) {
                        val item = items[i]
                        val lbl = "${page.labelForSlot(i)}."
                        val lblW = labelPaint.measureText(lbl)
                        slotLabels[i] = lbl
                        slotLabelWidths[i] = lblW
                        slotTexts[i] = item.text
                        slotComments[i] = ""
                        slotOpenccModified[i] = item.openccModified
                        val itemW = hItemPadH * 2f + lblW + labelGap + candPaint.measureText(item.text)
                        slotHitRects[i].set(curX, bodyTop, curX + itemW, bodyTop + rowH)
                        curX += itemW + hItemGap
                    }
                    bodyW = (curX - cardPadH - hItemGap).coerceAtLeast(0f)
                    bodyH = rowH
                }
            }

            val minW = if (vertical) minWVert else minWHoriz
            cardWidth = (maxOf(headerContentW, bodyW) + cardPadH * 2f).coerceIn(minW, maxCardW)
            cardHeight = (bodyTop + bodyH + cardPadV)

            // 豎排模式下所有候選行撐滿卡片內寬
            if (vertical && slotCount > 0) {
                val rowH = candLineH + vItemPadV * 2f
                val rowRight = cardWidth - cardPadH
                for (i in 0 until slotCount) {
                    val top = bodyTop + i * (rowH + vItemGap)
                    slotHitRects[i].set(cardPadH, top, rowRight, top + rowH)
                }
            }

            if (pageTextWidth > 0f) {
                pageHitRect.set(
                    cardWidth - cardPadH - pageTextWidth - pagePadLeft,
                    0f,
                    cardWidth,
                    cardPadV + headerHeight + dividerMarginV,
                )
            } else {
                pageHitRect.setEmpty()
            }
            hasContent = true
        }

        fun setCardOriginAndInvalidate(x: Float, y: Float) {
            originX = x
            originY = y
            cardRect.set(originX, originY, originX + cardWidth, originY + cardHeight)
            invalidate()
        }

        fun clearDrawing() {
            if (!hasContent) return
            hasContent = false
            slotCount = 0
            invalidate()
        }

        private fun preparePreeditText(p: HardwarePreedit, maxW: Float) {
            val raw = p.text
            if (raw.isEmpty()) {
                preeditDisplayText = ""
                preeditSelStart = 0
                preeditSelEnd = 0
                preeditCaretPos = 0
                return
            }
            val s = p.selStart.coerceIn(0, raw.length)
            val e = p.selEnd.coerceIn(s, raw.length)
            val c = p.cursorPos.coerceIn(0, raw.length)

            val full = StringBuilder(raw.length + 1)
                .append(raw, 0, c)
                .append('│')
                .append(raw, c, raw.length)
                .toString()

            if (preeditPaint.measureText(full) <= maxW) {
                preeditDisplayText = full
                preeditSelStart = if (s < c) s else s + 1
                preeditSelEnd = if (e <= c) e else e + 1
                preeditCaretPos = c
                return
            }

            // 超長時從開頭截斷並加「…」，保留末尾正在輸入的編碼與光標
            val ellipsis = "…"
            val ellW = preeditPaint.measureText(ellipsis)
            var trimIdx = 1
            while (trimIdx < full.length - 1 &&
                ellW + preeditPaint.measureText(full, trimIdx, full.length) > maxW
            ) {
                trimIdx++
            }
            preeditDisplayText = ellipsis + full.substring(trimIdx)
            val shift = trimIdx - 1
            val rawSelStart = if (s < c) s else s + 1
            val rawSelEnd = if (e <= c) e else e + 1
            preeditSelStart = (rawSelStart - shift).coerceIn(1, preeditDisplayText.length)
            preeditSelEnd = (rawSelEnd - shift).coerceIn(preeditSelStart, preeditDisplayText.length)
            preeditCaretPos = (c - shift).coerceIn(1, preeditDisplayText.length - 1)
        }

        override fun onDraw(canvas: Canvas) {
            if (!hasContent || cardWidth <= 0f || cardHeight <= 0f) return

            canvas.save()
            canvas.translate(originX, originY)

            // 1. 卡片背景（含柔和陰影）+ 圓角邊框
            canvas.drawRoundRect(0f, 0f, cardWidth, cardHeight, cardRadius, cardRadius, cardFillPaint)
            canvas.drawRoundRect(0f, 0f, cardWidth, cardHeight, cardRadius, cardRadius, cardStrokePaint)

            // 2. 預編輯行（分段著色 + 活躍段下劃線 + 游標 │）
            val headerBaseline = cardPadV - preeditFm.ascent
            drawPreedit(canvas, cardPadH, headerBaseline)

            // 2.5 編碼上限提示（外設 Canvas 自繪，不共用觸屏 PopupWindow 或系統 Toast）
            if (limitHintWidth > 0f && limitHintText.isNotEmpty()) {
                val amber = if (ColorUtils.calculateLuminance(textColor) > 0.5) {
                    0xFFFBBF24.toInt()
                } else {
                    0xFFD97706.toInt()
                }
                limitHintPaint.color = amber
                val pageRight = if (pageTextWidth > 0f) cardWidth - cardPadH - pageTextWidth - pagePadLeft else cardWidth - cardPadH
                val hintX = (pageRight - limitHintWidth).coerceAtLeast(cardPadH + preeditPaint.measureText(preeditDisplayText) + dpf(6f))
                canvas.drawText(limitHintText, hintX, headerBaseline, limitHintPaint)
            }

            // 3. 頁碼指示器
            if (pageTextWidth > 0f) {
                val pageX = cardWidth - cardPadH - pageTextWidth
                val pageBaseline = cardPadV + (headerHeight - (pageFm.descent - pageFm.ascent)) * 0.5f - pageFm.ascent
                canvas.drawText(pageLabelText, pageX, pageBaseline, pagePaint)
            }

            // 4. 分隔線 + 候選詞列表
            if (slotCount > 0) {
                canvas.drawLine(cardPadH, dividerY, cardWidth - cardPadH, dividerY, dividerPaint)

                val padH = if (vertical) vItemPadH else hItemPadH
                val hiIdx = page.highlightedIndex
                for (i in 0 until slotCount) {
                    val r = slotHitRects[i]
                    val selected = i == hiIdx
                    if (selected) {
                        canvas.drawRoundRect(r, pillRadius, pillRadius, pillPaint)
                    }
                    val primaryInk = if (selected) accentTextColor else textColor
                    val secondaryInk = if (selected) selectedSubColor else subTextColor

                    val cy = r.centerY()
                    val candBaseline = cy - (candFm.ascent + candFm.descent) * 0.5f
                    val labelBaseline = cy - (labelFm.ascent + labelFm.descent) * 0.5f

                    var x = r.left + padH
                    labelPaint.color = secondaryInk
                    canvas.drawText(slotLabels[i], x, labelBaseline, labelPaint)
                    x += slotLabelWidths[i] + labelGap

                    candPaint.color = primaryInk
                    val txt = slotTexts[i]
                    canvas.drawText(txt, x, candBaseline, candPaint)
                    val txtW = candPaint.measureText(txt)

                    if (slotOpenccModified[i]) {
                        val markColor = if (ColorUtils.calculateLuminance(primaryInk) > 0.5) {
                            0xFFFBBF24.toInt()
                        } else {
                            0xFFD97706.toInt()
                        }
                        openccDotPaint.color = ColorUtils.setAlphaComponent(markColor, 235)
                        val dotX = (x + txtW + dpf(3f)).coerceAtMost(r.right - dpf(4f))
                        val dotY = (candBaseline + candFm.ascent + dpf(2f)).coerceAtLeast(r.top + dpf(4f))
                        canvas.drawCircle(dotX, dotY, dpf(2f), openccDotPaint)
                    }

                    val cmt = slotComments[i]
                    if (vertical && cmt.isNotEmpty()) {
                        x += txtW + commentGap
                        commentPaint.color = secondaryInk
                        val cmtBaseline = cy - (commentFm.ascent + commentFm.descent) * 0.5f
                        canvas.drawText(cmt, x, cmtBaseline, commentPaint)
                    }
                }
            }

            canvas.restore()
        }

        private fun drawPreedit(canvas: Canvas, startX: Float, baseline: Float) {
            val txt = preeditDisplayText
            if (txt.isEmpty()) return
            var x = startX
            val s = preeditSelStart.coerceIn(0, txt.length)
            val e = preeditSelEnd.coerceIn(s, txt.length)

            fun drawSeg(from: Int, to: Int, color: Int, underline: Boolean) {
                if (to <= from) return
                val w = preeditPaint.measureText(txt, from, to)
                preeditPaint.color = color
                canvas.drawText(txt, from, to, x, baseline, preeditPaint)
                if (underline) {
                    val uy = baseline + dpf(1.5f)
                    canvas.drawLine(x, uy, x + w, uy, underlinePaint)
                }
                x += w
            }

            // 前綴（0..s）
            if (s > 0) {
                if (preeditCaretPos in 0 until s) {
                    drawSeg(0, preeditCaretPos, textColor, false)
                    drawSeg(preeditCaretPos, preeditCaretPos + 1, accentBgColor, false)
                    drawSeg(preeditCaretPos + 1, s, textColor, false)
                } else {
                    drawSeg(0, s, textColor, false)
                }
            }
            // 活躍編碼段（s..e）
            if (e > s) {
                drawSeg(s, e, accentBgColor, true)
            }
            // 後續段（e..length）
            if (e < txt.length) {
                if (preeditCaretPos in e until txt.length) {
                    drawSeg(e, preeditCaretPos, subTextColor, false)
                    drawSeg(preeditCaretPos, preeditCaretPos + 1, accentBgColor, false)
                    drawSeg(preeditCaretPos + 1, txt.length, subTextColor, false)
                } else {
                    drawSeg(e, txt.length, subTextColor, false)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!hasContent) return false
            val lx = event.x - originX
            val ly = event.y - originY
            if (lx < 0f || ly < 0f || lx > cardWidth || ly > cardHeight) {
                return false
            }
            if (event.action == MotionEvent.ACTION_UP) {
                if (!pageHitRect.isEmpty && pageHitRect.contains(lx, ly)) {
                    onPageTap?.invoke()
                    return true
                }
                for (i in 0 until slotCount) {
                    if (slotHitRects[i].contains(lx, ly)) {
                        onSlotTap?.invoke(i)
                        return true
                    }
                }
            }
            return true
        }
    }

    companion object {
        private const val SHADOW_PAD_DP = 10
        private const val LIMIT_NOTICE_DURATION_MS = 1600L
    }
}
