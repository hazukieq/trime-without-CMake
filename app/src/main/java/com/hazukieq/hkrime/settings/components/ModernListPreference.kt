package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import com.hazukieq.hkrime.ime.dialog.ModernListDialog

/** ListPreference 改成類 Apple 單選對話框（無陰影、卡片式）。 */
class ModernListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListPreference(context, attrs) {

    override fun onClick() {
        val labels = entries?.map { it.toString() } ?: return
        val values = entryValues?.map { it.toString() } ?: return
        val index = values.indexOf(value).let { if (it >= 0) it else 0 }
        ModernListDialog.singleChoice(context, title?.toString() ?: "", labels, index) { i ->
            if (callChangeListener(values[i])) setValue(values[i])
        }.show()
    }
}
