package com.hazukieq.hkrime.util

import com.hazukieq.hkrime.data.AppPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直接寫進 `<userDataDir>/logs/diag.log` 的診斷日誌（**不經 Timber、不經 logcat**）。
 *
 * 為什麼另起一份（用戶 2026-11-11 要求「通通寫入 hkrime/logs」）：
 * - logcat 會被系統日誌沖掉；
 * - Timber 的 LogFileTree 會被 2MB 輪轉、按進程分檔，排查時常常「剛好那段在另一個檔」；
 * - 面板/快照這種「卡死」場景最需要的是**能確認最後走到哪一步**，所以這裡每條都即時 append + flush。
 *
 * 只在 debug 構建裡寫（[enabled]）。
 */
object DiagLog {

    const val ENABLED = true

    @Volatile
    private var dir: File? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 日誌目錄（惰性解析，prefs 還沒就緒時返回 null，不拋異常）。 */
    private fun logDir(): File? {
        dir?.let { return it }
        val base = runCatching { AppPrefs.defaultInstance().conf.userDataDir }.getOrNull()
            ?: return null
        return File(base, "logs").also { d ->
            runCatching { d.mkdirs() }
            dir = d
        }
    }

    fun d(tag: String, message: String) = write("D", tag, message)

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String, t: Throwable? = null) =
        write("E", tag, message + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun write(level: String, tag: String, message: String) {
        if (!ENABLED) return
        val file = runCatching { logDir()?.resolve("diag.log") }.getOrNull() ?: return
        val line = "${timeFormat.format(Date())} $level/$tag[${Thread.currentThread().name}] $message\n"
        runCatching {
            file.appendText(line)
        }
    }

    /** 一次性把「本進程」的診斷檔清空（每次進程啟動調一次，避免越滾越大）。 */
    fun reset() {
        if (!ENABLED) return
        runCatching { logDir()?.resolve("diag.log")?.writeText("") }
    }
}
