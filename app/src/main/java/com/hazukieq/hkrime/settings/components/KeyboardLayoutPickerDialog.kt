package com.hazukieq.hkrime.settings.components

import android.content.Context
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.ime.dialog.ModernListDialog

/**
 * 鍵盤布局選擇器：僅列出當前主題中與當前方案 `keyboard/type` 嚴格匹配的布局，
 * 附帶「跟隨主題默認」選項，從 UI 與底層雙重杜絕 12 鍵誤選 26 鍵等錯配。
 */
class KeyboardLayoutPickerDialog(
    private val context: Context,
    private val onChanged: (() -> Unit)? = null,
) {
    fun show() {
        val config = Config.get(context)
        config.reset()
        val schemaId = Rime.getSchemaId().orEmpty()
        val schemaName = Rime.getSchemaName().orEmpty().ifEmpty { schemaId }
        val schemaType = config.currentSchemaKeyboardType
        val layouts = config.availableLayoutsForCurrentSchema
        val currentOverride = AppPrefs.defaultInstance().looks.selectedKeyboardLayout(schemaId)

        val defaultLabel = context.getString(R.string.looks__keyboard_layout_follow_theme)
        val labels = ArrayList<String>(layouts.size + 1)
        val values = ArrayList<String>(layouts.size + 1)
        labels.add(defaultLabel)
        values.add("")
        layouts.forEach { info ->
            labels.add(info.name)
            values.add(info.id)
        }

        val checked = values.indexOf(currentOverride).let { if (it >= 0) it else 0 }
        val title = context.getString(
            R.string.looks__selected_keyboard_layout_dialog_title,
            schemaName,
            schemaType,
        )
        ModernListDialog.singleChoice(context, title, labels, checked) { idx ->
            val picked = values.getOrElse(idx) { "" }
            AppPrefs.defaultInstance().looks.setSelectedKeyboardLayout(schemaId, picked)
            Trime.getServiceOrNull()?.initKeyboard()
            onChanged?.invoke()
        }.show()
    }
}
