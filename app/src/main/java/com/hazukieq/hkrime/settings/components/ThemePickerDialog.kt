package com.hazukieq.hkrime.settings.components

import android.app.Dialog
import android.content.Context
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.util.createLoadingDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 顯示配色方案列表 */
class ThemePickerDialog(
    private val context: Context
) : CoroutineScope by MainScope() {

    private val config = Config.get(context)
    private val themeKeys: Array<String?>
    private val themeNames: Array<String?>
    private var checkedId: Int = 0
    @Suppress("DEPRECATION")
    private val progressDialog: Dialog

    /** 對話框流程全部結束後的回調（輸入法宿主頁用來結束透明 Activity）。 */
    var onDismiss: (() -> Unit)? = null
    /** 是否已進入「套用主題」的異步流程：是的話由 onPostExecute 收尾，dismiss 不再觸發回調。 */
    private var applying = false

    init {
        val themeFile = config.theme + ".yaml"
        themeKeys = Config.getThemeKeys(true)
        themeKeys.sort()
        checkedId = themeKeys.binarySearch(themeFile)

        val themeMap = HashMap<String, String>().apply {
            put("tongwenfeng", context.getString(R.string.pref_themes_name_tongwenfeng))
            put("trime", context.getString(R.string.pref_themes_name_trime))
        }
        val nameArray = Config.getYamlFileNames(themeKeys)
        themeNames = arrayOfNulls(nameArray.size)

        for (i in nameArray.indices) {
            val themeName = themeMap[nameArray[i]]
            if (themeName == null) {
                themeNames[i] = nameArray[i]
            } else {
                themeNames[i] = themeName
            }
        }
        // Init progress dialog
        progressDialog = createLoadingDialog(context, R.string.themes_progress)
    }

    private fun setTheme() { config.theme = themeKeys[checkedId]?.replace(".yaml", "") }

    /** 调用该方法显示对话框（現代類 Apple 單選） **/
    fun show() {
        val items = themeNames.map { it ?: "" }
        val checked = if (checkedId in items.indices) checkedId else 0
        val d = com.hazukieq.hkrime.ime.dialog.ModernListDialog.singleChoice(
            context,
            context.getString(R.string.looks__selected_theme_title),
            items,
            checked,
        ) { idx ->
            checkedId = idx
            applying = true
            execute()
        }
        d.setOnDismissListener { if (!applying) onDismiss?.invoke() }
        d.show()
    }

    private fun execute() = launch {
        try {
            onPreExecute()
            doInBackground()
            onPostExecute()
        } catch (e: Exception) {
            onPostExecute()
        }
    }

    private fun onPreExecute() {
        progressDialog.show()
    }

    private suspend fun doInBackground(): String = withContext(Dispatchers.IO) {
        setTheme()
//        delay(500) // Simulate async task
        return@withContext "OK"
    }

    private fun onPostExecute() {
        progressDialog.dismiss()
        Trime.getServiceOrNull()?.initKeyboard() // 實時生效
        onDismiss?.invoke()
    }
}
