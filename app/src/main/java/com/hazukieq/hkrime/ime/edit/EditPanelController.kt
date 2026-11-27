package com.hazukieq.hkrime.ime.edit

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.hazukieq.hkrime.data.Config

/**
 * Native App-side Text Editing panel (`文字编辑`):
 * - Fixed, non-theme-editable layout (circular D-pad + center selection toggle + Home/End circles
 *   on the left; 3×2 rounded action cards on the right).
 * - Unified 2.2dp rounded-stroke vector icons tinted to the active theme's `key_text_color`.
 */
class EditPanelController(
    private val context: Context,
    private val keyboardView: View,
    private val inputArea: ViewGroup,
    private val panelRoot: View,
    private val btnBack: View,
    private val iconBack: ImageView,
    private val titleBack: TextView,
    private val dpadWheel: FrameLayout,
    private val btnUp: ImageView,
    private val btnDown: ImageView,
    private val btnLeft: ImageView,
    private val btnRight: ImageView,
    private val btnSelect: TextView,
    private val btnHome: ImageView,
    private val btnEnd: ImageView,
    private val btnSelectAll: TextView,
    private val btnDelete: TextView,
    private val btnCopy: TextView,
    private val btnCut: TextView,
    private val btnPaste: TextView,
    private val btnUndo: TextView,
) {
    enum class EditAction {
        UP, DOWN, LEFT, RIGHT, HOME, END,
        SELECT_ALL, DELETE, COPY, CUT, PASTE, UNDO,
    }

    var onAction: ((EditAction, Boolean) -> Unit)? = null
    var onSelectModeChanged: ((Boolean) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    val isShown: Boolean get() = panelRoot.visibility == View.VISIBLE

    private var isSelecting: Boolean = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var activeRepeatRunnable: Runnable? = null

    // Theme colors
    private var panelBgColor: Int = 0xFFF1F3F4.toInt()
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF202124.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentBgColor: Int = 0xFF1A73E8.toInt()
    private var accentTextColor: Int = 0xFFFFFFFF.toInt()

    init {
        btnBack.setOnClickListener { hide() }

        btnSelect.setOnClickListener {
            isSelecting = !isSelecting
            updateSelectButtonVisual()
            onSelectModeChanged?.invoke(isSelecting)
        }

        // Repeatable navigation & delete buttons
        bindRepeatable(btnUp) { dispatch(EditAction.UP) }
        bindRepeatable(btnDown) { dispatch(EditAction.DOWN) }
        bindRepeatable(btnLeft) { dispatch(EditAction.LEFT) }
        bindRepeatable(btnRight) { dispatch(EditAction.RIGHT) }
        bindRepeatable(btnDelete) { dispatch(EditAction.DELETE) }

        // Single-shot actions
        btnHome.setOnClickListener { dispatch(EditAction.HOME) }
        btnEnd.setOnClickListener { dispatch(EditAction.END) }
        btnSelectAll.setOnClickListener { dispatch(EditAction.SELECT_ALL) }
        btnCopy.setOnClickListener { dispatch(EditAction.COPY) }
        btnCut.setOnClickListener { dispatch(EditAction.CUT) }
        btnPaste.setOnClickListener { dispatch(EditAction.PASTE) }
        btnUndo.setOnClickListener { dispatch(EditAction.UNDO) }
    }

    private fun dispatch(action: EditAction) {
        onAction?.invoke(action, isSelecting)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindRepeatable(view: View, action: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    stopRepeat()
                    action()
                    val runnable = object : Runnable {
                        override fun run() {
                            action()
                            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
                        }
                    }
                    activeRepeatRunnable = runnable
                    repeatHandler.postDelayed(runnable, REPEAT_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRepeat()
                    true
                }
                else -> false
            }
        }
    }

    private fun stopRepeat() {
        activeRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        activeRepeatRunnable = null
    }

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull() ?: return
        panelBgColor = cfg.getColor("keyboard_back_color") ?: 0xFFF1F3F4.toInt()
        cardBgColor = cfg.getColor("key_back_color") ?: 0xFFFFFFFF.toInt()
        textColor = cfg.getColor("key_text_color") ?: 0xFF202124.toInt()
        borderColor = cfg.getColor("border_color") ?: 0xFFDADCE0.toInt()
        accentBgColor = cfg.getColor("hilited_key_back_color") ?: 0xFF1A73E8.toInt()
        accentTextColor = cfg.getColor("hilited_key_text_color") ?: 0xFFFFFFFF.toInt()

        panelRoot.setBackgroundColor(panelBgColor)
        titleBack.setTextColor(textColor)

        val iconTint = ColorStateList.valueOf(textColor)
        val circleIconTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(accentTextColor, textColor),
        )
        ImageViewCompat.setImageTintList(iconBack, iconTint)

        // Outer D-pad wheel (circle)
        dpadWheel.background = makeOvalDrawable(cardBgColor, borderColor)
        for (arrow in listOf(btnUp, btnDown, btnLeft, btnRight)) {
            ImageViewCompat.setImageTintList(arrow, iconTint)
        }

        // Home & End circular buttons
        for (circleBtn in listOf(btnHome, btnEnd)) {
            ImageViewCompat.setImageTintList(circleBtn, circleIconTint)
            circleBtn.background = makeOvalStateList(cardBgColor, accentBgColor, borderColor)
        }

        // 3×2 rounded action cards
        val radiusPx = dp(8).toFloat()
        val cardTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(accentTextColor, textColor),
        )
        for (card in listOf(btnSelectAll, btnDelete, btnCopy, btnCut, btnPaste, btnUndo)) {
            card.setTextColor(cardTextColors)
            card.background = makeRoundedStateList(cardBgColor, accentBgColor, borderColor, radiusPx)
        }

        updateSelectButtonVisual()
    }

    private fun updateSelectButtonVisual() {
        if (isSelecting) {
            btnSelect.background = makeOvalDrawable(accentBgColor, accentBgColor)
            btnSelect.setTextColor(accentTextColor)
        } else {
            btnSelect.background = makeOvalDrawable(cardBgColor, borderColor)
            btnSelect.setTextColor(textColor)
        }
    }

    fun show() {
        isSelecting = false
        onSelectModeChanged?.invoke(false)
        applyTheme()
        val targetH = keyboardView.height.takeIf { it > 0 } ?: dp(250)
        panelRoot.layoutParams = panelRoot.layoutParams.apply { height = targetH }
        inputArea.minimumHeight = targetH
        keyboardView.visibility = View.INVISIBLE
        panelRoot.visibility = View.VISIBLE
    }

    fun hide() {
        stopRepeat()
        isSelecting = false
        onSelectModeChanged?.invoke(false)
        if (!isShown) return
        panelRoot.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        inputArea.minimumHeight = 0
        onDismissed?.invoke()
    }

    private fun makeOvalDrawable(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun makeOvalStateList(normalFill: Int, pressedFill: Int, stroke: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), makeOvalDrawable(pressedFill, pressedFill))
            addState(intArrayOf(), makeOvalDrawable(normalFill, stroke))
        }

    private fun makeRoundedStateList(
        normalFill: Int,
        pressedFill: Int,
        stroke: Int,
        radiusPx: Float,
    ): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(normalFill)
            setStroke(dp(1), stroke)
        }
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(pressedFill)
            setStroke(dp(1), pressedFill)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    companion object {
        private const val REPEAT_INITIAL_DELAY_MS = 350L
        private const val REPEAT_INTERVAL_MS = 65L

        @JvmStatic
        fun isEditSelect(select: String?): Boolean {
            if (select.isNullOrBlank()) return false
            val s = select.trim().lowercase()
            return s == "edit" || s == "numberb"
        }
    }
}
