package com.hazukieq.hkrime.ime.hardware

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.hazukieq.hkrime.data.Config

/**
 * 仿 macOS Sonoma / Sequoia 的光標旁輸入法狀態膠囊懸浮層（僅用於物理鍵盤接手模式）：
 * - 透過 [HardwareOverlayHost] 渲染於透明畫布 / Overlay，不用觸屏 `PopupWindow`；
 * - 點擊進入輸入框時短暫浮出（顯示當前「中」或「英」狀態）；
 * - 按 Shift 切換中英文模式時即時浮出提醒；
 * - 淡出後自動隱藏，開始組字時立即讓位給候選懸浮層。
 */
class HardwareModeIndicatorOverlay(
    private val context: Context,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val overlay = HardwareOverlayHost(context, touchable = false, dismissOnOutsideTouch = false)
    private var shadowWrapper: FrameLayout? = null
    private var pillView: TextView? = null
    private var onAutoHidden: (() -> Unit)? = null

    private val hideRunnable = Runnable {
        val view = pillView
        if (view != null && isShowing) {
            view.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction {
                    dismissImmediate()
                    onAutoHidden?.invoke()
                }
                .start()
        } else {
            dismissImmediate()
            onAutoHidden?.invoke()
        }
    }

    val isShowing: Boolean get() = overlay.isShowing

    fun show(
        anchorView: View?,
        cursorRect: RectF,
        isAsciiMode: Boolean,
        durationMs: Long = DEFAULT_DURATION_MS,
        onHidden: (() -> Unit)? = null,
    ) {
        handler.removeCallbacks(hideRunnable)
        onAutoHidden = onHidden
        ensurePopup()

        val cfg = runCatching { Config.get(context) }.getOrNull()
        if (cfg != null && cfg.getCurrentColor_("back_color") == null) {
            runCatching { cfg.initCurrentColors() }
        }
        val rawBg = cfg?.getColor("back_color")
            ?: cfg?.getColor("keyboard_back_color")
            ?: cfg?.getColor("key_back_color")
            ?: Color.WHITE
        val cardBg = ColorUtils.setAlphaComponent(rawBg, 255)
        val inkColor = cfg?.getColor("candidate_text_color")
            ?: cfg?.getColor("key_text_color")
            ?: if (ColorUtils.calculateLuminance(cardBg) < 0.5) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val candHiliteBg = cfg?.getColor("hilited_candidate_back_color")
        val keyHiliteBg = cfg?.getColor("hilited_key_back_color")
        val accentBg = when {
            candHiliteBg != null && candHiliteBg != cardBg -> candHiliteBg
            keyHiliteBg != null && keyHiliteBg != cardBg -> keyHiliteBg
            else -> 0xFF007AFF.toInt()
        }
        val rawAccentText = cfg?.getColor("hilited_candidate_text_color")
            ?: cfg?.getColor("hilited_key_text_color")
        val accentText = if (rawAccentText != null &&
            ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(rawAccentText, 255), ColorUtils.setAlphaComponent(accentBg, 255)) >= 2.2
        ) {
            rawAccentText
        } else if (ColorUtils.calculateLuminance(accentBg) < 0.5) {
            Color.WHITE
        } else {
            0xFF1C1C1E.toInt()
        }
        val rawBorder = cfg?.getColor("border_color") ?: cfg?.getColor("key_border_color")
        val borderColor = if (rawBorder != null && rawBorder != cardBg) {
            rawBorder
        } else {
            ColorUtils.setAlphaComponent(inkColor, 48)
        }

        val pill = pillView ?: return
        pill.animate().cancel()
        pill.alpha = 0f
        pill.text = if (isAsciiMode) "英" else "中"
        if (isAsciiMode) {
            pill.setTextColor(inkColor)
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(cardBg)
                setStroke(dp(1).coerceAtLeast(1), borderColor)
            }
        } else {
            pill.setTextColor(accentText)
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(accentBg)
            }
        }

        positionPopup(anchorView, cursorRect)
        pill.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        handler.postDelayed(hideRunnable, durationMs)
    }

    fun updateCursorPosition(anchorView: View?, cursorRect: RectF) {
        if (!isShowing) return
        positionPopup(anchorView, cursorRect)
    }

    fun dismiss() {
        handler.removeCallbacks(hideRunnable)
        pillView?.animate()?.cancel()
        dismissImmediate()
    }

    private fun dismissImmediate() {
        overlay.dismiss()
    }

    private fun ensurePopup() {
        if (pillView != null && shadowWrapper != null) return
        val pad = dp(SHADOW_PAD_DP)
        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
        }
        val tv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(32)
            minimumHeight = dp(26)
            elevation = dp(5).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        wrapper.addView(tv)
        shadowWrapper = wrapper
        pillView = tv
    }

    private fun positionPopup(anchorView: View?, cursorRect: RectF) {
        val wrapper = shadowWrapper ?: return

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = dp(8)
        val pad = dp(SHADOW_PAD_DP)

        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST),
        )
        val w = wrapper.measuredWidth
        val h = wrapper.measuredHeight
        val pillW = (w - pad * 2).coerceAtLeast(0)
        val pillH = (h - pad * 2).coerceAtLeast(0)

        val hasValidCursor = cursorRect.left.isFinite() && cursorRect.top.isFinite() && cursorRect.bottom.isFinite() &&
            cursorRect.bottom >= cursorRect.top &&
            !(cursorRect.left == 0f && cursorRect.top == 0f && cursorRect.bottom == 0f) &&
            cursorRect.left >= -20f && cursorRect.left <= screenW + 20f &&
            cursorRect.bottom > 0f && cursorRect.top < screenH
        val cardX: Int
        val cardY: Int
        if (hasValidCursor) {
            cardX = cursorRect.left.toInt().coerceIn(margin, (screenW - pillW - margin).coerceAtLeast(margin))
            val belowY = cursorRect.bottom.toInt() + dp(6)
            cardY = if (belowY + pillH <= screenH - dp(16)) {
                belowY
            } else {
                (cursorRect.top.toInt() - pillH - dp(6)).coerceAtLeast(dp(16))
            }
        } else {
            cardX = dp(32)
            cardY = (screenH - pillH - dp(84)).coerceAtLeast(dp(24))
        }
        val x = cardX - pad
        val y = cardY - pad

        overlay.showOrUpdate(wrapper, anchorView, x, y, w, h)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        const val DEFAULT_DURATION_MS = 700L
        private const val FADE_IN_MS = 60L
        private const val FADE_OUT_MS = 100L
        private const val SHADOW_PAD_DP = 8
    }
}
