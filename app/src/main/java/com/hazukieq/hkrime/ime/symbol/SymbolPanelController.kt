package com.hazukieq.hkrime.ime.symbol

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config

/**
 * Native App-side Symbol & Emoji panel:
 * - Fixed, non-theme-editable layout (4×4 grid + right action column + bottom category tabs).
 * - Categories and entries are loaded from plain `.txt` files in `<userDataDir>/symbols/`.
 * - Supports lock toggle (unlocked = auto-close after picking 1 symbol; locked = multi-pick).
 */
class SymbolPanelController(
    private val context: Context,
    private val keyboardView: View,
    private val inputArea: ViewGroup,
    private val panelRoot: View,
    private val gridView: RecyclerView,
    private val tabsView: RecyclerView,
    private val btnBack: TextView,
    private val btnUp: ImageView,
    private val btnDown: ImageView,
    private val btnLock: ImageView,
    private val actionColumn: View,
    private val dividerHorizontal: View,
) {
    var onCommitSymbol: ((String) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    val isShown: Boolean get() = panelRoot.visibility == View.VISIBLE

    private var categories: List<SymbolRepository.SymbolCategory> = emptyList()
    private var activeIndex: Int = 0
    private var isLocked: Boolean = false

    // Theme colors refreshed on show/applyTheme
    private var cellBgColor: Int = 0xFFFFFFFF.toInt()
    private var cellTextColor: Int = 0xFF202124.toInt()
    private var badgeTextColor: Int = 0xFF80868B.toInt()
    private var actionBgColor: Int = 0xFFE8EAED.toInt()
    private var tabBarBgColor: Int = 0xFFE8EAED.toInt()
    private var tabActiveBgColor: Int = 0xFFDADCE0.toInt()
    private var accentColor: Int = 0xFF1A73E8.toInt()
    private var dividerColor: Int = 0xFFDADCE0.toInt()

    private val gridLayoutManager = GridLayoutManager(context, GRID_COLS)
    private val tabLayoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private val gridAdapter = SymbolGridAdapter()
    private val tabAdapter = CategoryTabAdapter()
    private val gridDivider = GridLineDecoration()

    init {
        gridView.layoutManager = gridLayoutManager
        gridView.adapter = gridAdapter
        gridView.itemAnimator = null
        gridView.addItemDecoration(gridDivider)

        tabsView.layoutManager = tabLayoutManager
        tabsView.adapter = tabAdapter
        tabsView.itemAnimator = null

        btnBack.setOnClickListener { hide() }
        btnUp.setOnClickListener { pageScroll(-1) }
        btnDown.setOnClickListener { pageScroll(1) }
        btnLock.setOnClickListener {
            isLocked = !isLocked
            updateLockButton()
        }

        // Recompute row height whenever grid height changes so exactly 4 full rows fill the area
        gridView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            if (h > 0 && h != (oldBottom - oldTop)) {
                gridAdapter.rowHeightPx = (h / GRID_ROWS).coerceAtLeast(dp(42))
                gridAdapter.notifyDataSetChanged()
            }
        }
    }

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull() ?: return
        cellBgColor = cfg.getColor("key_back_color") ?: 0xFFFFFFFF.toInt()
        cellTextColor = cfg.getColor("key_text_color") ?: 0xFF202124.toInt()
        badgeTextColor = cfg.getColor("key_symbol_color")
            ?: ColorUtils.setAlphaComponent(cellTextColor, 140)
        actionBgColor = cfg.getColor("benter")
            ?: cfg.getColor("off_key_back_color")
            ?: 0xFFE8EAED.toInt()
        tabBarBgColor = cfg.getColor("keyboard_back_color")
            ?: actionBgColor
        accentColor = cfg.getColor("hilited_key_back_color") ?: 0xFF1A73E8.toInt()
        dividerColor = cfg.getColor("key_border_color")
            ?: ColorUtils.setAlphaComponent(cellTextColor, 36)
        tabActiveBgColor = ColorUtils.blendARGB(tabBarBgColor, cellTextColor, 0.14f)

        panelRoot.setBackgroundColor(cellBgColor)
        gridView.setBackgroundColor(cellBgColor)
        actionColumn.setBackgroundColor(actionBgColor)
        tabsView.setBackgroundColor(tabBarBgColor)
        dividerHorizontal.setBackgroundColor(dividerColor)

        val actionStroke = dividerColor
        btnBack.setTextColor(cellTextColor)
        btnBack.background = makeCellBackground(actionBgColor, actionStroke)
        val iconTint = ColorStateList.valueOf(cellTextColor)
        for (btn in listOf(btnUp, btnDown)) {
            ImageViewCompat.setImageTintList(btn, iconTint)
            btn.background = makeCellBackground(actionBgColor, actionStroke)
        }
        updateLockButton()
        gridDivider.paint.color = dividerColor
        gridAdapter.notifyDataSetChanged()
        tabAdapter.notifyDataSetChanged()
    }

    /**
     * Opens the symbol panel, reloading `.txt` categories from disk so any newly added
     * user `.txt` files or symbols appear immediately.
     */
    fun show(initialCategory: String? = null) {
        val prefs = AppPrefs.defaultInstance()
        val userDir = prefs.conf.userDataDir
        categories = SymbolRepository.loadCategories(
            context = context,
            userDataDir = userDir,
            order = prefs.looks.symbolTabOrder,
            hidden = prefs.looks.symbolTabHidden,
            includeHidden = false,
        )
        if (categories.isEmpty()) return

        applyTheme()

        if (!initialCategory.isNullOrBlank()) {
            val found = categories.indexOfFirst {
                it.title.equals(initialCategory, ignoreCase = true) ||
                    it.title.contains(initialCategory, ignoreCase = true)
            }
            if (found >= 0) activeIndex = found
        }
        if (activeIndex !in categories.indices) activeIndex = 0

        // Match main keyboard height so opening/closing causes zero viewport jump
        val targetH = keyboardView.height.takeIf { it > 0 } ?: dp(250)
        panelRoot.layoutParams = panelRoot.layoutParams.apply { height = targetH }
        inputArea.minimumHeight = targetH

        keyboardView.visibility = View.INVISIBLE
        panelRoot.visibility = View.VISIBLE

        selectCategory(activeIndex, scrollTab = true)
    }

    fun hide() {
        if (!isShown) return
        panelRoot.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        inputArea.minimumHeight = 0
        onDismissed?.invoke()
    }

    private fun selectCategory(index: Int, scrollTab: Boolean = false) {
        if (index !in categories.indices) return
        activeIndex = index
        gridAdapter.items = categories[index].items
        gridAdapter.notifyDataSetChanged()
        gridView.scrollToPosition(0)
        tabAdapter.notifyDataSetChanged()
        if (scrollTab) {
            tabsView.scrollToPosition(index)
        }
    }

    private fun pageScroll(direction: Int) {
        val h = gridView.height
        if (h <= 0) return
        gridView.smoothScrollBy(0, direction * h)
    }

    private fun updateLockButton() {
        btnLock.setImageResource(
            if (isLocked) R.drawable.ic_symbol_lock_closed else R.drawable.ic_symbol_lock_open,
        )
        if (isLocked) {
            val bg = ColorUtils.blendARGB(actionBgColor, accentColor, 0.22f)
            btnLock.background = makeCellBackground(bg, dividerColor)
            ImageViewCompat.setImageTintList(btnLock, ColorStateList.valueOf(accentColor))
        } else {
            btnLock.background = makeCellBackground(actionBgColor, dividerColor)
            ImageViewCompat.setImageTintList(btnLock, ColorStateList.valueOf(cellTextColor))
        }
    }

    private fun makeCellBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(1, strokeColor)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    // -------------------------------------------------------------------------
    // Grid Adapter (4 columns × 4 visible rows)
    // -------------------------------------------------------------------------
    private inner class SymbolGridAdapter : RecyclerView.Adapter<SymbolGridAdapter.VH>() {
        var items: List<SymbolRepository.SymbolItem> = emptyList()
        var rowHeightPx: Int = dp(52)

        inner class VH(
            val container: FrameLayout,
            val label: TextView,
            val badge: TextView,
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rowHeightPx,
                )
                isClickable = true
                isFocusable = true
            }
            val label = TextView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
            }
            val badge = TextView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    topMargin = dp(3)
                    marginEnd = dp(4)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            }
            container.addView(label)
            container.addView(badge)
            return VH(container, label, badge)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val lp = holder.container.layoutParams
            if (lp.height != rowHeightPx) {
                lp.height = rowHeightPx
                holder.container.layoutParams = lp
            }
            holder.container.setBackgroundColor(cellBgColor)
            holder.label.text = item.text
            holder.label.setTextColor(cellTextColor)

            // Auto-scale font size cleanly for single symbols / emojis / .com / kaomoji
            val len = item.text.length
            val sp = when {
                len <= 2 -> 21f
                len <= 4 -> 16f
                len <= 7 -> 13f
                else -> 11f
            }
            holder.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)

            if (item.badge != null) {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = item.badge
                holder.badge.setTextColor(badgeTextColor)
            } else {
                holder.badge.visibility = View.GONE
            }

            holder.container.setOnClickListener {
                onCommitSymbol?.invoke(item.text)
                if (!isLocked) hide()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bottom Category Tabs Adapter
    // -------------------------------------------------------------------------
    private inner class CategoryTabAdapter : RecyclerView.Adapter<CategoryTabAdapter.VH>() {
        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                minWidth = dp(74)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
            }
            return VH(tv)
        }

        override fun getItemCount(): Int = categories.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cat = categories[position]
            val selected = position == activeIndex
            holder.text.text = "${position + 1}.${cat.title}"
            holder.text.setTextColor(cellTextColor)
            holder.text.setBackgroundColor(if (selected) tabActiveBgColor else tabBarBgColor)
            holder.text.setOnClickListener { selectCategory(position) }
        }
    }

    // -------------------------------------------------------------------------
    // Crisp 1px Grid Lines (matches native IME symbol panel aesthetic)
    // -------------------------------------------------------------------------
    private class GridLineDecoration : RecyclerView.ItemDecoration() {
        val paint = Paint().apply {
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val count = parent.childCount
            for (i in 0 until count) {
                val child = parent.getChildAt(i)
                val l = child.left.toFloat()
                val t = child.top.toFloat()
                val r = child.right.toFloat()
                val b = child.bottom.toFloat()
                // Bottom & right hairlines
                c.drawLine(l, b, r, b, paint)
                c.drawLine(r, t, r, b, paint)
            }
        }
    }

    companion object {
        private const val GRID_COLS = 4
        private const val GRID_ROWS = 4

        /**
         * Maps legacy keyboard names (`symbols`, `bqrw1`, `bqfh1`, `bqfh3`, `kao_happy`, etc.)
         * to the corresponding symbol panel tab title, or null if not a symbol/emoji target.
         */
        @JvmStatic
        fun mapSelectToInitialTab(select: String?): String? {
            if (select.isNullOrEmpty()) return null
            return when {
                select == "symbols" || select == "symbolsnum" || select == "bqfh1" || select == "bqfh2" -> "常用"
                select == "bqfh3" -> "英文"
                select == "emoji" || select.startsWith("bq") -> "表情"
                select == "kaomoji" || select.startsWith("kao_") -> "颜文"
                else -> null
            }
        }
    }
}
