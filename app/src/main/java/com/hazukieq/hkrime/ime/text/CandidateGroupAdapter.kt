package com.hazukieq.hkrime.ime.text

import android.content.Context
import android.content.res.ColorStateList
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
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.data.Config

/**
 * 展開面板左側的音節索引（Material 3 Navigation Rail 風格）。
 *
 * 設計原則：
 * - 所有未選中項（音節與單字母同等地位）樣式完全一致：無框、無底色、同字色；
 * - 僅當前選中項使用單一無描邊 tonal active indicator 膠囊 + 主題強調色粗體。
 */
class CandidateGroupAdapter(
    private val context: Context,
    private val onPick: (GroupItem) -> Unit,
) : RecyclerView.Adapter<CandidateGroupAdapter.VH>() {

    private var items: List<GroupItem> = emptyList()

    override fun getItemCount(): Int = items.size

    data class GroupItem(
        val index: Int,
        val reading: String,
        val selected: Boolean,
        val count: Int = 0,
        /** 兼容非 12 鍵的舊分段左列：已轉換前綴置灰 */
        val converted: Boolean = false,
        val incomplete: Boolean = false,
        val contextKey: String = "",
    )

    private val density = context.resources.displayMetrics.density

    private val textSize = sp(14.5f)
    private var ink: Int = 0xFF37474F.toInt()
    private var accent: Int = 0xFF1A73E8.toInt()
    private var canvas: Int = 0xFFF2F0EC.toInt()

    private var selectedText: Int = accent
    private var normalText: Int = withAlpha(ink, 0.86f)
    private var selectedBg: Int = withAlpha(accent, 0.14f)
    private var rippleColor: Int = withAlpha(ink, 0.10f)
    private val typeface: Typeface = Typeface.DEFAULT

    private val pillMarginH = dp(8f)
    private val pillMarginV = dp(2f)
    private val pillHeight = dp(36f)
    private val pillRadius = dp(18f).toFloat()

    private var columnWidth = dp(78f)

    init {
        refreshThemeTokens()
    }

    /** 切換配色/主題/深淺模式時重讀色值並刷新左列全部行。 */
    fun applyTheme() {
        refreshThemeTokens()
        notifyDataSetChanged()
    }

    private fun refreshThemeTokens() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        ink = cfg?.getColor("candidate_text_color") ?: 0xFF37474F.toInt()
        canvas = cfg?.getColor("keyboard_back_color") ?: 0xFFF2F0EC.toInt()
        val hilitedCand = cfg?.getColor("hilited_candidate_text_color")
        val hilitedKey = cfg?.getColor("hilited_key_back_color")
        accent = when {
            hilitedCand != null && hilitedCand != ink -> hilitedCand
            hilitedKey != null && hilitedKey != canvas && hilitedKey != ink -> hilitedKey
            else -> hilitedCand ?: ink
        }
        val isDark = ColorUtils.calculateLuminance(canvas) < 0.4
        selectedText = accent
        normalText = withAlpha(ink, 0.86f)
        selectedBg = withAlpha(accent, if (isDark) 0.22f else 0.14f)
        rippleColor = withAlpha(ink, if (isDark) 0.14f else 0.08f)
    }

    class VH(
        val pill: LinearLayout,
        val text: TextView,
        val bgShape: GradientDrawable,
        val ripple: RippleDrawable,
    ) : RecyclerView.ViewHolder(pill)

    fun submit(list: List<GroupItem>) {
        columnWidth = measureColumnWidth(list)
        items = list
        notifyDataSetChanged()
    }

    fun clear() {
        if (items.isEmpty()) return
        items = emptyList()
        notifyDataSetChanged()
    }

    private fun measureColumnWidth(list: List<GroupItem>): Int {
        if (list.isEmpty()) return dp(78f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = this@CandidateGroupAdapter.textSize
            typeface = this@CandidateGroupAdapter.typeface
        }
        var widest = 0f
        list.forEach { widest = maxOf(widest, paint.measureText(it.reading)) }
        val wanted = widest + pillMarginH * 2 + dp(16f)
        return wanted.toInt().coerceIn(dp(74f), dp(148f))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val bgShape = GradientDrawable().apply {
            cornerRadius = pillRadius
            setColor(Color.TRANSPARENT)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            bgShape,
            GradientDrawable().apply {
                cornerRadius = pillRadius
                setColor(Color.WHITE)
            },
        )
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                pillHeight,
            ).apply {
                marginStart = pillMarginH
                marginEnd = pillMarginH
                topMargin = pillMarginV
                bottomMargin = pillMarginV
            }
            background = ripple
        }
        val tv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            setPadding(dp(4f), 0, dp(4f), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setSingleLine(true)
            marqueeRepeatLimit = 0
            isFocusable = false
            isFocusableInTouchMode = false
            includeFontPadding = false
            setTextColor(normalText)
            typeface = this@CandidateGroupAdapter.typeface
        }
        pill.addView(tv)
        return VH(pill, tv, bgShape, ripple)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val multiSyllable = item.reading.count { it == ' ' } >= 1
        holder.text.isSelected = false
        holder.text.ellipsize = TextUtils.TruncateAt.END
        holder.text.marqueeRepeatLimit = 0
        holder.text.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            if (multiSyllable) textSize * 0.85f else textSize,
        )
        holder.ripple.setColor(ColorStateList.valueOf(rippleColor))
        // 僅選中項有 Material 3 tonal active indicator，其餘項一律乾淨無框無底色。
        holder.bgShape.setColor(if (item.selected) selectedBg else Color.TRANSPARENT)

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
        holder.text.setTypeface(
            typeface,
            if (item.selected) Typeface.BOLD else Typeface.NORMAL,
        )
        holder.pill.setOnClickListener { onPick(item) }
        holder.pill.isClickable = !item.converted
        holder.pill.contentDescription =
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
}
