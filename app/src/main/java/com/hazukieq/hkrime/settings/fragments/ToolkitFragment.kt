package com.hazukieq.hkrime.settings.fragments

import com.hazukieq.hkrime.settings.tunePreferenceList

import android.os.Bundle
import android.view.Menu
import androidx.core.view.forEach
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import android.view.View
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.util.ShortcutUtils

@Suppress("unused")
class ToolkitFragment : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.tunePreferenceList()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.real_time_logs)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    ShortcutUtils.launchLogActivity(context)
                    true
                }
            }
        )
        preferenceScreen = screen

        setHasOptionsMenu(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.forEach { item -> item.isVisible = false }
        super.onPrepareOptionsMenu(menu)
    }
}
