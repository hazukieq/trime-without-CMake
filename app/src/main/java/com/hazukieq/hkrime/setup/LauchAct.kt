package com.hazukieq.hkrime.setup

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.Utils
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.util.SpvalueStorage
import com.permissionx.guolindev.PermissionX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

class LauchAct : AppCompatActivity() {
    private fun tuneSystemBars() {
        // 啟動頁：透明狀態欄/導航欄；淺色模式用深色圖標，避免白字看不清。
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
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
        setContentView(R.layout.activity_lauch)
        tuneSystemBars()
        playIntro()

        // 品牌啟動頁：停留一小段（配合入場動畫），再按是否完成初始化分流。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
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

    companion object {
        var isCheck=false
        fun shouldSetup(context:Context):Boolean{
            var isAll=false
            val sputil=SpvalueStorage.getInstance(context)
            if (sputil != null) {
                isAll=sputil.getBooleanValue("isAll",false)
            }
            return isAll
        }

        fun setup(context:Context,string: String,boolean: Boolean){
            val sputil=SpvalueStorage.getInstance(context)
            if (sputil != null) {
                sputil.setBooleanValue(string,boolean)
            }
        }

    }

    private fun requestPermission(){
        val requestList=ArrayList<String>()
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M&& Build.VERSION.SDK_INT<= Build.VERSION_CODES.Q){
            requestList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.SYSTEM_ALERT_WINDOW)
        }else if(Build.VERSION.SDK_INT> Build.VERSION_CODES.Q){
            requestList.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.SYSTEM_ALERT_WINDOW)
        }
        if(!requestList.isEmpty()){
            PermissionX.init(this)
                .permissions(requestList)
                .onExplainRequestReason{scope,deniedList->
                    val msg= "即将申请的权限是程序运行必须依赖权限。\n同文输入法 需访问手机存储、用于为您提供文字、符号、表情、语音等内容输入服务。悬浮窗权限用于显示输入法键盘。"
                    scope.showRequestReasonDialog(deniedList,msg,"同意","拒绝")
                }
                .request { allGranted, grantedList, deniedList ->
                    if (allGranted) {
                        setup(this,"isAll",true)
                        ToastUtils.showShort(R.string.external_storage_permission_granted)
                        startActivity(Intent(this,SetupActivity::class.java))
                        finish()
                    } else {
                        ToastUtils.showShort(R.string.external_storage_permission_not_available)
                        ToastUtils.showShort(" $deniedList")
                        requestPermission()
                    }
                }
        }
    }


    fun unzipFile(dest:File,file:String){
        val hks=assets.open(file)
        val zip=ZipInputStream(hks.buffered())
        var entry=zip.nextEntry

        while (entry!=null){
            val current=File("$dest/${entry.name}")
            if(entry.isDirectory) current.mkdirs()
            else{
                current.parentFile?.mkdirs()
                Timber.tag("hs").i(entry.name)
                zip.buffered().copyTo(current.outputStream())
            }
            entry=zip.nextEntry
        }
        zip.closeEntry()
        hks.close()
    }

    /*fun Hks2local():Boolean{
        var isDone: Boolean
        val rootpath=Environment.getExternalStorageDirectory().toString()+"/rime"
        val destFile=File(rootpath)
        try {
            if(!destFile.exists()) {
                destFile.mkdir()
                unzipFile(destFile,"hks/rime.zip")
            }else{
                unzipFile(destFile,"hks/rime.zip")
            }
            isDone=true
        }catch (e:Exception){
            e.printStackTrace()
            isDone=false
        }
        return isDone
    }*/
}