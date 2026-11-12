package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.data.Config

/**
 * 展開面板左側的音節索引（rail）。
 *
 * 設計：左列是「索引」不是「按鈕」——同樣的字號、只靠顏色與一條 3dp 的高亮豎線
 * 表示選中，避免整行反白把視線從右側候選上搶走。
 */
class CandidateGroupAdapter(
    private val context: Context,
    private val onPick: (GroupItem) -> Unit,
) : ListAdapter<CandidateGroupAdapter.GroupItem, CandidateGroupAdapter.VH>(DIFF) {

    data class GroupItem(
        val index: Int,
        val reading: String,
        val selected: Boolean,
        val count: Int = 0,
        /** 兼容非 12 鍵的舊分段左列：已轉換前綴置灰 */
        val converted: Boolean = false,
        val incomplete: Boolean = false,
    )

    private val density = context.resources.displayMetrics.density
    private val cfg = Config.get(context)

    private val textSize = sp(14f)
    private val ink = cfg.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
    private val accent = cfg.getColor("hilited_candidate_text_color") ?: ink
    private val canvas = cfg.getColor("keyboard_back_color") ?: 0xFFF2F0EC.toInt()

    private val selectedText = accent
    private val normalText = withAlpha(ink, 0.72f)
    private val selectedBg = withAlpha(accent, 0.10f)
    private val barColor = accent
    private val typeface: Typeface = Typeface.DEFAULT

    private val rowPadStart = dp(12f)
    private val rowPadEnd = dp(6f)
    private val rowHeight = dp(40f)
    private val barWidth = dp(3f)
    private val barRadius = dp(1.5f)

    private var columnWidth = dp(88f)

    class VH(val root: LinearLayout, val text: TextView, val bar: android.view.View) :
        RecyclerView.ViewHolder(root)

    fun submit(list: List<GroupItem>) {
        columnWidth = measureColumnWidth(list)
        submitList(list)
    }

    fun clear() = submitList(emptyList())

    private fun measureColumnWidth(list: List<GroupItem>): Int {
        if (list.isEmpty()) return dp(100f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = this@CandidateGroupAdapter.textSize
            typeface = this@CandidateGroupAdapter.typeface
        }
        var widest = 0f
        list.forEach { widest = maxOf(widest, paint.measureText(it.reading)) }
        val wanted = widest + rowPadStart + rowPadEnd + barWidth
        return wanted.toInt().coerceIn(dp(96f), dp(176f))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeight,
            )
        }
        val bar = android.view.View(context).apply {
            // 選中指示線佔滿整行高度。
            layoutParams = LinearLayout.LayoutParams(barWidth, rowHeight).apply {
                marginStart = dp(6f)
            }
            background = GradientDrawable().apply {
                cornerRadius = barRadius.toFloat()
                setColor(Color.TRANSPARENT)
            }
        }
        val tv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            setPadding(rowPadStart, 0, rowPadEnd, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setSingleLine(true)
            // 不跑馬燈：聯想詞行的標題是完整讀音（如 `song gong`），跑馬燈會讓它看起來像亂碼。
            marqueeRepeatLimit = 0
            isFocusable = false
            isFocusableInTouchMode = false
            includeFontPadding = false
            setTextColor(normalText)
            typeface = this@CandidateGroupAdapter.typeface
        }
        root.addView(bar)
        root.addView(tv)
        return VH(root, tv, bar)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        // 多音節標題使用小字號與省略號，不啟用跑馬燈。
        val multiSyllable = item.reading.count { it == ' ' } >= 1
        holder.text.isSelected = false
        holder.text.ellipsize = TextUtils.TruncateAt.END
        holder.text.marqueeRepeatLimit = 0
        holder.text.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            if (multiSyllable) textSize * 0.86f else textSize,
        )
        holder.root.background = if (item.selected) {
            GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(selectedBg)
            }
        } else {
            null
        }
        (holder.bar.background as GradientDrawable).setColor(
            if (item.selected) barColor else Color.TRANSPARENT,
        )
        // 推薦組無讀音時顯示“推荐”，其它行顯示具體讀音。
        val label = if (item.reading.isBlank()) "推荐" else item.reading
        holder.text.text = label
        holder.text.setTextColor(
            when {
                item.converted -> withAlpha(ink, 0.38f)
                item.selected -> selectedText
                item.incomplete -> withAlpha(ink, 0.58f)
                else -> normalText
            },
        )
        // 每輪都從默認字重重設，避免 selected 的 MEDIUM 殘留在複用的 TextView 上
        holder.text.setTypeface(
            typeface,
            if (item.selected) Typeface.BOLD else Typeface.NORMAL,
        )
        holder.root.setOnClickListener { onPick(item) }
        holder.root.isClickable = !item.converted
        holder.root.contentDescription =
            if (item.count > 0) "$label ${item.count}" else label
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GroupItem>() {
            override fun areItemsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean =
                oldItem.index == newItem.index

            override fun areContentsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean =
                oldItem == newItem
        }
    }
}
