package com.hazukieq.hkrime.settings.fragments

import com.hazukieq.hkrime.settings.tunePreferenceList

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import androidx.core.view.forEach
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import android.view.View
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.settings.components.SoundPickerDialog

class KeyboardFragment :
    PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.tunePreferenceList()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.keyboard_preference)

        setHasOptionsMenu(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.forEach { item -> item.isVisible = false }
        super.onPrepareOptionsMenu(menu)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val trime = Trime.getServiceOrNull()
        val prefs = AppPrefs.defaultInstance()
        prefs.sync()
        when (key) {
            "keyboard__key_long_press_timeout",
            "keyboard__key_repeat_interval" -> {
                trime?.resetKeyboard()
            }
            "keyboard__show_window" -> {
                trime?.resetCandidate()
            }
            "keyboard__inline_preedit", "keyboard__soft_cursor" -> {
                trime?.loadConfig()
            }
        }
    }
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference?.key) {
            "keyboard__key_sound_package" -> {
                SoundPickerDialog(requireContext()).show()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }
    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }
}
