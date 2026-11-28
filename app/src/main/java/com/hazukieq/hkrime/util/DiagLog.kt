package com.hazukieq.hkrime.util

import com.hazukieq.hkrime.data.AppPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 診斷寫入 userDataDir/logs/diag.log，獨立於 Timber/logcat。
 * 由 ENABLED 控制，當前為 true；每次同步 appendText，進程啟動 reset 清空。
 * verbose 另控制逐鍵細節，不由 BuildConfig 自動限制為 Debug。
 */
object DiagLog {

    const val ENABLED = true

    /** 逐鍵/逐次渲染的調試日誌（dbgpre 等）；默認關，排查時改 true 再重編。 */
    @Volatile
    var verbose: Boolean = false

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

    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<String>(2048)

    @Volatile
    private var writerStarted = false

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            Thread({
                val batch = StringBuilder(4096)
                while (true) {
                    try {
                        val first = writeQueue.take()
                        batch.setLength(0)
                        batch.append(first)
                        var drained = 0
                        while (drained < 64) {
                            val next = writeQueue.poll() ?: break
                            batch.append(next)
                            drained++
                        }
                        val file = runCatching { logDir()?.resolve("diag.log") }.getOrNull() ?: continue
                        runCatching { file.appendText(batch.toString()) }
                    } catch (_: Throwable) {
                    }
                }
            }, "diaglog-writer").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun write(level: String, tag: String, message: String) {
        if (!ENABLED) return
        ensureWriter()
        val line = "${timeFormat.format(Date())} $level/$tag[${Thread.currentThread().name}] $message\n"
        writeQueue.offer(line)
    }

    /** 一次性把「本進程」的診斷檔清空（每次進程啟動調一次，避免越滾越大）。 */
    fun reset() {
        if (!ENABLED) return
        writeQueue.clear()
        runCatching { logDir()?.resolve("diag.log")?.writeText("") }
    }
}
