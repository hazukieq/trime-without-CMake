package com.hazukieq.hkrime.ime.hardware

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.symbol.SymbolRepository

/**
 * 物理鍵盤接手模式下的「跟隨光標懸浮符號/表情選擇器」（快捷鍵 `Ctrl+Shift+E`）：
 * - 透過 [HardwareOverlayHost] 渲染於 `InputMethod` 透明畫布（不共用觸屏 `PopupWindow`）；
 * - 直接複用 [SymbolRepository] 與用戶在設定裡自定義的符號分類及排序；
 * - 全鍵盤操作：`Tab`/`Shift+Tab` 切分類、方向鍵移動選中、`Enter`/`Space`/`1-9` 上屏、`Esc` 關閉；
 * - 同時支援點擊切換分類與點擊上屏；自動跟隨當前 IME 主題配色。
 */
class HardwareSymbolOverlay(
    private val context: Context,
) {
    var onCommitSymbol: ((String) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private val overlay = HardwareOverlayHost(context, touchable = true, dismissOnOutsideTouch = true).apply {
        onOutsideTouch = { dismiss() }
    }
    private var shadowWrapper: FrameLayout? = null
    private var rootCard: LinearLayout? = null
    private var tabScroll: HorizontalScrollView? = null
    private var tabStrip: LinearLayout? = null
    private var dividerView: View? = null
    private var gridView: RecyclerView? = null
    private var hintView: TextView? = null

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile
    private var categories: List<SymbolRepository.SymbolCategory> = emptyList()
    @Volatile
    private var cachedCategoriesKey: String? = null
    @Volatile
    private var prewarming: Boolean = false
    private var activeTabIndex: Int = 0
    private var selectedIndex: Int = 0
    private var spanCount: Int = 7
    private var lastAnchorView: View? = null
    private val lastCursorRect = RectF()
    private var lastColorSchemeKey: String? = null
    private val restoreHintRunnable = Runnable { restoreDefaultHint() }

    // Theme tokens
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF1C1C1E.toInt()
    private var subTextColor: Int = 0xFF8E8E93.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentBgColor: Int = 0xFF007AFF.toInt()
    private var accentTextColor: Int = Color.WHITE

    private val adapter = GridAdapter()

    val isShowing: Boolean get() = overlay.isShowing

    fun copyTouchableRect(out: android.graphics.Rect): Boolean = overlay.copyTouchableRect(out)

    fun setOnBoundsChanged(listener: (() -> Unit)?) {
        overlay.onBoundsChanged = listener
    }

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        val selectedColor = runCatching { AppPrefs.defaultInstance().looks.selectedColor }.getOrNull()
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
            ColorUtils.setAlphaComponent(textColor, 150)
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

        rootCard?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(cardBgColor)
            setStroke(dp(1).coerceAtLeast(1), borderColor)
        }
        dividerView?.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 26))
        hintView?.setTextColor(subTextColor)
        if (isShowing) {
            renderTabs()
            adapter.notifyDataSetChanged()
        }
    }

    /** 在背景預熱符號分類表，避免首次按 Ctrl+Shift+E 時同步讀檔卡住主線程。 */
    fun prewarm() {
        val prefs = AppPrefs.defaultInstance()
        val cacheKey = "${prefs.conf.userDataDir}|${prefs.looks.symbolTabOrder}|${prefs.looks.symbolTabHidden}"
        if ((categories.isNotEmpty() && cachedCategoriesKey == cacheKey) || prewarming) return
        prewarming = true
        Thread({
            try {
                val loaded = SymbolRepository.loadCategories(
                    context = context,
                    userDataDir = prefs.conf.userDataDir,
                    order = prefs.looks.symbolTabOrder,
                    hidden = prefs.looks.symbolTabHidden,
                    includeHidden = false,
                )
                categories = loaded
                cachedCategoriesKey = cacheKey
            } finally {
                prewarming = false
            }
        }, "hw-symbol-prewarm").start()
    }

    fun showTemporaryHint(message: String, durationMs: Long = TEMP_HINT_DURATION_MS) {
        val tv = hintView ?: return
        uiHandler.removeCallbacks(restoreHintRunnable)
        val amber = if (ColorUtils.calculateLuminance(textColor) > 0.5) {
            0xFFFBBF24.toInt()
        } else {
            0xFFD97706.toInt()
        }
        tv.text = message
        tv.setTextColor(amber)
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        uiHandler.postDelayed(restoreHintRunnable, durationMs)
    }

    private fun restoreDefaultHint() {
        val tv = hintView ?: return
        tv.text = DEFAULT_HINT_TEXT
        tv.setTextColor(subTextColor)
        tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
    }

    fun show(anchorView: View?, cursorRect: RectF) {
        uiHandler.removeCallbacks(restoreHintRunnable)
        val prefs = AppPrefs.defaultInstance()
        val cacheKey = "${prefs.conf.userDataDir}|${prefs.looks.symbolTabOrder}|${prefs.looks.symbolTabHidden}"
        if (categories.isEmpty() || cachedCategoriesKey != cacheKey) {
            categories = SymbolRepository.loadCategories(
                context = context,
                userDataDir = prefs.conf.userDataDir,
                order = prefs.looks.symbolTabOrder,
                hidden = prefs.looks.symbolTabHidden,
                includeHidden = false,
            )
            cachedCategoriesKey = cacheKey
        }
        if (categories.isEmpty()) return
        activeTabIndex = activeTabIndex.coerceIn(0, categories.lastIndex)
        selectedIndex = 0
        lastAnchorView = anchorView
        lastCursorRect.set(cursorRect)

        ensurePopup()
        applyTheme()
        restoreDefaultHint()
        bindActiveCategory()
        positionPopup(anchorView, lastCursorRect)
    }

    fun updateCursorPosition(anchorView: View?, cursorRect: RectF) {
        lastCursorRect.set(cursorRect)
        if (anchorView != null) lastAnchorView = anchorView
        if (!isShowing) return
        positionPopup(lastAnchorView, lastCursorRect)
    }

    fun dismiss() {
        uiHandler.removeCallbacks(restoreHintRunnable)
        val wasShowing = isShowing
        overlay.dismiss()
        if (wasShowing) {
            onDismissed?.invoke()
        }
    }

    /** 在符號懸浮窗打開期間接管方向鍵、Tab、數字、回車與 Esc。 */
    fun handleKeyDown(event: KeyEvent): Boolean {
        val items = currentItems()
        val keyCode = event.keyCode
        when {
            keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK -> {
                dismiss()
                return true
            }
            keyCode == KeyEvent.KEYCODE_TAB -> {
                shiftCategory(if (event.isShiftPressed) -1 else 1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET ||
                keyCode == KeyEvent.KEYCODE_MINUS ||
                keyCode == KeyEvent.KEYCODE_PAGE_UP -> {
                shiftCategory(-1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET ||
                keyCode == KeyEvent.KEYCODE_EQUALS ||
                keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> {
                shiftCategory(1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveSelection(1, items.size)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveSelection(-1, items.size)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(spanCount, items.size)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-spanCount, items.size)
                return true
            }
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_SPACE -> {
                val item = items.getOrNull(selectedIndex)
                if (item != null) {
                    commitAndClose(item.text)
                } else {
                    dismiss()
                }
                return true
            }
            keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 && !event.isCtrlPressed && !event.isAltPressed -> {
                val idx = keyCode - KeyEvent.KEYCODE_1
                val item = items.getOrNull(idx)
                if (item != null) {
                    commitAndClose(item.text)
                }
                return true
            }
            else -> return true // 打開期間吞掉其他按鍵，避免誤打進宿主或 Rime
        }
    }

    private fun shiftCategory(delta: Int) {
        if (categories.size <= 1) return
        activeTabIndex = (activeTabIndex + delta + categories.size) % categories.size
        selectedIndex = 0
        bindActiveCategory()
    }

    private fun moveSelection(delta: Int, total: Int) {
        if (total <= 0) return
        val next = (selectedIndex + delta).coerceIn(0, total - 1)
        if (next == selectedIndex) return
        val old = selectedIndex
        selectedIndex = next
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(next)
        gridView?.scrollToPosition(next)
    }

    private fun commitAndClose(text: String) {
        dismiss()
        onCommitSymbol?.invoke(text)
    }

    private fun currentItems(): List<SymbolRepository.SymbolItem> =
        categories.getOrNull(activeTabIndex)?.items.orEmpty()

    private fun bindActiveCategory() {
        val items = currentItems()
        val maxLen = items.take(24).maxOfOrNull { it.text.length } ?: 1
        spanCount = when {
            maxLen >= 5 -> 3
            maxLen >= 3 -> 4
            else -> 7
        }
        (gridView?.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        adapter.submit(items)
        gridView?.scrollToPosition(0)
        renderTabs()
    }

    private fun renderTabs() {
        val strip = tabStrip ?: return
        strip.removeAllViews()
        var activeView: View? = null
        categories.forEachIndexed { idx, cat ->
            val selected = idx == activeTabIndex
            val tv = TextView(context).apply {
                text = cat.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) accentTextColor else subTextColor)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = if (selected) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(7).toFloat()
                        setColor(accentBgColor)
                    }
                } else {
                    null
                }
                isFocusable = false
                isFocusableInTouchMode = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusable = View.NOT_FOCUSABLE
                }
                isClickable = true
                setOnClickListener {
                    if (activeTabIndex != idx) {
                        activeTabIndex = idx
                        selectedIndex = 0
                        bindActiveCategory()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            }
            if (selected) activeView = tv
            strip.addView(tv)
        }
        activeView?.let { target ->
            tabScroll?.post {
                val x = (target.left - dp(24)).coerceAtLeast(0)
                tabScroll?.smoothScrollTo(x, 0)
            }
        }
    }

    private fun ensurePopup() {
        if (rootCard != null && shadowWrapper != null) return
        val pad = dp(SHADOW_PAD_DP)
        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(6).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = FrameLayout.LayoutParams(dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(strip)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        val rv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, spanCount)
            adapter = this@HardwareSymbolOverlay.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(GRID_HEIGHT_DP),
            )
        }

        val hint = TextView(context).apply {
            text = "[ ] / - = 切分类 · 方向键选 · Enter 上屏 · Esc 关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.END
            setPadding(0, dp(5), dp(2), 0)
        }

        card.addView(scroll)
        card.addView(divider)
        card.addView(rv)
        card.addView(hint)
        wrapper.addView(card)

        shadowWrapper = wrapper
        rootCard = card
        tabScroll = scroll
        tabStrip = strip
        dividerView = divider
        gridView = rv
        hintView = hint
    }

    private fun positionPopup(anchorView: View?, cursorRect: RectF) {
        val wrapper = shadowWrapper ?: return
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = dp(8)
        val pad = dp(SHADOW_PAD_DP)

        val cardW = dp(CARD_WIDTH_DP).coerceAtMost((screenW - margin * 2).coerceAtLeast(dp(240)))
        rootCard?.layoutParams = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(cardW + pad * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((screenH * 0.65f).toInt(), View.MeasureSpec.AT_MOST),
        )
        val popW = wrapper.measuredWidth
        val popH = wrapper.measuredHeight
        val innerH = (popH - pad * 2).coerceAtLeast(0)

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
            cardY = if (belowY + innerH <= screenH - dp(16)) {
                belowY
            } else {
                (cursorRect.top.toInt() - innerH - dp(6)).coerceAtLeast(dp(16))
            }
        } else {
            cardX = ((screenW - cardW) / 2).coerceAtLeast(margin)
            cardY = ((screenH - innerH) / 2).coerceAtLeast(dp(24))
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

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        private var items: List<SymbolRepository.SymbolItem> = emptyList()

        fun submit(newItems: List<SymbolRepository.SymbolItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(7), dp(4), dp(7))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    val m = dp(2)
                    setMargins(m, m, m, m)
                }
            }
            return VH(tv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val selected = position == selectedIndex
            holder.tv.text = item.text
            holder.tv.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (item.text.length >= 4) 12.5f else 17f,
            )
            holder.tv.setTextColor(if (selected) accentTextColor else textColor)
            holder.tv.background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(7).toFloat()
                    setColor(accentBgColor)
                }
            } else {
                null
            }
            holder.tv.setOnClickListener {
                commitAndClose(item.text)
            }
        }
    }

    companion object {
        private const val CARD_WIDTH_DP = 340
        private const val GRID_HEIGHT_DP = 216
        private const val SHADOW_PAD_DP = 10
        private const val TEMP_HINT_DURATION_MS = 3500L
        private const val DEFAULT_HINT_TEXT = "[ ] / - = 切分类 · 方向键选 · Enter 上屏 · Esc 关闭"
    }
}
