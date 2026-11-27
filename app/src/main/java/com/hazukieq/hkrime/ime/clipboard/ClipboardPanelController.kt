package com.hazukieq.hkrime.ime.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.Config

/**
 * 原生剪切板面板（視覺與文字編輯面板、候選展開面板完全統一）：
 * - 自動跟隨當前 IME 主題配色（`keyboard_back_color` / `key_back_color` / `key_text_color` / `hilited_key_back_color`）；
 * - 頂部欄與 `EditPanel` 保持一致（`< 剪切板` + 右側「清空」膠囊）；
 * - 每條記錄以圓角按鍵卡片呈現：單擊直接上屏，右側提供置頂與刪除操作。
 */
class ClipboardPanelController(
    private val context: Context,
    private val store: ClipboardStore,
    private val keyboardView: View,
    private val inputArea: ViewGroup,
    private val panelRoot: View,
    private val btnBack: View,
    private val iconBack: ImageView,
    private val titleBack: TextView,
    private val btnClear: TextView,
    private val clearConfirmBar: View,
    private val clearConfirmLabel: TextView,
    private val btnClearCancel: TextView,
    private val btnClearOk: TextView,
    private val topDivider: View,
    private val listView: RecyclerView,
    private val emptyView: LinearLayout,
    private val emptyTitle: TextView,
    private val emptyHint: TextView,
) {
    var onCommitText: ((String) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    val isShown: Boolean get() = panelRoot.visibility == View.VISIBLE

    private var entries: List<ClipboardStore.Entry> = emptyList()
    private val adapter = ClipboardAdapter()
    private var confirmingClear: Boolean = false

    // Theme colors synced from active IME Config
    private var panelBgColor: Int = 0xFFF1F3F4.toInt()
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF202124.toInt()
    private var subTextColor: Int = 0xFF5F6368.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentColor: Int = 0xFF007AFF.toInt()
    private var actionBgColor: Int = 0xFFE8EAED.toInt()
    private val dangerColor: Int = 0xFFFF3B30.toInt()

    init {
        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter
        listView.itemAnimator = null

        btnBack.setOnClickListener { hide() }
        btnClear.setOnClickListener {
            if (!store.hasUnpinned()) return@setOnClickListener
            confirmingClear = true
            updateClearBarVisibility()
        }
        btnClearCancel.setOnClickListener {
            confirmingClear = false
            updateClearBarVisibility()
        }
        btnClearOk.setOnClickListener {
            confirmingClear = false
            store.clearUnpinned(context)
            refresh()
        }
        applyTheme()
    }

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        panelBgColor = cfg?.getColor("keyboard_back_color") ?: 0xFFF1F3F4.toInt()
        cardBgColor = cfg?.getColor("key_back_color") ?: 0xFFFFFFFF.toInt()
        textColor = cfg?.getColor("key_text_color") ?: 0xFF202124.toInt()
        subTextColor = ColorUtils.setAlphaComponent(textColor, 145)
        borderColor = cfg?.getColor("key_border_color")
            ?: cfg?.getColor("border_color")
            ?: ColorUtils.setAlphaComponent(textColor, 36)
        accentColor = cfg?.getColor("hilited_key_back_color") ?: 0xFF007AFF.toInt()
        actionBgColor = cfg?.getColor("off_key_back_color")
            ?: ColorUtils.blendARGB(panelBgColor, textColor, 0.08f)

        panelRoot.setBackgroundColor(panelBgColor)
        titleBack.setTextColor(textColor)
        ImageViewCompat.setImageTintList(iconBack, ColorStateList.valueOf(textColor))
        topDivider.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 26))

        btnClear.setTextColor(textColor)
        btnClear.background = makeCardBackground(actionBgColor, borderColor, dp(14).toFloat())

        clearConfirmLabel.setTextColor(subTextColor)
        btnClearCancel.setTextColor(textColor)
        btnClearCancel.background = makeCardBackground(actionBgColor, borderColor, dp(13).toFloat())
        btnClearOk.setTextColor(0xFFFFFFFF.toInt())
        btnClearOk.background = makeCardBackground(dangerColor, 0, dp(13).toFloat())

        emptyTitle.setTextColor(textColor)
        emptyHint.setTextColor(subTextColor)

        listView.recycledViewPool.clear()
        adapter.notifyDataSetChanged()
    }

    fun show() {
        confirmingClear = false
        store.syncFromSystem(context)
        applyTheme()
        refresh()

        val kbHeight = when {
            keyboardView.height > 0 -> keyboardView.height
            keyboardView.measuredHeight > 0 -> keyboardView.measuredHeight
            else -> dp(250)
        }
        inputArea.layoutParams?.let { lp ->
            lp.height = kbHeight
            inputArea.requestLayout()
        }
        panelRoot.layoutParams?.let { lp ->
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            panelRoot.requestLayout()
        }

        keyboardView.visibility = View.GONE
        panelRoot.visibility = View.VISIBLE
        listView.scrollToPosition(0)
    }

    fun hide() {
        if (!isShown) return
        confirmingClear = false
        panelRoot.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        inputArea.layoutParams?.let { lp ->
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            inputArea.requestLayout()
        }
        onDismissed?.invoke()
    }

    fun toggle() {
        if (isShown) hide() else show()
    }

    fun refresh() {
        entries = store.entries()
        adapter.notifyDataSetChanged()
        val isEmpty = entries.isEmpty()
        listView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (!store.hasUnpinned()) confirmingClear = false
        updateClearBarVisibility()
    }

    private fun updateClearBarVisibility() {
        val canClear = store.hasUnpinned()
        btnClear.visibility = if (canClear && !confirmingClear) View.VISIBLE else View.GONE
        clearConfirmBar.visibility = if (canClear && confirmingClear) View.VISIBLE else View.GONE
    }

    private inner class ClipboardAdapter : RecyclerView.Adapter<ClipboardAdapter.VH>() {

        inner class VH(
            val card: LinearLayout,
            val textArea: LinearLayout,
            val pinBadge: TextView,
            val bodyText: TextView,
            val btnPin: ImageView,
            val btnDelete: ImageView,
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6)
                }
                setPadding(dp(12), dp(9), dp(8), dp(9))
                isClickable = true
                isFocusable = true
            }

            val textArea = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pinBadge = TextView(parent.context).apply {
                text = "置顶"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(3) }
            }

            val bodyText = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(2).toFloat(), 1f)
            }

            textArea.addView(pinBadge)
            textArea.addView(bodyText)

            fun actionIcon(resId: Int, desc: String): ImageView = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginStart = dp(4)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setImageResource(resId)
                contentDescription = desc
                isClickable = true
                isFocusable = true
            }

            val btnPin = actionIcon(R.drawable.ic_clip_pin, "置顶")
            val btnDelete = actionIcon(R.drawable.ic_clip_delete, "删除")

            card.addView(textArea)
            card.addView(btnPin)
            card.addView(btnDelete)
            return VH(card, textArea, pinBadge, bodyText, btnPin, btnDelete)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = entries[position]
            val cardStroke = if (item.pinned) ColorUtils.setAlphaComponent(accentColor, 110) else borderColor
            holder.card.background = makeCardBackground(cardBgColor, cardStroke, dp(10).toFloat())

            holder.bodyText.setTextColor(textColor)
            holder.bodyText.text = item.text.trim()

            if (item.pinned) {
                holder.pinBadge.visibility = View.VISIBLE
                holder.pinBadge.setTextColor(accentColor)
                holder.pinBadge.background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(ColorUtils.setAlphaComponent(accentColor, 30))
                }
            } else {
                holder.pinBadge.visibility = View.GONE
            }

            val pinTint = if (item.pinned) accentColor else subTextColor
            val pinBg = if (item.pinned) ColorUtils.setAlphaComponent(accentColor, 26) else actionBgColor
            ImageViewCompat.setImageTintList(holder.btnPin, ColorStateList.valueOf(pinTint))
            holder.btnPin.background = makeCardBackground(pinBg, 0, dp(8).toFloat())

            ImageViewCompat.setImageTintList(holder.btnDelete, ColorStateList.valueOf(subTextColor))
            holder.btnDelete.background = makeCardBackground(actionBgColor, 0, dp(8).toFloat())

            holder.card.setOnClickListener {
                onCommitText?.invoke(item.text)
            }
            holder.btnPin.setOnClickListener {
                if (store.togglePin(item.id)) refresh()
            }
            holder.btnDelete.setOnClickListener {
                if (store.delete(item.id, context)) refresh()
            }
        }
    }

    private fun makeCardBackground(normalColor: Int, strokeColor: Int, radiusPx: Float): StateListDrawable {
        val pressedColor = ColorUtils.blendARGB(normalColor, textColor, 0.12f)
        val strokePx = if (strokeColor != 0) dp(1).coerceAtLeast(1) else 0
        fun shape(fill: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor))
            addState(IntArray(0), shape(normalColor))
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        fun isClipboardSelect(select: String?): Boolean {
            if (select.isNullOrBlank()) return false
            val s = select.trim().lowercase()
            return s == "clipboard" || s == "clipboard_panel"
        }
    }
}
