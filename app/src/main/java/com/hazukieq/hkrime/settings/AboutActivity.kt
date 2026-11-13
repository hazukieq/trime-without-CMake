package com.hazukieq.hkrime.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

        /** 當前打開的授權對話框與其中的 WebView；離開頁面/關閉對話框時必須釋放。 */
        private var activeDialog: com.hazukieq.hkrime.ime.dialog.ModernListDialog? = null
        private var activeWebView: WebView? = null

        /** 編譯信息 + 當前安裝包信息；點擊複製也用這份。 */
        private var buildInfoText: String = BuildConfig.BUILD_INFO

        private fun runtimeBuildInfo(): String {
            val ctx = requireContext()
            val pkg = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }.getOrNull() ?: return BuildConfig.BUILD_INFO
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            val updated = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z",
                java.util.Locale.getDefault(),
            ).format(java.util.Date(pkg.lastUpdateTime))
            return BuildConfig.BUILD_INFO + "\n\n安裝包: " + (pkg.versionName ?: "?") +
                " (" + code + ")\n安裝時間: " + updated
        }
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.tunePreferenceList()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.about_preference, rootKey)
            findPreference<Preference>("about__changelog")
                ?.writeLibraryVersionToSummary(BuildConfig.BUILD_VERSION)
            buildInfoText = runtimeBuildInfo()
            findPreference<Preference>("about__buildinfo")
                ?.writeLibraryVersionToSummary(buildInfoText)
            findPreference<Preference>("about__librime_version")
                ?.writeLibraryVersionToSummary(Rime.get_librime_version())
            findPreference<Preference>("about__opencc_version")
                ?.writeLibraryVersionToSummary(Rime.get_opencc_version())
        }

        /**
         * 打開只讀 WebView 對話框。
         *
         * WebView 是有原生資源（渲染線程/Chromium）的重對象，官方 WebView#destroy() 文檔要求：
         * 必須「先從視圖樹移除」，再在創建它的主線程呼叫 destroy()，之後不得再調用它的任何方法。
         * 這裡在對話框 dismiss 回調裡做釋放，避免每次點擊都留下一個活著的 WebView。
         */
        private fun showWebViewDialog(title: String, url: String) {
            releaseWebViewDialog() // 極端情況下上一次還沒釋放：先清掉，避免兩個 WebView 同時存活
            val ctx = requireContext()
            val webView = WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (320 * resources.displayMetrics.density).toInt(),
                )
                loadUrl(url)
            }
            activeWebView = webView
            val dialog = com.hazukieq.hkrime.ime.dialog.ModernListDialog.custom(ctx, title, webView)
            dialog.setOnDismissListener { releaseWebViewDialog() }
            activeDialog = dialog
            dialog.show()
        }

        /**
         * 一次性釋放對話框與 WebView。
         *
         * - 對話框：Activity 被重建/關閉時若窗口還在，會報 WindowLeaked；Dialog.dismiss() 內部
         *   走 removeViewImmediate，能在 Activity.onDestroy 的泄漏檢查之前同步移除窗口。
         * - WebView：按官方 WebView#destroy() 的要求，先從視圖樹移除，再在主線程 destroy，
         *   之後不再調用該實例的任何方法。
         */
        private fun releaseWebViewDialog() {
            val dialog = activeDialog
            activeDialog = null
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            val webView = activeWebView ?: return
            activeWebView = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }

        override fun onDestroyView() {
            releaseWebViewDialog()
            super.onDestroyView()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            return when (preference.key) {
                "about__licensing" -> {
                    showWebViewDialog(
                        getString(R.string.about__licensing_title),
                        "file:///android_asset/license/open_source_license.html",
                    )
                    true
                }
                "about__used_libraries" -> {
                    showWebViewDialog(
                        getString(R.string.about__used_library_dialog_title),
                        "file:///android_asset/license/library_licenses.html",
                    )
                    true
                }
                "about__buildinfo" -> {
                    val cbm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cbm.text = buildInfoText
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
