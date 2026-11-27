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
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.clipboard.ClipboardStore

/**
 * 物理鍵盤接手模式下的「跟隨光標懸浮剪切板歷史窗」（快捷鍵 `Ctrl+Shift+V`）：
 * - 直接複用 [ClipboardStore] 本地持久化歷史與置頂狀態；
 * - 全鍵盤操作：`↑`/`↓` 移動選中、`Enter`/`Space`/`1-9` 貼上、`Del`/`BackSpace` 刪除、`P` 置頂、`Esc` 關閉；
 * - 同時支援點擊貼上；自動跟隨當前 IME 主題配色。
 */
class HardwareClipboardPopup(
    private val context: Context,
    private val store: ClipboardStore,
) {
    var onCommitText: ((String) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private val overlay = HardwareOverlayHost(context, touchable = true, dismissOnOutsideTouch = true).apply {
        onOutsideTouch = { dismiss() }
    }
    private var shadowWrapper: FrameLayout? = null
    private var rootCard: LinearLayout? = null
    private var titleView: TextView? = null
    private var countView: TextView? = null
    private var dividerView: View? = null
    private var listView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var hintView: TextView? = null

    private var entries: List<ClipboardStore.Entry> = emptyList()
    private var selectedIndex: Int = 0
    private var lastAnchorView: View? = null
    private val lastCursorRect = RectF()
    private var lastColorSchemeKey: String? = null

    // Theme tokens
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF1C1C1E.toInt()
    private var subTextColor: Int = 0xFF8E8E93.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentBgColor: Int = 0xFF007AFF.toInt()
    private var accentTextColor: Int = Color.WHITE

    private val adapter = ClipAdapter()

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
        titleView?.setTextColor(textColor)
        countView?.setTextColor(subTextColor)
        dividerView?.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 26))
        emptyView?.setTextColor(subTextColor)
        hintView?.setTextColor(subTextColor)
        if (isShowing) {
            adapter.notifyDataSetChanged()
        }
    }

    fun show(anchorView: View?, cursorRect: RectF) {
        store.syncFromSystem(context)
        selectedIndex = 0
        lastAnchorView = anchorView
        lastCursorRect.set(cursorRect)

        ensurePopup()
        applyTheme()
        refreshList()
        positionPopup(anchorView, lastCursorRect)
    }

    fun updateCursorPosition(anchorView: View?, cursorRect: RectF) {
        lastCursorRect.set(cursorRect)
        if (anchorView != null) lastAnchorView = anchorView
        if (!isShowing) return
        positionPopup(lastAnchorView, lastCursorRect)
    }

    fun dismiss() {
        val wasShowing = isShowing
        overlay.dismiss()
        if (wasShowing) {
            onDismissed?.invoke()
        }
    }

    fun handleKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        when {
            keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK -> {
                dismiss()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_SPACE -> {
                val item = entries.getOrNull(selectedIndex)
                if (item != null) {
                    commitAndClose(item.text)
                } else {
                    dismiss()
                }
                return true
            }
            keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 && !event.isCtrlPressed && !event.isAltPressed -> {
                val idx = keyCode - KeyEvent.KEYCODE_1
                val item = entries.getOrNull(idx)
                if (item != null) {
                    commitAndClose(item.text)
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> {
                val item = entries.getOrNull(selectedIndex)
                if (item != null) {
                    store.delete(item.id, context)
                    refreshList()
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_P && !event.isCtrlPressed && !event.isAltPressed -> {
                val item = entries.getOrNull(selectedIndex)
                if (item != null) {
                    store.togglePin(item.id)
                    refreshList()
                }
                return true
            }
            else -> return true
        }
    }

    private fun moveSelection(delta: Int) {
        if (entries.isEmpty()) return
        val next = (selectedIndex + delta).coerceIn(0, entries.lastIndex)
        if (next == selectedIndex) return
        val old = selectedIndex
        selectedIndex = next
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(next)
        listView?.scrollToPosition(next)
    }

    private fun commitAndClose(text: String) {
        dismiss()
        onCommitText?.invoke(text)
    }

    private fun refreshList() {
        entries = store.entries()
        selectedIndex = if (entries.isEmpty()) 0 else selectedIndex.coerceIn(0, entries.lastIndex)
        countView?.text = if (entries.isNotEmpty()) "${entries.size} 条" else ""
        if (entries.isEmpty()) {
            listView?.visibility = View.GONE
            emptyView?.visibility = View.VISIBLE
        } else {
            listView?.visibility = View.VISIBLE
            emptyView?.visibility = View.GONE
        }
        adapter.submit(entries)
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
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = FrameLayout.LayoutParams(dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val titleTv = TextView(context).apply {
            text = "剪切板"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        header.addView(titleTv)
        header.addView(countTv)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HardwareClipboardPopup.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(LIST_HEIGHT_DP),
            )
        }

        val emptyTv = TextView(context).apply {
            text = "暂无剪切板记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88),
            )
        }

        val hint = TextView(context).apply {
            text = "↑↓/1-9 粘贴 · P 置顶 · Del 删除 · Esc 关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.END
            setPadding(0, dp(5), dp(2), 0)
        }

        card.addView(header)
        card.addView(divider)
        card.addView(rv)
        card.addView(emptyTv)
        card.addView(hint)
        wrapper.addView(card)

        shadowWrapper = wrapper
        rootCard = card
        titleView = titleTv
        countView = countTv
        dividerView = divider
        listView = rv
        emptyView = emptyTv
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

    private inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.VH>() {
        private var items: List<ClipboardStore.Entry> = emptyList()

        fun submit(newItems: List<ClipboardStore.Entry>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(
            val row: LinearLayout,
            val numTv: TextView,
            val textTv: TextView,
            val pinTv: TextView,
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(2) }
            }
            val numTv = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, dp(6), 0)
            }
            val textTv = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pinTv = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(6), 0, 0, 0)
            }
            row.addView(numTv)
            row.addView(textTv)
            row.addView(pinTv)
            return VH(row, numTv, textTv, pinTv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val selected = position == selectedIndex
            val primaryInk = if (selected) accentTextColor else textColor
            val secondaryInk = if (selected) ColorUtils.setAlphaComponent(accentTextColor, 200) else subTextColor

            holder.row.background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(accentBgColor)
                }
            } else {
                null
            }
            holder.numTv.text = if (position < 9) "${position + 1}." else "·"
            holder.numTv.setTextColor(secondaryInk)
            holder.textTv.text = item.text.replace('\n', ' ')
            holder.textTv.setTextColor(primaryInk)
            holder.pinTv.text = if (item.pinned) "置顶" else ""
            holder.pinTv.setTextColor(secondaryInk)
            holder.pinTv.visibility = if (item.pinned) View.VISIBLE else View.GONE
            holder.row.setOnClickListener {
                commitAndClose(item.text)
            }
        }
    }

    companion object {
        private const val CARD_WIDTH_DP = 340
        private const val LIST_HEIGHT_DP = 224
        private const val SHADOW_PAD_DP = 10
    }
}
