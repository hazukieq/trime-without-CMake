package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

    private val cfg = Config.get(context)
    private val textSize = cfg.getPixel("candidate_text_size", 22).toFloat()
    private val textColor = cfg.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
    private val accent = cfg.getColor("hilited_candidate_text_color") ?: textColor
    private val selectedBg = 0xFFFFFFFF.toInt()
    private val widthPx = (92 * context.resources.displayMetrics.density).toInt()

    class VH(val root: LinearLayout, val text: TextView, val bar: TextView) :
        RecyclerView.ViewHolder(root)

    fun submit(list: List<GroupItem>) {
        submitList(list)
    }

    fun clear() {
        submitList(emptyList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bar = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (3 * context.resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0x00000000)
        }
        val tv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            setPadding(
                0,
                (10 * context.resources.displayMetrics.density).toInt(),
                0,
                (10 * context.resources.displayMetrics.density).toInt(),
            )
        }
        root.addView(bar)
        root.addView(tv)
        return VH(root, tv, bar)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
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
        holder.text.setTypeface(
            holder.text.typeface,
            if (item.selected) Typeface.BOLD else Typeface.NORMAL,
        )
        holder.root.setOnClickListener { onPick(item) }
        // 只有「已轉換前綴」不可點；其餘組都只切右格視圖
        holder.root.alpha = if (item.converted) 0.6f else 1f
        holder.root.isClickable = !item.converted
        if (item.count > 0) {
            holder.root.contentDescription = "${item.reading} ${item.count}"
        }
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
