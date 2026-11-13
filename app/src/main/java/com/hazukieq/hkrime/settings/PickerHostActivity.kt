package com.hazukieq.hkrime.settings

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.hazukieq.hkrime.ime.dialog.ImePickerDialogs
import com.hazukieq.hkrime.settings.components.ColorPickerDialog
import com.hazukieq.hkrime.settings.components.SchemaPickerDialog
import com.hazukieq.hkrime.settings.components.SoundPickerDialog
import com.hazukieq.hkrime.settings.components.ThemePickerDialog

/**
 * 由輸入法 Service 呼起的選擇器對話框宿主（透明、無動畫）。
 *
 * 背景：InputMethodService 直接 show Dialog 時窗口沒有 Activity Token，
 * 過去只能改寫 `Window.attributes.token` + `TYPE_APPLICATION_OVERLAY`
 * （需要 SYSTEM_ALERT_WINDOW 權限）。權限未授予時 Dialog.show() 會拋
 * BadTokenException（"permission denied for window type 2038"），
 * 直接把輸入法進程打死；權限已授予時也讓所有對話框額外依賴懸浮窗權限。
 *
 * 這裡改用一個透明 Activity 承載：對話框落在普通 Activity Window 上，
 * 既不需要懸浮窗權限，也不需要劫持輸入法 Window Token。
 */
class PickerHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置變化後重建：直接結束，避免對話框重複堆疊。
        if (savedInstanceState != null) {
            finish()
            return
        }
        setContentView(FrameLayout(this))
        tuneSystemBars()
        val done: () -> Unit = { if (!isFinishing) finish() }
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_THEME -> ThemePickerDialog(this).apply { onDismiss = done }.show()
            KIND_COLOR -> ColorPickerDialog(this).apply { onDismiss = done }.show()
            KIND_SCHEMA -> SchemaPickerDialog(this).apply { onDismiss = done }.show()
            KIND_SOUND -> SoundPickerDialog(this).apply { onDismiss = done }.show()
            KIND_SCHEMA_SWITCH -> ImePickerDialogs.showSchemaSwitch(this, done)
            KIND_SCHEMA_MENU -> ImePickerDialogs.showSchemaMenu(this, done)
            KIND_OPTIONS -> ImePickerDialogs.showOptions(this, done)
            else -> finish()
        }
    }

    /** 透明宿主頁：系統欄保持透明，圖標顏色跟隨深/淺色模式，避免出現一條灰黑色狀態欄。 */
    private fun tuneSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val night =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val flags = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (night) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    companion object {
        const val EXTRA_KIND = "extra_kind"
        const val KIND_THEME = "theme"
        const val KIND_COLOR = "color"
        const val KIND_SCHEMA = "schema"
        const val KIND_SOUND = "sound"
        const val KIND_SCHEMA_SWITCH = "schema_switch"
        const val KIND_SCHEMA_MENU = "schema_menu"
        const val KIND_OPTIONS = "options"

        /** 最近一次啟動時間：800ms 內的重複點按只算一次，避免連點疊出多個宿主頁。 */
        @Volatile
        private var lastStartAt = 0L

        /** 由輸入法（Service 上下文）啟動。 */
        @JvmStatic
        fun start(context: Context, kind: String) {
            val now = System.currentTimeMillis()
            if (now - lastStartAt < 800L) return
            lastStartAt = now
            context.startActivity(
                Intent(context, PickerHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_KIND, kind),
            )
        }
    }
}
