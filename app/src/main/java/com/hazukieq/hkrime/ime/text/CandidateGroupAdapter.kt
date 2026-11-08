package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.data.Config

/**
 * 展開面板左側的拼音音節組列表。
 *
 * 只顯示拼音、不顯示候選數量；選中項白底 + 左側強調豎條。
 */
class CandidateGroupAdapter(
    private val context: Context,
    private val onPick: (PinyinGroupAdapterItem) -> Unit,
) : RecyclerView.Adapter<CandidateGroupAdapter.VH>() {

  data class PinyinGroupAdapterItem(
    val index: Int,
    val text: String,
    /** 該組在 preedit 中的起始字節偏移（用於 RimeSetCaretPos） */
    val startByte: Int,
    /** 已經轉換成漢字的前綴（點擊無意義，置灰） */
    val converted: Boolean,
    val incomplete: Boolean,
    val selected: Boolean,
  )

  private val items = ArrayList<PinyinGroupAdapterItem>()

  private val cfg = Config.get(context)
  private val textSize = cfg.getPixel("candidate_text_size", 22).toFloat()
  private val textColor = cfg.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
  private val accent = cfg.getColor("hilited_candidate_text_color") ?: textColor
  private val selectedBg = 0xFFFFFFFF.toInt()
  private val widthPx = (92 * context.resources.displayMetrics.density).toInt()

  class VH(val root: LinearLayout, val text: TextView, val bar: TextView) :
    RecyclerView.ViewHolder(root)

  fun submit(list: List<PinyinGroupAdapterItem>) {
    items.clear()
    items.addAll(list)
    notifyDataSetChanged()
  }

  fun clear() {
    items.clear()
    notifyDataSetChanged()
  }

  override fun getItemCount(): Int = items.size

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    val root = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = RecyclerView.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val bar = TextView(context).apply {
      layoutParams = LinearLayout.LayoutParams((3 * context.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
      setBackgroundColor(0x00000000)
    }
    val tv = TextView(context).apply {
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      gravity = Gravity.CENTER
      setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
      setPadding(0, (10 * context.resources.displayMetrics.density).toInt(), 0, (10 * context.resources.displayMetrics.density).toInt())
    }
    root.addView(bar)
    root.addView(tv)
    return VH(root, tv, bar)
  }

  override fun onBindViewHolder(holder: VH, position: Int) {
    val item = items[position]
    holder.root.setBackgroundColor(if (item.selected) selectedBg else 0x00000000.toInt())
    holder.bar.setBackgroundColor(if (item.selected) accent else 0x00000000.toInt())
    holder.text.text = item.text
    holder.text.setTextColor(
      when {
        item.converted -> textColor and 0x66FFFFFF.toInt()
        item.incomplete -> textColor and 0x99FFFFFF.toInt()
        else -> textColor
      }
    )
    holder.text.setTypeface(holder.text.typeface, if (item.selected) Typeface.BOLD else Typeface.NORMAL)
    holder.root.setOnClickListener { onPick(item) }
  }
}
