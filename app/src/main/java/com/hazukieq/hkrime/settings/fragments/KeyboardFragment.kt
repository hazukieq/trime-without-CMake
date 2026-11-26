package com.hazukieq.hkrime.settings.fragments

import com.hazukieq.hkrime.settings.tunePreferenceList

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import androidx.core.view.forEach
import androidx.appcompat.app.AlertDialog
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
            AppPrefs.Keyboard.CANDIDATE_ALGORITHM_ENABLED -> {
                trime?.textInputManager?.onCandidateAlgorithmChanged()
            }
            "keyboard__key_long_press_timeout",
            "keyboard__key_repeat_interval" -> {
                trime?.resetKeyboard()
            }
            "keyboard__show_window" -> {
                // 懸浮編碼窗（PopupWindow）需要「顯示在其他應用上方」權限；
                // 只有用戶主動開啟該功能時才引導授權，不在首次啟動時強索。
                val enabled = AppPrefs.defaultInstance().keyboard.popupWindowEnabled
                if (enabled) askOverlayPermissionIfNeeded()
                trime?.resetCandidate()
            }
            "keyboard__inline_preedit", "keyboard__soft_cursor" -> {
                trime?.loadConfig()
            }
        }
    }
    /** 用戶開啟「顯示懸浮窗」但缺少懸浮窗權限時，就地說明並引導到系統設置頁。 */
    private fun askOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val ctx = context ?: return
        if (Settings.canDrawOverlays(ctx)) return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.keyboard__show_window_title)
            .setMessage(R.string.alert_window_access_required)
            .setPositiveButton(R.string.permission_go_settings) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // 沒有懸浮窗權限就顯示不了：把開關退回關閉，避免「開關開着卻沒效果」的困惑。
                preferenceManager.sharedPreferences
                    ?.edit()
                    ?.putBoolean(AppPrefs.Keyboard.FLOATING_WINDOW_ENABLED, false)
                    ?.apply()
            }
            .show()
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
