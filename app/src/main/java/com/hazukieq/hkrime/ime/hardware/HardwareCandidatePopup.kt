package com.hazukieq.hkrime.ime.hardware

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
 * 物理鍵盤接手模式下的「跟隨光標懸浮候選窗」（類桌面端 ibus-rime / 小狼毫體驗）：
 * - 直接渲染 librime 引擎當前頁（`RimeMenu`），完全忠實反映引擎的翻頁、高亮與 preedit 游標；
 * - 自動跟隨 IME 主題配色；
 * - 支援橫排（horizontal）與豎排（vertical）兩種排布；
 * - 透過 `CursorAnchorInfo` 實時跟隨光標，靠近底部時自動翻轉至光標上方。
 */
class HardwareCandidatePopup(
    private val context: Context,
) {
    /** 點擊當前頁第 slotIndex（0-based）個候選時回調，直接轉給 `Rime.selectCandidate(slotIndex)`。 */
    var onSelectSlot: ((Int) -> Unit)? = null

    /** 點擊頁碼指示器時回調，向引擎發送 `Page_Down`。 */
    var onNextPage: (() -> Unit)? = null

    private val overlay = HardwareOverlayHost(context, touchable = true, dismissOnOutsideTouch = false)
    private var shadowWrapper: FrameLayout? = null
    private var rootCard: LinearLayout? = null
    private var preeditView: TextView? = null
    private var pageView: TextView? = null
    private var dividerView: View? = null
    private var candidateContainer: LinearLayout? = null

    private var verticalMode: Boolean = false
    private var currentPreedit: HardwarePreedit = HardwarePreedit("")
    private var currentPage: HardwareEnginePage = HardwareEnginePage.EMPTY
    private var lastAnchorView: View? = null
    private val lastCursorRect = RectF()

    // Theme tokens
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF1C1C1E.toInt()
    private var subTextColor: Int = 0xFF8E8E93.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentBgColor: Int = 0xFF007AFF.toInt()
    private var accentTextColor: Int = Color.WHITE

    val isShowing: Boolean get() = overlay.isShowing

    fun copyTouchableRect(out: android.graphics.Rect): Boolean = overlay.copyTouchableRect(out)

    fun setOnBoundsChanged(listener: (() -> Unit)?) {
        overlay.onBoundsChanged = listener
    }

    private var lastColorSchemeKey: String? = null

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        val selectedColor = runCatching { com.hazukieq.hkrime.data.AppPrefs.defaultInstance().looks.selectedColor }.getOrNull()
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
            ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(rawAccentText, 255), ColorUtils.setAlphaComponent(accentBgColor, 255)) >= 2.2
        ) {
            rawAccentText
        } else if (ColorUtils.calculateLuminance(accentBgColor) < 0.5) {
            Color.WHITE
        } else {
            0xFF1C1C1E.toInt()
        }

        rootCard?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(cardBgColor)
            setStroke(dp(1).coerceAtLeast(1), borderColor)
        }
        preeditView?.setTextColor(textColor)
        pageView?.setTextColor(subTextColor)
        dividerView?.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 28))
        if (isShowing) {
            renderContent()
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
        ensurePopup()
        applyTheme()
        renderContent()
        positionPopup(anchorView, lastCursorRect)
    }

    fun updateCursorPosition(anchorView: View?, cursorRect: RectF) {
        lastCursorRect.set(cursorRect)
        if (anchorView != null) lastAnchorView = anchorView
        if (!isShowing) return
        positionPopup(lastAnchorView, lastCursorRect)
    }

    fun dismiss() {
        currentPage = HardwareEnginePage.EMPTY
        currentPreedit = HardwarePreedit("")
        overlay.dismiss()
    }

    private fun ensurePopup() {
        if (rootCard != null && shadowWrapper != null) return

        val pad = dp(SHADOW_PAD_DP)
        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(6).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val preeditTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pageTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), 0, 0, 0)
            isClickable = true
            setOnClickListener { onNextPage?.invoke() }
        }
        header.addView(preeditTv)
        header.addView(pageTv)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        card.addView(header)
        card.addView(divider)
        card.addView(container)
        wrapper.addView(card)

        shadowWrapper = wrapper
        rootCard = card
        preeditView = preeditTv
        pageView = pageTv
        dividerView = divider
        candidateContainer = container
    }

    private fun renderContent() {
        val preeditTv = preeditView ?: return
        val pageTv = pageView ?: return
        val divider = dividerView ?: return
        val container = candidateContainer ?: return

        preeditTv.text = formatIbusPreedit(currentPreedit)
        if (currentPage.hasMultiplePages) {
            val leftArrow = if (currentPage.pageNo > 0) "‹" else "·"
            val rightArrow = if (!currentPage.isLastPage) "›" else "·"
            pageTv.text = "$leftArrow ${currentPage.pageNo + 1} $rightArrow"
            pageTv.visibility = View.VISIBLE
        } else {
            pageTv.text = ""
            pageTv.visibility = View.GONE
        }

        val items = currentPage.items
        divider.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE

        container.removeAllViews()
        container.orientation = if (verticalMode) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        container.layoutParams = LinearLayout.LayoutParams(
            if (verticalMode) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        rootCard?.minimumWidth = if (verticalMode) dp(176) else dp(120)

        items.forEachIndexed { idx, item ->
            val selected = idx == currentPage.highlightedIndex
            val label = currentPage.labelForSlot(idx)
            val itemView = buildCandidateRowView(label, item, selected)
            itemView.setOnClickListener {
                onSelectSlot?.invoke(idx)
            }
            container.addView(itemView)
        }
    }

    /**
     * 仿 PC 端 ibus-rime 的 preedit 視覺：
     * - 已選前綴（0..selStart）：常規主色；
     * - 當前活躍編碼段（selStart..selEnd）：主題高亮色 + 細下劃線；
     * - 游標位置（cursorPos）：插入細豎線 `│` 標示編輯點（隨 ←/→ 實時移動）。
     */
    private fun formatIbusPreedit(p: HardwarePreedit): CharSequence {
        val raw = p.text
        if (raw.isEmpty()) return ""
        val s = p.selStart.coerceIn(0, raw.length)
        val e = p.selEnd.coerceIn(s, raw.length)
        val c = p.cursorPos.coerceIn(0, raw.length)

        val sb = SpannableStringBuilder()
        if (s > 0) {
            val prefixEnd = sb.length + s
            sb.append(raw.substring(0, s))
            sb.setSpan(ForegroundColorSpan(textColor), 0, prefixEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val activeStart = sb.length
        if (e > s) {
            val activeText = raw.substring(s, e)
            sb.append(activeText)
            val activeEnd = sb.length
            sb.setSpan(ForegroundColorSpan(accentBgColor), activeStart, activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), activeStart, activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), activeStart, activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (e < raw.length) {
            val tailStart = sb.length
            sb.append(raw.substring(e))
            sb.setSpan(ForegroundColorSpan(subTextColor), tailStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val caretPos = c.coerceIn(0, sb.length)
        sb.insert(caretPos, "│")
        sb.setSpan(ForegroundColorSpan(accentBgColor), caretPos, caretPos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun buildCandidateRowView(
        label: String,
        item: HardwareEnginePage.Item,
        selected: Boolean,
    ): View {
        val bg = if (selected) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(accentBgColor)
            }
        } else {
            null
        }
        val primaryInk = if (selected) accentTextColor else textColor
        val secondaryInk = if (selected) ColorUtils.setAlphaComponent(accentTextColor, 200) else subTextColor

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            isClickable = true
            isFocusable = false
            if (verticalMode) {
                setPadding(dp(8), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(2) }
            } else {
                setPadding(dp(7), dp(4), dp(7), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            }

            addView(TextView(context).apply {
                text = "$label."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(secondaryInk)
                setPadding(0, 0, dp(4), 0)
            })
            addView(TextView(context).apply {
                text = item.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(primaryInk)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (item.comment.isNotEmpty() && verticalMode) {
                addView(TextView(context).apply {
                    text = item.comment
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(secondaryInk)
                    maxLines = 1
                    setPadding(dp(8), 0, 0, 0)
                })
            }
        }
    }

    private fun positionPopup(anchorView: View?, cursorRect: RectF) {
        val wrapper = shadowWrapper ?: return

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = dp(8)
        val pad = dp(SHADOW_PAD_DP)

        val maxCardW = if (verticalMode) {
            dp(260).coerceAtMost((screenW - margin * 2).coerceAtLeast(dp(180)))
        } else {
            (screenW - margin * 2).coerceAtLeast(dp(120))
        }
        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(maxCardW + pad * 2, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((screenH * 0.6f).toInt(), View.MeasureSpec.AT_MOST),
        )
        val popW = wrapper.measuredWidth
        val popH = wrapper.measuredHeight
        val cardW = (popW - pad * 2).coerceAtLeast(0)
        val cardH = (popH - pad * 2).coerceAtLeast(0)

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
        val x = cardX - pad
        val y = cardY - pad

        overlay.showOrUpdate(wrapper, anchorView, x, y, popW, popH)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        private const val SHADOW_PAD_DP = 10
    }
}
