package com.hazukieq.hkrime.settings.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.forEach
import androidx.preference.Preference
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

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            KEY_HW_MANUAL -> {
                showHardwareManualDialog()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun showHardwareManualDialog() {
        val ctx = context ?: return
        val density = ctx.resources.displayMetrics.density
        val padH = (22f * density).toInt()
        val padV = (14f * density).toInt()
        val contentView = TextView(ctx).apply {
            text = getString(R.string.keyboard__hw_manual_content)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.38f)
            setTextIsSelectable(true)
            setPadding(padH, padV, padH, padV)
        }
        val scrollView = ScrollView(ctx).apply {
            addView(contentView)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.keyboard__hw_manual_title)
            .setView(scrollView)
            .setPositiveButton(R.string.keyboard__hw_manual_close, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    companion object {
        private const val KEY_HW_MANUAL = "keyboard__hw_manual"
    }
}
