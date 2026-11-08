package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.blankj.utilcode.util.ToastUtils
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.core.Trime

/** 顯示輸入法內置數據列表，並回廠選中的數據 */
class ResetAssetsDialog(private val context: Context) {
    private val config = Config.get(context)
    /** 內置數據列表 */
    private var assetItems: Array<String>? = context.assets.list("rime")

    /** 列表勾選狀態 */
    private var checkedStatus: BooleanArray = BooleanArray(assetItems!!.size)

    init { /* 現代對話框在 show() 時才組裝 */ }

    private fun selectAssets() {
        var res = true
        for (i in assetItems?.indices!!) {
            res = if (checkedStatus[i]) {
                config.copyFileOrDir(assetItems!![i], true)
            } else false
        }
        ToastUtils.showShort(
            if (res) R.string.reset_success else R.string.reset_failure
        )
    }

    /** 彈出對話框 */
    fun show() {
        val items = assetItems?.map { it } ?: emptyList()
        val d = com.hazukieq.hkrime.ime.dialog.ModernListDialog.multiChoice(
            context,
            context.getString(R.string.conf__reset_title),
            items,
            checkedStatus,
        ) { flags ->
            flags.copyInto(checkedStatus)
            selectAssets()
        }
        d.window?.let { window ->
            window.attributes.token =
                Trime.getServiceOrNull()?.window?.window?.decorView?.windowToken
            window.attributes.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        d.show()
    }
}
