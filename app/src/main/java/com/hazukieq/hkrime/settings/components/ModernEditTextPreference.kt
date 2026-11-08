package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import com.hazukieq.hkrime.ime.dialog.ModernListDialog

/** EditTextPreference 改成類 Apple 輸入對話框（無陰影、卡片式）。 */
class ModernEditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditTextPreference(context, attrs) {

    override fun onClick() {
        ModernListDialog.input(
            context,
            title?.toString() ?: "",
            text ?: "",
            singleLine = true,
        ) { value ->
            if (callChangeListener(value)) setText(value)
        }.show()
    }
}
