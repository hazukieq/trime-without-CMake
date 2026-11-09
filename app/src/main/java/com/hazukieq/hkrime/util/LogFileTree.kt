package com.hazukieq.hkrime.util

import android.content.Context
import android.os.Process
import android.util.Log
import com.hazukieq.hkrime.BuildConfig
import com.hazukieq.hkrime.data.AppPrefs
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 把 App 內部日誌寫到公共目錄，方便用戶試用後把文件回傳。
 *
 * - 日誌先緩衝在內存（[FLUSH_THRESHOLD] 條），滿了就 append 到文件；
 * - 文件位置：`<userDataDir>/logs/hkrime-YYYYMMDD.log`（默認就是 `/sdcard/hkrime/logs/`）；
 *   單檔超過 [MAX_FILE_BYTES] 就輪轉成 `.1`；
 * - 進程啟動時 [dumpCurrentLogcat] 會把本進程啟動前後的 logcat 抓一段寫進文件，
 *   這樣反饋回來的文件從啟動就有上下文（release 包也能用，見 TrimeApplication）。
 *
 * 這裡刻意不依賴 Timber 的 DebugTree：release 包只 plant 了 CrashReportingTree（只留 WARN 以上），
 * 而排障最需要的是 INFO。
 */
class LogFileTree(private val context: Context) : Timber.Tree() {

    companion object {
        private const val FLUSH_THRESHOLD = 60
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val KEEP_FILES = 7
        private const val LOGCAT_HEAD_LINES = 300

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        private const val LEVELS = "VDIWEF"

        @Volatile
        private var instance: LogFileTree? = null

        /** 把緩衝日誌立刻落盤（退到後台、IME 隱藏、崩潰前都調一下）。 */
        @JvmStatic
        fun flushNow() {
            instance?.flush()
        }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    init {
        instance = this
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // release 包裡只留 INFO 以上：DEBUG/VERBOSE 有大量鍵盤布局調試輸出，
        // 寫進文件會很快把 2MB 撐滿，對排障反而沒用（debug 包全留）。
        if (!BuildConfig.DEBUG && priority < Log.INFO) return
        val line = buildString(96) {
            append(timeFormat.format(Date()))
            append(' ')
            append(if (priority in Log.VERBOSE..Log.ASSERT) LEVELS[priority - Log.VERBOSE] else '?')
            append('/')
            append(tag ?: "-")
            append(": ")
            append(message)
            if (t != null) {
                append('\n')
                append(Log.getStackTraceString(t))
            }
        }
        synchronized(lock) {
            buffer.addLast(line)
            if (buffer.size >= FLUSH_THRESHOLD) flushLocked()
        }
    }

    fun flush() = synchronized(lock) { flushLocked() }

    /**
     * 抓本進程當前 logcat（`logcat --pid=<pid> -d`）寫進文件開頭。
     * 用於「用戶反饋時文件裡也要有啟動那一段日誌」。必須在後台線程調用。
     */
    fun dumpCurrentLogcat(maxLines: Int = LOGCAT_HEAD_LINES) {
        runCatching {
            val lines = Runtime.getRuntime()
                .exec(arrayOf("logcat", "--pid=${Process.myPid()}", "-d"))
                .inputStream.bufferedReader().use { it.readLines() }
            if (lines.isEmpty()) return@runCatching
            val tail = if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
            synchronized(lock) {
                val f = currentFile() ?: return@runCatching
                f.appendText(
                    buildString {
                        append("\n========== logcat snapshot (${tail.size} lines) ==========\n")
                        tail.forEach { append(it).append('\n') }
                        append("========== end of logcat snapshot ==========\n")
                    }
                )
            }
        }.onFailure { Log.w("LogFileTree", "dumpCurrentLogcat failed: ${it.message}") }
    }

    private fun flushLocked() {
        if (buffer.isEmpty()) return
        runCatching {
            val f = currentFile() ?: return
            val text = buildString {
                while (buffer.isNotEmpty()) {
                    append(buffer.removeFirst()).append('\n')
                }
            }
            f.appendText(text)
        }.onFailure {
            // 不能在這裡再用 Timber（會遞歸），直接走 android.util.Log
            Log.w("LogFileTree", "flush failed: ${it.message}")
            buffer.clear()
        }
    }

    /** 當天的日誌文件；超過大小就輪轉。目錄不可寫時回傳 null（只保留內存緩衝）。 */
    private fun currentFile(): File? {
        val dir = runCatching { logDir() }.getOrNull() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(dir, "hkrime-$day.log")
        if (file.length() > MAX_FILE_BYTES) {
            val rotated = File(dir, "hkrime-$day.1.log")
            runCatching {
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
        }
        if (!file.exists()) {
            runCatching {
                file.appendText(header())
                trimOldFiles(dir)
            }
        }
        return file
    }

    private fun logDir(): File? {
        val userDir = runCatching { AppPrefs.defaultInstance().conf.userDataDir }.getOrNull()
        val base = when {
            !userDir.isNullOrBlank() -> File(userDir)
            else -> context.getExternalFilesDir(null)
        } ?: return null
        return File(base, "logs")
    }

    private fun header(): String = buildString {
        append("========== hkrime log ==========\n")
        append("build: ").append(runCatching { BuildConfig.BUILD_INFO }.getOrNull()).append('\n')
        append("version: ").append(runCatching { BuildConfig.BUILD_VERSION }.getOrNull()).append('\n')
        append("debug: ").append(BuildConfig.DEBUG).append('\n')
        append("pid: ").append(Process.myPid()).append('\n')
        append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("========== start ==========\n")
    }

    private fun trimOldFiles(dir: File) {
        runCatching {
            val logs = dir.listFiles { f -> f.isFile && f.name.startsWith("hkrime-") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: return
            logs.drop(KEEP_FILES).forEach { it.delete() }
        }
    }
}
