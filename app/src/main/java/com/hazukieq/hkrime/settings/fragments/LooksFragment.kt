package com.hazukieq.hkrime.settings.fragments

import com.hazukieq.hkrime.settings.tunePreferenceList

import android.os.Bundle
import android.view.Menu
import androidx.core.view.forEach
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import android.view.View
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.symbol.SymbolRepository
import com.hazukieq.hkrime.settings.components.ColorPickerDialog
import com.hazukieq.hkrime.settings.components.KeyboardLayoutPickerDialog
import com.hazukieq.hkrime.settings.components.SymbolTabsManagerDialog

class LooksFragment : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.tunePreferenceList()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.looks_preference)

        setHasOptionsMenu(true)
        refreshLayoutSummary()
        refreshSymbolTabsSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshLayoutSummary()
        refreshSymbolTabsSummary()
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.forEach { item -> item.isVisible = false }
        super.onPrepareOptionsMenu(menu)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference?.key) {
            "looks__selected_color" -> {
                ColorPickerDialog(requireContext()).show()
                true
            }
            "looks__selected_keyboard_layout" -> {
                KeyboardLayoutPickerDialog(requireContext()) {
                    refreshLayoutSummary()
                }.show()
                true
            }
            "looks__symbol_tabs" -> {
                SymbolTabsManagerDialog(requireContext()) {
                    refreshSymbolTabsSummary()
                }.show()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun refreshLayoutSummary() {
        val pref = findPreference<Preference>("looks__selected_keyboard_layout") ?: return
        runCatching {
            val ctx = context ?: return
            val config = Config.get(ctx)
            config.reset()
            val schemaId = Rime.getSchemaId().orEmpty()
            val schemaName = Rime.getSchemaName().orEmpty().ifEmpty { schemaId }
            val schemaType = config.currentSchemaKeyboardType
            val resolvedId = config.resolvedDefaultLayoutId
            val displayName = config.availableLayoutsForCurrentSchema
                .firstOrNull { it.id == resolvedId }?.name ?: resolvedId
            val overrideId = AppPrefs.defaultInstance().looks.selectedKeyboardLayout(schemaId)
            val suffix = if (overrideId.isEmpty()) {
                getString(R.string.looks__keyboard_layout_follow_theme)
            } else {
                displayName
            }
            pref.summary = if (schemaName.isNotEmpty()) {
                "$schemaName ($schemaType) · $suffix"
            } else {
                suffix
            }
        }
    }

    private fun refreshSymbolTabsSummary() {
        val pref = findPreference<Preference>("looks__symbol_tabs") ?: return
        runCatching {
            val ctx = context ?: return
            val prefs = AppPrefs.defaultInstance()
            val visible = SymbolRepository.loadCategories(
                context = ctx,
                userDataDir = prefs.conf.userDataDir,
                order = prefs.looks.symbolTabOrder,
                hidden = prefs.looks.symbolTabHidden,
                includeHidden = false,
            )
            if (visible.isNotEmpty()) {
                val preview = visible.take(4).joinToString(" · ") { it.title }
                val suffix = if (visible.size > 4) "…" else ""
                pref.summary = "${visible.size} 项：$preview$suffix"
            }
        }
    }
}
