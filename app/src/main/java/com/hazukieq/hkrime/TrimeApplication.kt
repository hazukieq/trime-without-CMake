package com.hazukieq.hkrime

import android.app.Application
import android.content.Context
import android.util.Log
import com.hazukieq.hkrime.data.AppPrefs
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
        fun getInstance() =
            instance ?: throw IllegalStateException("Trime application is not created!")

    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            if (BuildConfig.DEBUG) {
                // 每次進程啟動清空診斷檔。
                runCatching { com.hazukieq.hkrime.util.DiagLog.reset() }
                // 12 鍵每鍵幾十條 DEBUG/VERBOSE 日誌；DebugTree 每條都走 logcat IPC，
                // 在按鍵熱路徑上是實打實的卡頓源。DEBUG 級別只進 LogFileTree（文件），
                // logcat 保留 INFO 以上，排查照樣看 /sdcard/hkrime/logs。
                Timber.plant(object : Timber.DebugTree() {
                    override fun isLoggable(tag: String?, priority: Int): Boolean =
                        priority > Log.DEBUG && super.isLoggable(tag, priority)
                })
            }
            val prefs = AppPrefs.initDefault(this@TrimeApplication)
            prefs.initDefaultPreferences()
            // 日誌落盤到公共目錄（/sdcard/hkrime/logs/）：用戶試用後可以直接回傳文件。
            // 放在 prefs 初始化之後，才能拿到 userDataDir。
            val logTree = com.hazukieq.hkrime.util.LogFileTree(this@TrimeApplication)
            Timber.plant(logTree)
            // 進程標記供性能門禁隔離當前進程的按鍵延遲樣本。
            Timber.i("perfgate: process-start t=%d", System.currentTimeMillis())
            runCatching { com.hazukieq.hkrime.util.LogFileTree.flushNow() }
            // 把本進程啟動前後的 logcat 也抓一段寫進文件（release 包同樣有效）。
            Thread {
                runCatching { logTree.dumpCurrentLogcat() }
            }.apply { name = "hkrime-log-head"; isDaemon = true }.start()
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }
}
