package com.hazukieq.hkrime.settings.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.core.view.forEach
import androidx.preference.PreferenceFragmentCompat
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.settings.tunePreferenceList

/**
 * 物理键盘独立设置板块：所有外设专属参数（接手模式、横竖排、每页候选数、外设独立预编辑预览）
 * 均与触屏软键盘设置彻底隔离，互不干扰。
 */
class HardwareKeyboardFragment :
    PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.tunePreferenceList()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.hardware_keyboard_preference)
        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.forEach { item -> item.isVisible = false }
        @Suppress("DEPRECATION")
        super.onPrepareOptionsMenu(menu)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val prefs = AppPrefs.defaultInstance()
        prefs.sync()
        if (key == AppPrefs.Keyboard.HW_CANDIDATE_COUNT) {
            val count = prefs.keyboard.hwCandidateCount
            Thread({
                com.hazukieq.hkrime.core.Rime.applyHardwarePageSize(count)
            }, "hw-page-size").start()
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }
}
