package com.hazukieq.hkrime.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.ToastUtils
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.util.SpvalueStorage
import com.permissionx.guolindev.PermissionX
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 品牌啟動頁：等初始化完成後決定進設定頁還是申請權限。
 *
 * 權限分兩段，互不阻塞：
 * 1. 存儲權限（讀寫方案/詞庫必需）：被拒絕時給「去設置 / 退出」兩個選擇，
 *    不再像舊實現那樣在 denied 回調裡遞歸調用 requestPermission()（無限彈窗、ANR）。
 * 2. 「顯示在其他應用上方」權限（可選）：只影響鍵盤上方的懸浮編碼窗，
 *    可以跳過，跳過後照常進入初始化，基本輸入不受影響。
 *    輸入法內的對話框已改由 PickerHostActivity 承載，不再依賴此權限。
 */
class LaunchActivity : AppCompatActivity() {

    private fun tuneSystemBars() {
        // 啟動頁：透明狀態欄/導航欄；淺色模式用深色圖標，避免白字看不清。
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            var flags = window.decorView.systemUiVisibility or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            if (!night) flags = flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch)
        tuneSystemBars()
        playIntro()

        // 品牌啟動頁：停留一小段（配合入場動畫），再按是否完成初始化分流。
        Handler(Looper.getMainLooper()).postDelayed({
            if (shouldSetup(this)) {
                startActivity(
                    Intent(this, com.hazukieq.hkrime.settings.PrefMainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            } else {
                requestPermission()
            }
        }, 520)
    }

    /** 啟動頁入場：圖標淡入+輕微放大，文字依次淡入（總時長 ~520ms，不打斷原有流程）。 */
    private fun playIntro() {
        val icon = findViewById<android.view.View>(R.id.launch_icon)
        val name = findViewById<android.view.View>(R.id.launch_name)
        val tagline = findViewById<android.view.View>(R.id.launch_tagline)
        val version = findViewById<android.view.View>(R.id.launch_version)
        findViewById<android.widget.TextView>(R.id.launch_version)?.text =
            "v" + com.hazukieq.hkrime.BuildConfig.VERSION_NAME

        icon.apply {
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(
                android.view.animation.DecelerateInterpolator(),
            ).start()
        }
        listOf(name to 120L, tagline to 200L, version to 280L).forEach { (view, delay) ->
            view.alpha = 0f
            view.animate().alpha(1f).setStartDelay(delay).setDuration(320).start()
        }
    }

    private fun requestPermission() {
        val requestList = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
        ) {
            requestList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            requestList.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        }
        if (requestList.isEmpty()) {
            onStorageReady()
            return
        }
        PermissionX.init(this)
            .permissions(requestList)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    getString(R.string.external_storage_access_required),
                    "同意",
                    "拒绝",
                )
            }
            .request { allGranted, _, deniedList ->
                if (allGranted) {
                    ToastUtils.showShort(R.string.external_storage_permission_granted)
                    onStorageReady()
                } else {
                    ToastUtils.showShort(R.string.external_storage_permission_not_available)
                    Timber.w("儲存權限被拒絕：%s", deniedList)
                    showStorageDeniedDialog()
                }
            }
    }

    /**
     * 存儲權限被拒：說明用途，提供「去設置 / 退出」。
     * 舊實現在這裡再次調用 requestPermission()，用戶拒絕就無限彈窗，會被系統 ANR 攔截。
     */
    private fun showStorageDeniedDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.external_storage_permission_not_available)
            .setMessage(R.string.external_storage_access_required)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_go_settings) { _, _ ->
                openAppSettings()
                finish()
            }
            .setNegativeButton(R.string.permission_exit) { _, _ -> finish() }
            .show()
    }

    /** 存儲權限就緒：記錄初始化完成，再按需詢問可選的懸浮窗權限，最後進初始化流程。 */
    private fun onStorageReady() {
        setup(this, "isAll", true)
        if (canDrawOverlays()) openSetup() else askOverlayPermission()
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /**
     * 「顯示在其他應用上方」：僅鍵盤上方的懸浮編碼窗需要，可跳過。
     * 舊實現在首次啟動就把它和存儲權限綁在一起強索，任一被拒都無法進入初始化。
     */
    private fun askOverlayPermission() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.keyboard__show_window_title)
            .setMessage(R.string.alert_window_access_required)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_go_settings) { _, _ ->
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                    REQ_OVERLAY,
                )
            }
            .setNegativeButton(R.string.permission_skip) { _, _ -> openSetup() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) openSetup()
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { Timber.w(it, "無法打開應用設置頁") }
    }

    companion object {
        private const val REQ_OVERLAY = 1001

        var isCheck = false

        fun shouldSetup(context: Context): Boolean {
            val sputil = SpvalueStorage.getInstance(context)
            return sputil?.getBooleanValue("isAll", false) ?: false
        }

        fun setup(context: Context, string: String, boolean: Boolean) {
            val sputil = SpvalueStorage.getInstance(context)
            sputil?.setBooleanValue(string, boolean)
        }
    }

    @Suppress("unused")
    fun unzipFile(dest: File, file: String) {
        val hks = assets.open(file)
        val zip = ZipInputStream(hks.buffered())
        var entry = zip.nextEntry
        while (entry != null) {
            val current = File("$dest/${entry.name}")
            if (entry.isDirectory) {
                current.mkdirs()
            } else {
                current.parentFile?.mkdirs()
                Timber.tag("hs").i(entry.name)
                zip.buffered().copyTo(current.outputStream())
            }
            entry = zip.nextEntry
        }
        zip.closeEntry()
        hks.close()
    }
}
