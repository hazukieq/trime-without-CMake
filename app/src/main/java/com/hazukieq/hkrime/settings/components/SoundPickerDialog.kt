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

// show sound schema list
class SoundPickerDialog(
    private val context: Context
) : CoroutineScope by MainScope() {

    private val config = Config.get(context)
    private val soundPackageFiles: Array<String?>
    private val soundPackageNames: Array<String?>
    private var checkedId: Int = 0

    private val progressDialog: Dialog

    /** 對話框流程全部結束後的回調（輸入法宿主頁用來結束透明 Activity）。 */
    var onDismiss: (() -> Unit)? = null
    /** 是否已進入「套用音效包」的異步流程：是的話由 onPostExecute 收尾。 */
    private var applying = false

    init {
        soundPackageFiles = Config.getSoundPackages()
        soundPackageFiles.sort()
        checkedId = soundPackageFiles.binarySearch(config.soundPackage + ".sound.yaml")

        soundPackageNames = Config.getYamlFileNames(soundPackageFiles)

        // Init progress dialog
        progressDialog = createLoadingDialog(context, R.string.sound_progress)
    }

    private fun setSound() {
        if (checkedId >= 0 && checkedId <soundPackageFiles.size)
            config.soundPackage = soundPackageFiles[checkedId]?.replace(".sound.yaml", "")
    }

    /** 调用该方法显示对话框（現代類 Apple 單選） **/
    fun show() {
        val items = soundPackageNames.map { it ?: "" }
        val checked = if (checkedId in items.indices) checkedId else 0
        val d = com.hazukieq.hkrime.ime.dialog.ModernListDialog.singleChoice(
            context,
            context.getString(R.string.keyboard__key_sound_package_title),
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
        setSound()
//        delay(500) // Simulate async task
        return@withContext "OK"
    }

    private fun onPostExecute() {
        progressDialog.dismiss()
        onDismiss?.invoke()
    }
}
