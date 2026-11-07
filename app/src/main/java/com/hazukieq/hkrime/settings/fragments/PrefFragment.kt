package com.hazukieq.hkrime.settings.fragments

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.settings.components.SchemaPickerDialog

class PrefFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        setPreferencesFromResource(R.xml.prefs, rootKey)
        val systemCategory = PreferenceCategory(context).apply {
            setTitle(R.string.system)
            isIconSpaceReserved = false
            order = 18
        }
        preferenceScreen.addPreference(systemCategory)
        systemCategory.addPreference(
            Preference(context).apply {
                setTitle(R.string.settings__system_toolkit)
                setIcon(R.drawable.ic_iconpark_toolkit_24)
                fragment = "com.hazukieq.hkrime.settings.fragments.ToolkitFragment"
                order = 19
            }
        )
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            "pref_schemas" -> {
                SchemaPickerDialog(requireContext()).show()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }
}
