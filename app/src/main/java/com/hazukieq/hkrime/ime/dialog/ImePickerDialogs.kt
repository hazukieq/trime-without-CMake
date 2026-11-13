package com.hazukieq.hkrime.ime.dialog

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.settings.PrefMainActivity
import com.hazukieq.hkrime.settings.components.SchemaPickerDialog

/**
 * 輸入法內（候選條按鈕 / 長按 meta）呼起的對話框內容。
 *
 * 只接受 Activity 上下文（由 [com.hazukieq.hkrime.settings.PickerHostActivity] 承載）：
 * 對話框走普通 Activity Window，不需要 SYSTEM_ALERT_WINDOW，
 * 也不再改寫 Window.attributes 的 token / type。
 */
object ImePickerDialogs {

    /** 候選條左側 ☰：當前方案的開關（简/繁、情、联…），點一行即切換。 */
    @JvmStatic
    fun showSchemaSwitch(context: Context, onDone: () -> Unit) {
        val labels = Rime.getSchemaSwitchDialogLabels()
        if (labels.isEmpty()) {
            showOptions(context, onDone)
            return
        }
        val dialog = ModernListDialog.actionList(
            context,
            "方案开关",
            labels.toList(),
            "✓ 已开启　○ 已关闭，点按一行即可切换",
        ) { index ->
            Rime.toggleOption(index)
            Trime.getServiceOrNull()?.updateComposing()
        }
        dialog.setOnDismissListener { onDone() }
        dialog.show()
    }

    /** 長按 meta/Menu：切換當前方案，或進入「啟用 / 停用方案」。 */
    @JvmStatic
    fun showSchemaMenu(context: Context, onDone: () -> Unit) {
        val names = Rime.getSchemaNames()
        if (names.isEmpty()) {
            val picker = SchemaPickerDialog(context).apply { onDismiss = onDone }
            picker.show()
            return
        }
        val current = Rime.getSchemaIndex()
        val items = ArrayList<String>(names.size + 1)
        names.forEachIndexed { index, name ->
            items.add(if (index == current) "$name（当前）" else name)
        }
        items.add("启用 / 停用方案…")
        // 選擇「啟用 / 停用」時會接續彈出 SchemaPickerDialog，
        // 由它負責收尾，本對話框 dismiss 時不能再呼叫 onDone（否則宿主提前結束）。
        var chained = false
        val dialog = ModernListDialog.actionList(context, "输入方案", items) { index ->
            when {
                index == names.size -> {
                    chained = true
                    val picker = SchemaPickerDialog(context).apply { onDismiss = onDone }
                    picker.show()
                }
                index == current -> Unit
                // 方案切換會觸發 librime 的 schema 通知 → initSchema() + initKeyboard()。
                else -> synchronized(Rime.ENGINE_LOCK) { Rime.selectSchema(index) }
            }
        }
        dialog.setOnDismissListener { if (!chained) onDone() }
        dialog.show()
    }

    /** 無方案開關/無方案時：切換輸入法或進入設定。 */
    @JvmStatic
    fun showOptions(context: Context, onDone: () -> Unit) {
        val message =
            if (Rime.getSchemaId().contentEquals(".default")) {
                context.getString(R.string.no_schemas)
            } else {
                null
            }
        val dialog = ModernListDialog.actionList(
            context,
            context.getString(R.string.trime_app_name),
            listOf(context.getString(R.string.set_ime), context.getString(R.string.other_ime)),
            message,
        ) { index ->
            when (index) {
                0 -> context.startActivity(Intent(context, PrefMainActivity::class.java))
                1 -> {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showInputMethodPicker()
                }
            }
        }
        dialog.setOnDismissListener { onDone() }
        dialog.show()
    }
}
