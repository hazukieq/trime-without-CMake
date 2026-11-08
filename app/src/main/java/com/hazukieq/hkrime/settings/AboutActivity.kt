package com.hazukieq.hkrime.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.hazukieq.hkrime.BuildConfig
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.util.AppVersionUtils.writeLibraryVersionToSummary

/**
 * 關於頁：與設置頁同一套「大標題 + 卡片列表」外殼。
 * 純展示，不再有 Toolbar，返回鍵在標題左側。
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pref_activity)

        window.statusBarColor = getColor(R.color.modern_bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (!night) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        findViewById<View>(R.id.header_deploy).visibility = View.GONE
        findViewById<View>(R.id.header_about).visibility = View.GONE
        findViewById<TextView>(R.id.header_title).setText(R.string.settings__about_title)
        findViewById<TextView>(R.id.header_subtitle).setText(R.string.trime_app_name)
        findViewById<ImageButton>(R.id.header_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference, AboutFragment(), FRAGMENT_TAG)
                .commit()
        }
    }

    class AboutFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.about_preference, rootKey)
            findPreference<Preference>("about__changelog")
                ?.writeLibraryVersionToSummary(BuildConfig.BUILD_VERSION)
            findPreference<Preference>("about__buildinfo")
                ?.writeLibraryVersionToSummary(BuildConfig.BUILD_INFO)
            findPreference<Preference>("about__librime_version")
                ?.writeLibraryVersionToSummary(Rime.get_librime_version())
            findPreference<Preference>("about__opencc_version")
                ?.writeLibraryVersionToSummary(Rime.get_opencc_version())
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            return when (preference.key) {
                "about__licensing" -> {
                    val webView = WebView(requireContext())
                    webView.loadUrl("file:///android_asset/license/open_source_license.html")
                    webView.layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (320 * resources.displayMetrics.density).toInt(),
                    )
                    com.hazukieq.hkrime.ime.dialog.ModernListDialog.custom(
                        requireContext(),
                        getString(R.string.about__licensing_title),
                        webView,
                    ).show()
                    true
                }
                "about__used_libraries" -> {
                    val webView = WebView(requireContext())
                    webView.loadUrl("file:///android_asset/license/library_licenses.html")
                    webView.layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (320 * resources.displayMetrics.density).toInt(),
                    )
                    com.hazukieq.hkrime.ime.dialog.ModernListDialog.custom(
                        requireContext(),
                        getString(R.string.about__used_library_dialog_title),
                        webView,
                    ).show()
                    true
                }
                "about__buildinfo" -> {
                    val cbm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cbm.text = BuildConfig.BUILD_INFO
                    Toast.makeText(context, R.string.copy_done, Toast.LENGTH_LONG).show()
                    true
                }
                else -> super.onPreferenceTreeClick(preference)
            }
        }
    }

    companion object {
        private const val FRAGMENT_TAG = "about"
    }
}
