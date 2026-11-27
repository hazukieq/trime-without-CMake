package com.hazukieq.hkrime.ime.hardware

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import timber.log.Timber

/**
 * 物理鍵盤模式專用懸浮窗承載器（對齊 Gboard / Fcitx5-Android 桌面浮窗架構）：
 * - 首選直接掛載在 `InputMethod`（`type=2011`）內部的全屏透明 `FrameLayout` 畫布上：
 *   1. 不需要任何 `SYSTEM_ALERT_WINDOW` 權限；
 *   2. 系統設定（`HIDE_NON_SYSTEM_OVERLAY_WINDOWS`）、權限頁、QQ、微信、銀行等所有國產 ROM 場景 100% 可見；
 *   3. 配合 `onComputeInsets` 僅把浮窗自身矩形設為 `touchableRegion`，其餘區域完全穿透且不頂起宿主介面。
 * - 若畫布尚未附著且已授懸浮窗權限（非系統限制宿主），自動回退 `TYPE_APPLICATION_OVERLAY`（2038）兜底。
 */
class HardwareOverlayHost(
    private val context: Context,
    private val touchable: Boolean = true,
    private val dismissOnOutsideTouch: Boolean = false,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var attachedView: View? = null
    private var attachedToCanvas: Boolean = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private val currentRectInWindow = Rect()
    private val canvasScreenLoc = IntArray(2)
    private val canvasWindowLoc = IntArray(2)

    var onOutsideTouch: (() -> Unit)? = null
    var onBoundsChanged: (() -> Unit)? = null

    val isShowing: Boolean get() = attachedView != null

    /** 返回當前在 `InputMethod` 畫布內的可點擊區域（用於 `onComputeInsets` 的 `touchableRegion`）。 */
    fun copyTouchableRect(out: Rect): Boolean {
        if (!touchable || attachedView == null || !attachedToCanvas || currentRectInWindow.isEmpty) {
            return false
        }
        out.set(currentRectInWindow)
        return true
    }

    fun showOrUpdate(
        contentView: View,
        anchorView: View?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val canvas = anchorView as? FrameLayout
        val canUseCanvas = canvas != null && canvas.isAttachedToWindow

        // 1. 首選：直接渲染在 InputMethod(type=2011) 內部的全屏透明畫布上
        if (canUseCanvas && canvas != null) {
            if (attachedView != null && !attachedToCanvas) {
                dismiss()
            }
            canvas.getLocationOnScreen(canvasScreenLoc)
            canvas.getLocationInWindow(canvasWindowLoc)
            val localX = (x - canvasScreenLoc[0]).coerceAtLeast(0)
            val localY = (y - canvasScreenLoc[1]).coerceAtLeast(0)
            val existingLp = contentView.layoutParams as? FrameLayout.LayoutParams
            val sizeChanged = existingLp == null ||
                existingLp.width != width ||
                existingLp.height != height ||
                existingLp.leftMargin != 0 ||
                existingLp.topMargin != 0
            if (contentView.parent !== canvas) {
                (contentView.parent as? ViewGroup)?.removeView(contentView)
                val lp = FrameLayout.LayoutParams(width, height, Gravity.START or Gravity.TOP)
                canvas.addView(contentView, lp)
            } else if (sizeChanged) {
                val lp = existingLp ?: FrameLayout.LayoutParams(width, height, Gravity.START or Gravity.TOP)
                lp.width = width
                lp.height = height
                lp.gravity = Gravity.START or Gravity.TOP
                lp.leftMargin = 0
                lp.topMargin = 0
                contentView.layoutParams = lp
            }
            // 使用 GPU 加速的 translationX/Y 定位，移動時零 requestLayout 開銷
            val targetTx = localX.toFloat()
            val targetTy = localY.toFloat()
            if (contentView.translationX != targetTx) contentView.translationX = targetTx
            if (contentView.translationY != targetTy) contentView.translationY = targetTy
            attachedView = contentView
            attachedToCanvas = true
            val winX = canvasWindowLoc[0] + localX
            val winY = canvasWindowLoc[1] + localY
            val newRight = winX + width
            val newBottom = winY + height
            val rectChanged = currentRectInWindow.left != winX ||
                currentRectInWindow.top != winY ||
                currentRectInWindow.right != newRight ||
                currentRectInWindow.bottom != newBottom
            if (rectChanged) {
                currentRectInWindow.set(winX, winY, newRight, newBottom)
                onBoundsChanged?.invoke()
            }
            return true
        }

        // 2. 次選兜底：非系統限制宿主且已授權懸浮窗時，使用 TYPE_APPLICATION_OVERLAY（2038）
        val editorPkg = (context as? InputMethodService)?.currentInputEditorInfo?.packageName
        if (isOverlayRestrictedHost(editorPkg) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !Settings.canDrawOverlays(context)
        ) {
            return false
        }

        val current = attachedView
        val wmLp = layoutParams
        if (current === contentView && !attachedToCanvas && wmLp != null) {
            if (wmLp.x != x || wmLp.y != y || wmLp.width != width || wmLp.height != height) {
                wmLp.x = x
                wmLp.y = y
                wmLp.width = width
                wmLp.height = height
                return runCatching {
                    windowManager.updateViewLayout(contentView, wmLp)
                    true
                }.onFailure {
                    Timber.w(it, "HardwareOverlayHost updateViewLayout failed")
                }.getOrDefault(false)
            }
            return true
        }

        if (current != null) dismiss()
        (contentView.parent as? ViewGroup)?.removeView(contentView)

        if (dismissOnOutsideTouch) {
            contentView.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                    onOutsideTouch?.invoke()
                    true
                } else {
                    false
                }
            }
        }

        val params = createOverlayParams(x, y, width, height)
        return runCatching {
            windowManager.addView(contentView, params)
            attachedView = contentView
            attachedToCanvas = false
            layoutParams = params
            currentRectInWindow.setEmpty()
            true
        }.onFailure {
            Timber.w(it, "HardwareOverlayHost addView failed for type=2038 pkg=%s", editorPkg)
        }.getOrDefault(false)
    }

    fun dismiss() {
        val v = attachedView ?: return
        val wasCanvas = attachedToCanvas
        attachedView = null
        attachedToCanvas = false
        layoutParams = null
        currentRectInWindow.setEmpty()
        if (wasCanvas) {
            v.translationX = 0f
            v.translationY = 0f
            runCatching { (v.parent as? ViewGroup)?.removeView(v) }
            onBoundsChanged?.invoke()
        } else {
            runCatching { windowManager.removeViewImmediate(v) }
                .onFailure { Timber.w(it, "HardwareOverlayHost removeViewImmediate failed") }
        }
    }

    private fun createOverlayParams(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (dismissOnOutsideTouch) {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        return WindowManager.LayoutParams(
            width,
            height,
            x,
            y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    companion object {
        fun isOverlayRestrictedHost(packageName: String?): Boolean {
            if (packageName.isNullOrEmpty()) return false
            val pkg = packageName.lowercase()
            return pkg.contains("settings") ||
                pkg.contains("permission") ||
                pkg.contains("packageinstaller") ||
                pkg == "com.android.systemui" ||
                pkg.startsWith("com.vivo.") ||
                pkg.startsWith("com.bbk.")
        }
    }
}
