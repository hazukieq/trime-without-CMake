package com.hazukieq.hkrime

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import cat.ereza.customactivityoncrash.config.CaocConfig
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.settings.LogActivity
import timber.log.Timber

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid
    }

    override fun onCreate() {
        super.onCreate()
        CaocConfig.Builder
            .create()
            .errorActivity(LogActivity::class.java)
            .enabled(!BuildConfig.DEBUG)
            .apply()
        instance = this
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }else{
                Timber.plant(CrashReportingTree())
            }
            val prefs = AppPrefs.initDefault(this@TrimeApplication)
            prefs.initDefaultPreferences()
            // 日誌落盤到公共目錄（/sdcard/hkrime/logs/）：用戶試用後可以直接回傳文件。
            // 放在 prefs 初始化之後，才能拿到 userDataDir。
            val logTree = com.hazukieq.hkrime.util.LogFileTree(this@TrimeApplication)
            Timber.plant(logTree)
            // 把本進程啟動前後的 logcat 也抓一段寫進文件（release 包同樣有效）。
            Thread {
                runCatching { logTree.dumpCurrentLogcat() }
            }.apply { name = "hkrime-log-head"; isDaemon = true }.start()
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }

    inner class CrashReportingTree: Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if(priority==Log.VERBOSE||priority==Log.DEBUG||priority==Log.INFO)return
            if(t!=null){
                if(priority==Log.ERROR){
                    // record last pid for crash logs
                    val appPrefs = AppPrefs.defaultInstance()
                    val currentPid = Process.myPid()
                    appPrefs.general.pid.apply {
                        lastPid = this
                        Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
                    }
                    appPrefs.general.pid = currentPid
                }
            }
        }
    }
}
