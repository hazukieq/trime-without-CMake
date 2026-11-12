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
    private val multi: Boolean = false,
    initialChecked: BooleanArray? = null,
    private val onConfirmMulti: ((BooleanArray) -> Unit)? = null,
    private val message: String? = null,
    private val contentView: View? = null,
    private val singleOk: Boolean = false,
    private val inputField: android.widget.EditText? = null,
    private val onConfirmInput: ((String) -> Unit)? = null,
) : Dialog(context) {

    private val checkedFlags: BooleanArray? = initialChecked?.copyOf()
    private fun isChecked(index: Int): Boolean =
        // 單選標記讀取當前 currentIndex。
        if (checkedFlags != null) checkedFlags[index] else index == currentIndex

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

    override fun show() {
        super.show()
        // show() 之後才算真正掛窗，這裡再定一次尺寸/位置，確保卡片垂直居中且不被裁。
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        window?.setGravity(Gravity.CENTER)
    }

    init {
        val root = FrameLayout(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = true
        }
        val card = LinearLayout(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            orientation = LinearLayout.VERTICAL
            elevation = 0f
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(surface)
            }
        }

        card.addView(buildTitle())
        if (!message.isNullOrBlank()) card.addView(buildMessage())
        inputField?.let { e ->
            card.addView(
                e,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(18) },
            )
        }
        contentView?.let { v ->
            card.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(16) },
            )
        }
        items.forEachIndexed { index, _ ->
            card.addView(
                buildRow(index),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = dp(16); rightMargin = dp(16)
                    topMargin = dp(3); bottomMargin = dp(3)
                },
            )
        }
        card.addView(buildButtons())

        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(32); rightMargin = dp(32)
                topMargin = dp(24); bottomMargin = dp(24)
            },
        )
        setContentView(root)
        // 取消一切窗口陰影（含系統默認的 dialog frame），只保留 0.4 dim。
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.setDimAmount(0.4f)
        window?.setElevation(0f)
        window?.decorView?.let {
            it.isVerticalScrollBarEnabled = false
            it.isHorizontalScrollBarEnabled = false
        }
        window?.setGravity(Gravity.CENTER)
        // 佔滿整屏，卡片在 FrameLayout 內居中：避免 WRAP_CONTENT 窗口被系統
        // 壓短導致卡片底部（最後一行/按鈕）被裁掉。
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
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

    private fun buildMessage(): View = TextView(context).apply {
        text = message
        textSize = 14f
        setTextColor(ink)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(24), 0, dp(24), dp(18))
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
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            text = items[index]
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 17f
            setTextColor(ink)
            setPadding(0, dp(15), 0, dp(15))
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = TextView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            text = "\u2713"
            textSize = 17f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        check.visibility = if (isChecked(index)) View.VISIBLE else View.INVISIBLE
        row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)))
        row.setOnClickListener {
            when {
                actionMode -> {
                    onPick(index)
                    dismiss()
                }
                multi -> {
                    checkedFlags?.let { it[index] = !it[index] }
                    refreshChecks()
                }
                else -> {
                    currentIndex = index
                    refreshChecks()
                }
            }
        }
        checks.add(check)

        // 不用分割線：每行是獨立圓角淺底「選項片」，靠底色與間距區分。
        row.minimumHeight = dp(48)
        row.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (night) 0xFF2C2C2E.toInt() else 0xFFF2F2F7.toInt())
        }
        return row
    }

    private fun buildButtons(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (singleOk) {
                    addView(
                        button(android.R.string.ok, true) { dismiss() },
                        LinearLayout.LayoutParams(0, dp(48), 1f),
                    )
                } else if (actionMode) {
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
                            onConfirmInput?.invoke(inputField?.text?.toString() ?: "")
                            onConfirm(currentIndex)
                            // 多選確定時將當前勾選狀態交回調用方。
                            checkedFlags?.let { onConfirmMulti?.invoke(it.copyOf()) }
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
            view.visibility = if (isChecked(index)) View.VISIBLE else View.INVISIBLE
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

        /** 多選：勾選多個後按「確定」回調。 */
        @JvmStatic
        @JvmOverloads
        fun multiChoice(
            context: Context,
            title: String,
            items: List<String>,
            checked: BooleanArray,
            onConfirm: (BooleanArray) -> Unit,
        ): ModernListDialog = ModernListDialog(
            context, title, items, -1, actionMode = false,
            onPick = {}, onConfirm = {},
            multi = true, initialChecked = checked, onConfirmMulti = onConfirm,
        )

        /** 輸入型對話框：標題 + 單行輸入框 + 取消/確定。 */
        @JvmStatic
        fun input(
            context: Context,
            title: String,
            initial: String,
            singleLine: Boolean,
            onConfirm: (String) -> Unit,
        ): ModernListDialog {
            val density = context.resources.displayMetrics.density
            fun dp(v: Number): Int = (v.toFloat() * density + 0.5f).toInt()
            val night = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val ink = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
            val field = android.widget.EditText(context).apply {
                setText(initial)
                setSelection(text.length)
                textSize = 17f
                setTextColor(ink)
                setHintTextColor(0xFF8E8E93.toInt())
                isSingleLine = singleLine
                if (!singleLine) { minLines = 2; maxLines = 6 }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (night) 0xFF2C2C2E.toInt() else 0xFFF2F2F7.toInt())
                }
            }
            return ModernListDialog(
                context, title, emptyList(), -1, actionMode = false,
                onPick = {}, onConfirm = {}, inputField = field, onConfirmInput = onConfirm,
            )
        }

        /** 內容型對話框：標題 + 自訂 View（如授權網頁）+ 確定。 */
        @JvmStatic
        fun custom(
            context: Context,
            title: String,
            view: View,
        ): ModernListDialog = ModernListDialog(
            context, title, emptyList(), -1, actionMode = false,
            onPick = {}, onConfirm = {}, contentView = view, singleOk = true,
        )

        /** 確認型對話框：標題 + 說明 + 取消/確定。 */
        @JvmStatic
        fun confirm(
            context: Context,
            title: String,
            message: String,
            onConfirm: OnIndex,
        ): ModernListDialog = ModernListDialog(
            context, title, emptyList(), -1, actionMode = false,
            onPick = {}, onConfirm = { onConfirm.onIndex(it) }, message = message,
        )

        /** 點行立即回調並關閉（方案開關）。 */
        @JvmStatic
        fun actionList(
            context: Context,
            title: String,
            items: List<String>,
            onPick: OnIndex,
        ): ModernListDialog = actionList(context, title, items, null, onPick)

        /** 帶說明文字的 action 列表（如「沒有啟用方案」）。 */
        @JvmStatic
        fun actionList(
            context: Context,
            title: String,
            items: List<String>,
            message: String?,
            onPick: OnIndex,
        ): ModernListDialog = ModernListDialog(
            context, title, items, -1, actionMode = true,
            onPick = { onPick.onIndex(it) }, onConfirm = {}, message = message,
        )
    }
}
