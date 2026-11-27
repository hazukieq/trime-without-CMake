package com.hazukieq.hkrime.util

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 现代圆角加载弹窗（与 ModernListDialog 视觉体系统一：22dp 圆角、日夜自适应底色、#007AFF 主题蓝进度环）。
 */
fun createLoadingDialog(context: Context, textId: Int): Dialog {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val surface = if (night) 0xFF1C1C1E.toInt() else Color.WHITE
    val ink = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val accent = 0xFF007AFF.toInt()

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(20), dp(28), dp(20))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(surface)
        }
    }

    val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(accent)
    }
    card.addView(spinner, LinearLayout.LayoutParams(dp(26), dp(26)))

    val label = TextView(context).apply {
        text = context.getText(textId)
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }
    card.addView(
        label,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(16) },
    )

    return Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(card)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.35f)
            setGravity(Gravity.CENTER)
        }
    }
}
