package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.data.Config

/**
 * 展開面板左側的音節組列表：只讀快照，整體替換。
 *
 * 點擊只切換右側網格的視圖，**不調引擎、不動 caret**（設計 §5.2）。
 *
 * 排版：每組一行（[TextView.maxLines]=1 + END 省略），列寬按當前組列表裡最長的 reading
 * 動態測量（上限 [MAX_COLUMN_DP]），避免多音節 reading 被硬換行成兩三行。
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
    private val textSize = cfg.getPixel("candidate_text_size", 22).toFloat()
    private val textColor = cfg.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
    private val accent = cfg.getColor("hilited_candidate_text_color") ?: textColor
    private val selectedBg = accent and 0x22FFFFFF.toInt()
    private val typeface: Typeface = Typeface.DEFAULT

    private val rowPaddingH = (12 * density).toInt()
    private val rowPaddingV = (9 * density).toInt()
    private val barWidth = (3 * density).toInt()
    private val maxColumnPx = (200 * density).toInt()

    /** 由 [submit] 依最長 reading 算出；每項目共用，保證左列不抖動。 */
    private var columnWidth = (96 * density).toInt()

    class VH(val root: LinearLayout, val text: TextView, val bar: TextView) :
        RecyclerView.ViewHolder(root)

    fun submit(list: List<GroupItem>) {
        columnWidth = measureColumnWidth(list)
        submitList(list)
    }

    fun clear() {
        submitList(emptyList())
    }

    private fun measureColumnWidth(list: List<GroupItem>): Int {
        if (list.isEmpty()) return (96 * density).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = this@CandidateGroupAdapter.textSize
            typeface = this@CandidateGroupAdapter.typeface
        }
        var widest = 0f
        list.forEach { widest = maxOf(widest, paint.measureText(it.reading)) }
        val wanted = widest + rowPaddingH * 2 + barWidth
        return wanted.toInt().coerceIn((88 * density).toInt(), maxColumnPx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(columnWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bar = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(barWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0x00000000)
        }
        val tv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setSingleLine(true)
            setPadding(rowPaddingH, rowPaddingV, rowPaddingH, rowPaddingV)
        }
        root.addView(bar)
        root.addView(tv)
        return VH(root, tv, bar)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.root.layoutParams = RecyclerView.LayoutParams(
            columnWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        holder.root.setBackgroundColor(if (item.selected) selectedBg else 0x00000000)
        holder.bar.setBackgroundColor(if (item.selected) accent else 0x00000000)
        holder.text.text = item.reading
        holder.text.setTextColor(
            when {
                item.converted -> textColor and 0x66FFFFFF.toInt()
                item.incomplete -> textColor and 0x99FFFFFF.toInt()
                else -> textColor
            },
        )
        // 每次都從默認字重重新設定，避免上一個 selected 的 BOLD 殘留在複用的 TextView 上
        holder.text.setTypeface(typeface, if (item.selected) Typeface.BOLD else Typeface.NORMAL)
        holder.root.setOnClickListener { onPick(item) }
        holder.root.alpha = if (item.converted) 0.6f else 1f
        holder.root.isClickable = !item.converted
        holder.root.contentDescription = if (item.count > 0) "${item.reading} ${item.count}" else item.reading
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GroupItem>() {
            override fun areItemsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean =
                oldItem.index == newItem.index

            override fun areContentsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean =
                oldItem == newItem
        }
    }
}
