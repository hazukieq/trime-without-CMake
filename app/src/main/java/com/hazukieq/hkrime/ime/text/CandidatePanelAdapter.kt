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
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.Config

/**
 * 候選展開面板右側的網格：只讀快照，整表 submitList 替換。
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
    )

    private val cfg = Config.get(context)
    private val textSize = cfg.getPixel("candidate_text_size", 22).toFloat()
    private val commentSize = cfg.getPixel("comment_text_size", 12).toFloat()
    private val pad = cfg.getPixel("candidate_padding", 5)
    private val radius = cfg.getPixel("round_corner", 8).toFloat()
    private val textColor = cfg.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
    private val commentColor = cfg.getColor("comment_text_color") ?: textColor
    private val cellColor = cfg.getColor("key_back_color") ?: 0x00000000
    private val hideComment = Rime.getOption("_hide_comment")

    class VH(val box: LinearLayout, val word: TextView, val comment: TextView) :
        RecyclerView.ViewHolder(box)

    fun submit(list: List<Item>) {
        submitList(list)
    }

    fun clear() {
        submitList(emptyList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(pad, pad, pad, pad) }
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(cellColor)
            }
        }
        val word = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            typeface = cfg.getFont("candidate_font") ?: Typeface.DEFAULT
        }
        val comment = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, commentSize)
            setTextColor(commentColor)
            gravity = Gravity.CENTER
        }
        box.addView(word)
        box.addView(comment)
        return VH(box, word, comment)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.word.text = item.text
        if (hideComment || item.comment.isEmpty()) {
            holder.comment.visibility = View.GONE
        } else {
            holder.comment.visibility = View.VISIBLE
            holder.comment.text = item.comment
        }
        holder.box.setOnClickListener { onPick(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.index == newItem.index

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }
    }
}
