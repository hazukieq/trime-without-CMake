package com.hazukieq.hkrime.ime.dialog

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 現代（類 Apple）列表對話框：方案開關、配色選擇共用。
 *
 * - 卡片 22dp 圓角、左右留白 32dp；標題 17sp Semibold 居中；
 * - 每行 52dp，行間 1px 髮絲線（左縮進 20dp）；選中行右側藍色勾；
 * - 底部髮絲線 + 兩個文字按鈕（取消 / 確定），按壓有 ripple；
 * - actionMode=true 時點行立即回調並關閉（方案開關）；否則單選後按「確定」（配色）。
 */
class ModernListDialog private constructor(
    context: Context,
    private val title: String,
    private val items: List<String>,
    private val checkedIndex: Int,
    private val actionMode: Boolean,
    private val onPick: (Int) -> Unit,
    private val onConfirm: (Int) -> Unit,
) : Dialog(context) {

    private var currentIndex = checkedIndex
    private val checks = ArrayList<View>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val night: Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    private val surface: Int get() = if (night) 0xFF1C1C1E.toInt() else Color.WHITE
    private val ink: Int get() = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    private val hairline: Int get() = if (night) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()
    private val accent: Int get() = 0xFF007AFF.toInt()

    init {
        val root = FrameLayout(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(surface)
            }
        }

        card.addView(buildTitle())
        items.forEachIndexed { index, _ -> card.addView(buildRow(index)) }
        card.addView(buildButtons())

        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(32); rightMargin = dp(32)
                topMargin = dp(24); bottomMargin = dp(24)
            },
        )
        setContentView(root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0.4f)
        window?.setGravity(Gravity.CENTER)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setCanceledOnTouchOutside(true)
    }

    private fun buildTitle(): View = TextView(context).apply {
        text = title
        textSize = 17f
        setTextColor(ink)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(0, dp(22), 0, dp(18))
    }

    private fun buildRow(index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            background = ripple(0.06f)
            isClickable = true
        }
        val label = TextView(context).apply {
            text = items[index]
            textSize = 17f
            setTextColor(ink)
            setPadding(0, dp(15), 0, dp(15))
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = CheckMark(context, accent)
        check.visibility = if (index == checkedIndex) View.VISIBLE else View.INVISIBLE
        row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)))
        row.setOnClickListener {
            if (actionMode) {
                onPick(index)
                dismiss()
            } else {
                currentIndex = index
                refreshChecks()
            }
        }
        checks.add(check)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            if (index != items.lastIndex) {
                addView(
                    View(context).apply { setBackgroundColor(hairline) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        .apply { leftMargin = dp(20) },
                )
            }
        }
    }

    private fun buildButtons(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            View(context).apply { setBackgroundColor(hairline) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (actionMode) {
                    // 點行即生效，底部只留「取消」，避免出現一個沒意義的「確定」。
                    addView(
                        button(android.R.string.cancel, false) { dismiss() },
                        LinearLayout.LayoutParams(0, dp(48), 1f),
                    )
                } else {
                    addView(
                        button(android.R.string.cancel, false) { dismiss() },
                        LinearLayout.LayoutParams(0, dp(48), 1f),
                    )
                    addView(
                        button(android.R.string.ok, true) {
                            onConfirm(currentIndex)
                            dismiss()
                        },
                        LinearLayout.LayoutParams(0, dp(48), 1f),
                    )
                }
            },
        )
    }

    private fun button(resId: Int, bold: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = context.getString(resId)
            textSize = 17f
            setTextColor(accent)
            gravity = Gravity.CENTER
            typeface = Typeface.create(
                Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
            background = ripple(0.06f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun refreshChecks() {
        checks.forEachIndexed { index, view ->
            view.visibility = if (index == currentIndex) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun ripple(alpha: Float) = RippleDrawable(
        ColorStateList.valueOf(
            Color.argb((alpha * 255).toInt(), 0, 0, 0),
        ),
        null,
        null,
    )

    private fun dp(v: Number): Int =
        (v.toFloat() * context.resources.displayMetrics.density + 0.5f).toInt()

    private class CheckMark(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.76f)
                lineTo(w * 0.84f, h * 0.26f)
            }
            canvas.drawPath(path, paint)
        }
    }

    /** Java 端可用的 lambda 回調（避免 Function1 在 Java 不好寫）。 */
    fun interface OnIndex {
        fun onIndex(index: Int)
    }

    companion object {
        /** 單選：等「確定」回調。 */
        @JvmStatic
        fun singleChoice(
            context: Context,
            title: String,
            items: List<String>,
            checkedIndex: Int,
            onConfirm: OnIndex,
        ): ModernListDialog = ModernListDialog(
            context, title, items, checkedIndex, actionMode = false,
            onPick = {}, onConfirm = { onConfirm.onIndex(it) },
        )

        /** 點行立即回調並關閉（方案開關）。 */
        @JvmStatic
        fun actionList(
            context: Context,
            title: String,
            items: List<String>,
            onPick: OnIndex,
        ): ModernListDialog = ModernListDialog(
            context, title, items, -1, actionMode = true,
            onPick = { onPick.onIndex(it) }, onConfirm = {},
        )
    }
}
