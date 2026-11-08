package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.core.Trime
import timber.log.Timber

/** 顯示配色方案列表
 *  Show Color Scheme List
 * **/
class ColorPickerDialog(
    private val context: Context
) {
    val config: Config = Config.get(context)
    private val prefs get() = AppPrefs.defaultInstance()
    private var colorKeys: Array<String>
    private var checkedColorKey: Int = 0
    private lateinit var colorNames: Array<String?>

    init {
        val colorScheme = prefs.looks.selectedColor
        colorKeys = config.colorKeys
        colorKeys.sort()
        colorNames = config.getColorNames(colorKeys) ?: arrayOf()
        checkedColorKey = colorKeys.binarySearch(colorScheme)
    }

    private fun selectColor() {
        Timber.i("select")
        if (checkedColorKey !in colorKeys.indices) return
        val colorKey = colorKeys[checkedColorKey]
        prefs.looks.selectedColor = colorKey
        Timber.i("initKeyboard")
        Trime.getServiceOrNull()?.initKeyboard() // 立刻重初始化键盘生效
        Timber.i("done")
    }

    /** 调用该方法显示对话框（現代類 Apple 單選） **/
    fun show() {
        val checked = if (checkedColorKey in colorNames.indices) checkedColorKey else 0
        val d = com.hazukieq.hkrime.ime.dialog.ModernListDialog.singleChoice(
            context,
            context.getString(R.string.looks__selected_color_title),
            colorNames.map { it ?: "" },
            checked,
        ) { idx ->
            checkedColorKey = idx
            selectColor()
        }
        d.window?.let { window ->
            window.attributes.token = Trime.getServiceOrNull()?.window?.window?.decorView?.windowToken
            window.attributes.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        d.show()
    }
}
