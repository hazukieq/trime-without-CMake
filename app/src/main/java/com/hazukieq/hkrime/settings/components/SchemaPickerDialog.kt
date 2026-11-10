package com.hazukieq.hkrime.settings.components

import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.util.RimeUtils
import com.hazukieq.hkrime.util.createLoadingDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

class SchemaPickerDialog(
    private val context: Context
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var schemaItems: Array<String?>
    private lateinit var checkedStatus: BooleanArray
    private var schemaMapList: List<Map<String?, String?>>? = availableSchemaList()
    private lateinit var schemaNames: Array<String?>

    private var progressDialog: ProgressDialog

    companion object {
        private class SortByName : Comparator<Map<String?, String?>> {
            override fun compare(o1: Map<String?, String?>, o2: Map<String?, String?>): Int {
                val s1 = o1["schema_id"]
                val s2 = o2["schema_id"]
                return if (s1 != null && s2 !== null) {
                    s1.compareTo(s2)
                } else -1
            }
        }
    }

    init {
        progressDialog = createLoadingDialog(context, R.string.schemas_progress)
    }

    /**
     * 取方案列表前先確保 librime 初始化過。
     *
     * 用戶 2026-11-11 反饋「設置裡點選擇方案直接 crash」：設置界面是**新進程**，
     * 若沒先 `Rime.get(context)`，deployer 的 `shared_data_dir/user_data_dir` 還是空的，
     * `SwitcherSettings::GetAvailableSchemasFromDirectory("")` 會去 iterate `.`——
     * Android 上 cwd 不可讀 → 原生層 `fs::filesystem_error` 沒被 catch → abort（整個進程掛掉）。
     *
     * 兩邊都補：這裡先初始化 + try/catch；librime fork 裡也加了空路徑/異常的防護。
     */
    private fun availableSchemaList(): List<Map<String?, String?>> = runCatching {
        Rime.get(context, false)
        Rime.get_available_schema_list()
    }.getOrElse {
        Timber.w(it, "SchemaPickerDialog: get_available_schema_list failed")
        emptyList()
    }

    private fun showPickerDialog() {
        if (schemaMapList.isNullOrEmpty() || !::schemaNames.isInitialized || schemaNames.isEmpty()) {
            val d = com.hazukieq.hkrime.ime.dialog.ModernListDialog.confirm(
                context,
                context.getString(R.string.pref_schemas),
                context.getString(R.string.no_schemas),
            ) {}
            appendDialogParams(d)
            d.show()
            return
        }
        val items = schemaNames.map { it ?: "" }
        val d = com.hazukieq.hkrime.ime.dialog.ModernListDialog.multiChoice(
            context,
            context.getString(R.string.pref_schemas),
            items,
            checkedStatus,
        ) { flags ->
            flags.copyInto(checkedStatus)
            progressDialog = createLoadingDialog(context, R.string.deploy_progress).also {
                appendDialogParams(it)
                it.show()
            }
            launch {
                try {
                    setSchema()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to set schema")
                } finally {
                    progressDialog.dismiss()
                }
            }
        }
        appendDialogParams(d)
        d.show()
    }

    private fun appendDialogParams(dialog: Dialog) {
        dialog.window?.let { window ->
            window.attributes.token = Trime.getServiceOrNull()?.window?.window?.decorView?.windowToken
            window.attributes.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    private fun initSchemas() {
        if (schemaMapList.isNullOrEmpty()) {
            // 尝试一次配置文件重置 缺失 default.custom.yaml 导致方案为空
            Config.get(context).prepareRime(context)
            schemaMapList = availableSchemaList()
            if (schemaMapList.isNullOrEmpty()) return
        }
        schemaMapList!!.sortedWith(SortByName())
        val selectedSchemas = Rime.get_selected_schema_list()
        val selectedIds = ArrayList<String>()
        schemaMapList!!.size.let {
            schemaNames = arrayOfNulls(it)
            checkedStatus = BooleanArray(it)
            schemaItems = arrayOfNulls(it)
        }

        if (selectedSchemas.size > 0) {
            for (m in selectedSchemas) {
                m["schema_id"]?.let { selectedIds.add(it) }
            }
        }
        for ((i, m) in schemaMapList!!.withIndex()) {
            schemaNames[i] = m["name"]
            m["schema_id"].let { // schema_id
                schemaItems[i] = it
                checkedStatus[i] = selectedIds.contains(it)
            }
        }
    }

    private suspend fun setSchema() {
        val checkedIds = ArrayList<String>()
        for ((i, b) in checkedStatus.withIndex()) {
            if (b) schemaItems[i]?.let { checkedIds.add(it) }
        }
        if (checkedIds.size > 0) {
            val schemaIdList = arrayOfNulls<String>(checkedIds.size)
            checkedIds.toArray(schemaIdList)
            Rime.select_schemas(schemaIdList)
            RimeUtils.deploy(context)
            // 部署後方案清單可能變了（當前方案被停用時引擎會回退到清單第一項），
            // 但輸入法的鍵盤/候選 UI 不會自己跟著換：這裡補一次重綁。
            // 與 SchemaEvent 通知走同一個入口（initKeyboard = reset + bind + updateComposing）。
            withContext(Dispatchers.Main) {
                runCatching { Trime.getServiceOrNull()?.initKeyboard() }
                    .onFailure { Timber.e(it, "Failed to rebind keyboard after schema deploy") }
            }
        }
    }

    /** 调用该方法显示对话框 **/
    fun show() = execute()

    private fun execute() = launch {
        onPreExecute()
        doInBackground()
        onPostExecute()
    }

    private fun onPreExecute() {
        appendDialogParams(progressDialog)
        progressDialog.show()
    }

    private suspend fun doInBackground(): String = withContext(Dispatchers.IO) {
        initSchemas()
//        delay(1000) // Simulate async task
        return@withContext "OK"
    }

    private fun onPostExecute() {
        progressDialog.dismiss()
        showPickerDialog()
    }
}
