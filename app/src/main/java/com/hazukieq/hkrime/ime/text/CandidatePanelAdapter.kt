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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.Config

/**
 * 候選展開面板右側的流式候選（配合 [FlowLayoutManager]）：只讀快照，整表 submitList 替換。
 *
 * 設計：
 * - 格子寬度由「候選詞」決定（1 字窄、多字寬），拼音只做註腳、放不下就自動縮字；
 * - 底色是比鍵盤淺一層的表面色，圓角 14dp，不加陰影（IME 面板不適合投影）；
 * - 觸摸反饋用 ripple，而不是改變字色。
 *
 * [Item.index] 是 librime menu 的全局下標，點擊後直接 Rime.select_candidate(index)。
 */
class CandidatePanelAdapter(
    private val context: Context,
    private val onPick: (Item) -> Unit,
) : ListAdapter<CandidatePanelAdapter.Item, CandidatePanelAdapter.VH>(DIFF) {

    data class Item(
        val text: String,
        val comment: String,
        /** librime menu 全局下標（不是頁內下標） */
        val index: Int,
        /** >=0 = 分詞段選字（字典候選，index=-1），值為段序號 */
        val segment: Int = -1,
        /** 選中後要重新餵回引擎的剩餘碼（按段選字用；空表示沒有剩餘）。 */
        val restCode: String = "",
    )

    private val density = context.resources.displayMetrics.density
    private val cfg = Config.get(context)

    private val wordSize = sp(20f)
    private val commentSize = sp(10.5f)
    private val ink = cfg.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
    private val commentColor = cfg.getColor("comment_text_color") ?: ink
    private val canvas = cfg.getColor("keyboard_back_color") ?: 0xFFF2F0EC.toInt()

    private val wordColor = ink
    private val subColor = withAlpha(commentColor, 0.62f)
    private val tileColor = surfaceOf(canvas, ink)
    private val rippleColor = withAlpha(ink, 0.10f)

    private val padH = dp(10f)
    private val padV = dp(6f)
    private val radius = dp(14f)
    private val minTileWidth = dp(56f)

    private val hideComment = Rime.getOption("_hide_comment")

    /** 只有組數 >= 2（多個讀音）時才標註拼音；單讀音時留白反而清爽。 */
    private var showComment = true

    fun setShowComment(value: Boolean) {
        if (showComment == value) return
        showComment = value
        notifyItemRangeChanged(0, itemCount)
    }

    class VH(val box: LinearLayout, val word: TextView, val comment: TextView) :
        RecyclerView.ViewHolder(box)

    fun submit(list: List<Item>) = submitList(list)

    fun clear() = submitList(emptyList())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val shape = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(tileColor)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                shape,
                null,
            )
        }
        val word = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, wordSize)
            setTextColor(wordColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            typeface = cfg.getFont("candidate_font") ?: Typeface.DEFAULT
        }
        val comment = TextView(context).apply {
            setTextColor(subColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, commentSize)
            setPadding(0, dp(1f), 0, 0)
        }
        box.addView(word)
        box.addView(comment)
        return VH(box, word, comment)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
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
        holder.box.layoutParams = ViewGroup.MarginLayoutParams(
            tileWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (hideComment || !showComment || item.comment.isEmpty()) {
            holder.comment.visibility = View.GONE
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

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.index == newItem.index

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }
    }
}
