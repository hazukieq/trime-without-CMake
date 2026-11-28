package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.core.twelvekey.InputCodeSpan
import com.hazukieq.hkrime.data.Config

/**
 * 候選展開面板右側的流式候選（配合 FlexboxLayoutManager）：只讀快照，整表 submitList 替換。
 *
 * 設計：
 * - 格子寬度由「候選詞」決定（1 字窄、多字寬），拼音只做註腳、放不下就自動縮字；
 * - 底色是比鍵盤淺一層的表面色，圓角 14dp，不加陰影（IME 面板不適合投影）；
 * - 觸摸反饋用 ripple，而不是改變字色。
 *
 * 點擊攜帶原碼跨度與快照標識回調統一選擇入口，不在 Adapter 選詞或提交。
 */
class CandidatePanelAdapter(
    private val context: Context,
    private val onPick: (Item) -> Unit,
) : RecyclerView.Adapter<CandidatePanelAdapter.VH>() {

    private var items: List<Item> = emptyList()

    override fun getItemCount(): Int = items.size

    var onLoadAll: (() -> Unit)? = null

    data class Item(
        val text: String,
        val comment: String,
        /** librime menu 全局下標（不是頁內下標） */
        val index: Int,
        /** >=0 = 分詞段選字（字典候選，index=-1），值為段序號 */
        val segment: Int = -1,
        /** 選中後要重新餵回引擎的剩餘碼（按段選字用；空表示沒有剩餘）。 */
        val restCode: String = "",
        val contextKey: String = "",
        val inputSpan: InputCodeSpan? = null,
        val engineSpan: com.hazukieq.hkrime.core.twelvekey.EngineCandidateSpan? = null,
        /** 末尾「全部」懶加載按鈕佔位項。 */
        val isLoadAll: Boolean = false,
        val isLoading: Boolean = false,
        /** 是否經 OpenCC 簡繁/聯想濾鏡改寫，面板磁貼以虛線框 + 右上角微圓點區分。 */
        val openccModified: Boolean = false,
    )

    private val density = context.resources.displayMetrics.density

    private val wordSize = sp(19f)
    private val actionSize = sp(15f)
    private val commentSize = sp(10.5f)
    private var ink: Int = 0xFF37474F.toInt()
    private var commentColor: Int = ink
    private var canvas: Int = 0xFFF2F0EC.toInt()

    private var wordColor: Int = ink
    private var subColor: Int = withAlpha(commentColor, 0.72f)
    private var tileColor: Int = surfaceOf(canvas, ink)
    private var tileBorderColor: Int = withAlpha(ink, 0.12f)
    private var openccMarkColor: Int = 0xFFD97706.toInt()
    private var openccBorderColor: Int = withAlpha(openccMarkColor, 0.72f)
    private var rippleColor: Int = withAlpha(ink, 0.12f)
    private var wordTypeface: Typeface = Typeface.DEFAULT

    private val padH = dp(12f)
    private val padV = dp(6f)
    private val radius = dp(10f)
    private val borderWidth = dp(1f).coerceAtLeast(1)
    private val openccDashWidth = dp(4f).toFloat().coerceAtLeast(2f)
    private val openccDashGap = dp(2.5f).toFloat().coerceAtLeast(1.5f)
    private val dotSize = dp(5f).coerceAtLeast(4)
    private val dotMargin = dp(4f)
    private val minTileWidth = dp(54f)

    /** 格子間距（FlexboxLayoutManager 沒有 gap 參數，用 item 的 margin 實現）。 */
    private val gap = dp(7f)

    private var hideComment: Boolean = false

    init {
        refreshThemeTokens()
    }

    private var showComment = true
    private var listHasAnyComment = false

    fun setShowComment(value: Boolean) {
        if (showComment == value) return
        showComment = value
        notifyItemRangeChanged(0, itemCount)
    }

    /** 切換配色/主題/深淺模式時重讀色值並刷新所有已見格子。 */
    fun applyTheme() {
        refreshThemeTokens()
        notifyDataSetChanged()
    }

    private fun refreshThemeTokens() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        ink = cfg?.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
        commentColor = cfg?.getColor("comment_text_color") ?: ink
        canvas = cfg?.getColor("keyboard_back_color") ?: 0xFFF2F0EC.toInt()
        val keyBack = cfg?.getColor("key_back_color")
        val keyBorder = cfg?.getColor("key_border_color")

        wordColor = ink
        subColor = withAlpha(commentColor, 0.72f)
        tileColor = if (keyBack != null && keyBack != canvas) keyBack else surfaceOf(canvas, ink)
        // Material 3 表面層次靠底色對比承載，描邊僅保留極輕微輪廓（不搶主視覺）。
        val isDark = androidx.core.graphics.ColorUtils.calculateLuminance(canvas) < 0.4
        tileBorderColor = keyBorder?.let { withAlpha(it, if (isDark) 0.28f else 0.42f) }
            ?: withAlpha(ink, 0.08f)
        openccMarkColor = if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
        openccBorderColor = withAlpha(openccMarkColor, if (isDark) 0.78f else 0.72f)
        rippleColor = withAlpha(ink, 0.12f)
        wordTypeface = cfg?.getFont("candidate_font") ?: Typeface.DEFAULT
        hideComment = runCatching { Rime.getOption("_hide_comment") }.getOrDefault(false)
    }

    class VH(
        val box: FrameLayout,
        val word: TextView,
        val comment: TextView,
        val dot: View,
        val dotShape: GradientDrawable,
        val shape: GradientDrawable,
        val ripple: RippleDrawable,
    ) : RecyclerView.ViewHolder(box)

    fun submit(list: List<Item>) {
        listHasAnyComment = list.any { it.comment.isNotEmpty() }
        items = list
        // 同步刷新：避免 ListAdapter 異步 DiffUtil 在首幀產生短暫空白閃爍
        notifyDataSetChanged()
    }

    fun clear() {
        listHasAnyComment = false
        if (items.isEmpty()) return
        items = emptyList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val shape = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(tileColor)
            setStroke(borderWidth, tileBorderColor)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            shape,
            null,
        )
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
        }
        val word = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, wordSize)
            setTextColor(wordColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            typeface = wordTypeface
        }
        val comment = TextView(context).apply {
            setTextColor(subColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, commentSize)
            setPadding(0, dp(2f), 0, 0)
        }
        inner.addView(word)
        inner.addView(comment)
        val dotShape = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(openccMarkColor)
        }
        val dot = View(context).apply {
            background = dotShape
            visibility = View.GONE
        }
        val box = FrameLayout(context).apply {
            background = ripple
            addView(
                inner,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                dot,
                FrameLayout.LayoutParams(dotSize, dotSize, Gravity.TOP or Gravity.END).apply {
                    topMargin = dotMargin
                    marginEnd = dotMargin
                },
            )
        }
        return VH(box, word, comment, dot, dotShape, shape, ripple)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        // 複用的 ViewHolder 也必須同步最新配色與狀態，避免切換主題色或按鈕複用殘留舊態。
        if (item.isLoadAll) {
            holder.dot.visibility = View.GONE
            val actionBg = androidx.core.graphics.ColorUtils.blendARGB(tileColor, ink, 0.08f)
            val actionBorder = withAlpha(ink, 0.24f)
            holder.shape.setColor(actionBg)
            holder.shape.setStroke(borderWidth, actionBorder)
            holder.ripple.setColor(ColorStateList.valueOf(rippleColor))
            holder.word.setTextSize(TypedValue.COMPLEX_UNIT_PX, actionSize)
            holder.word.setTextColor(if (item.isLoading) subColor else wordColor)
            holder.word.setTypeface(wordTypeface, Typeface.BOLD)
            holder.word.text = item.text
            holder.box.isEnabled = !item.isLoading
            val actionWidth = maxOf(holder.word.paint.measureText(item.text).toInt() + padH * 2 + dp(8f), minTileWidth + dp(12f))
            val lp = holder.box.layoutParams
            if (lp is FlexboxLayoutManager.LayoutParams) {
                lp.width = actionWidth
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.marginEnd = gap
                lp.bottomMargin = gap
            } else {
                holder.box.layoutParams = FlexboxLayoutManager.LayoutParams(
                    actionWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = gap
                    bottomMargin = gap
                }
            }
            if (hideComment || !showComment || !listHasAnyComment) {
                holder.comment.visibility = View.GONE
            } else {
                holder.comment.visibility = View.INVISIBLE
                holder.comment.text = " "
            }
            holder.box.setOnClickListener {
                if (!item.isLoading) onLoadAll?.invoke()
            }
            return
        }
        holder.shape.setColor(tileColor)
        if (item.openccModified) {
            holder.shape.setStroke(borderWidth, openccBorderColor, openccDashWidth, openccDashGap)
            holder.dotShape.setColor(openccMarkColor)
            holder.dot.visibility = View.VISIBLE
        } else {
            holder.shape.setStroke(borderWidth, tileBorderColor)
            holder.dot.visibility = View.GONE
        }
        holder.ripple.setColor(ColorStateList.valueOf(rippleColor))
        holder.word.setTextSize(TypedValue.COMPLEX_UNIT_PX, wordSize)
        holder.word.setTextColor(wordColor)
        holder.word.setTypeface(wordTypeface, Typeface.NORMAL)
        holder.box.isEnabled = true
        holder.comment.setTextColor(subColor)
        holder.word.text = item.text
        val wordWidth = holder.word.paint.measureText(item.text)
        val commentWidth = if (showComment && !hideComment && item.comment.isNotEmpty()) {
            holder.comment.paint.measureText(item.comment)
        } else {
            0f
        }
        val tileWidth = maxOf(
            maxOf(wordWidth, commentWidth).toInt() + padH * 2,
            minTileWidth,
        )
        val lp = holder.box.layoutParams
        if (lp is FlexboxLayoutManager.LayoutParams) {
            lp.width = tileWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.marginEnd = gap
            lp.bottomMargin = gap
        } else {
            holder.box.layoutParams = FlexboxLayoutManager.LayoutParams(
                tileWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = gap
                bottomMargin = gap
            }
        }
        if (hideComment || !showComment || !listHasAnyComment) {
            holder.comment.visibility = View.GONE
        } else if (item.comment.isEmpty()) {
            // 同頁其它候選帶注腳時，無注腳項保留同等行高，避免同排卡片高低不齊
            holder.comment.visibility = View.INVISIBLE
            holder.comment.text = " "
        } else {
            holder.comment.visibility = View.VISIBLE
            holder.comment.text = item.comment
        }
        holder.box.setOnClickListener { onPick(item) }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    /** 在鍵盤底色上抬一層的表面色：亮色主題往白走，暗色主題往白走一點點。 */
    private fun surfaceOf(canvas: Int, ink: Int): Int {
        val lum = (0.2126f * Color.red(canvas) + 0.7152f * Color.green(canvas) + 0.0722f * Color.blue(canvas)) / 255f
        val target = if (lum > 0.55f) 0xFFFFFFFF.toInt() else Color.WHITE
        val ratio = if (lum > 0.55f) 0.55f else 0.10f
        return Color.argb(
            255,
            blend(Color.red(canvas), Color.red(target), ratio),
            blend(Color.green(canvas), Color.green(target), ratio),
            blend(Color.blue(canvas), Color.blue(target), ratio),
        )
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int =
        (a + (b - a) * ratio).toInt().coerceIn(0, 255)
}
