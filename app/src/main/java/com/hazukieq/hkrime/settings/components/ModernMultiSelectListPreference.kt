package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.util.AttributeSet
import androidx.preference.MultiSelectListPreference
import com.hazukieq.hkrime.ime.dialog.ModernListDialog

/** MultiSelectListPreference 改成類 Apple 多選對話框。 */
class ModernMultiSelectListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MultiSelectListPreference(context, attrs) {

    override fun onClick() {
        val labels = entries?.map { it.toString() } ?: return
        val values = entryValues?.map { it.toString() } ?: return
        val checked = BooleanArray(values.size) { values[it] in this.values }
        ModernListDialog.multiChoice(context, title?.toString() ?: "", labels, checked) { flags ->
            val next = values.filterIndexed { i, _ -> flags[i] }.toSet()
            if (callChangeListener(next)) this.values = next
        }.show()
    }
}
