package com.hazukieq.hkrime.util

import android.content.Context
import android.os.Process
import com.hazukieq.hkrime.data.AppPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * 部署（deploy）日誌：把 librime 這次部署幹了什麼，**覆蓋寫**到
 * `<userDataDir>/logs/hkrime-deploy.log`（默認 `/sdcard/hkrime/logs/hkrime-deploy.log`）。
 *
 * 為什麼需要：librime 的 `LOG(INFO)`（`collecting entries from ...`、`Pass 1: total ...`、
 * `rebuilding pack 'hakka'`、`Encode failure ...`）只寫進 logcat，而 logcat 會被刷掉、
 * 一般用戶也拿不到；App 自己的 `hkrime-YYYYMMDD.log` 只是「抓進程 logcat 快照」進去的副本，
 * 內容混雜、而且不保證覆蓋部署那一段。查「為什麼改了詞典不生效」時，最關鍵的就是
 * 「這次部署到底有沒有重建表、讀到了哪些詞典」，所以單獨留一份**每次部署覆蓋**的日誌。
 *
 * 內容分四塊：
 * 1. 頭部：時間、full/incremental、進程號、輸入法方案與詞典文件的 mtime/size（部署前）；
 * 2. 尾部：`build/` 下的 `*.bin` 的 mtime/size 對比，標出 **重建** 的表；
 * 3. **librime glog 全文**：這次部署期間 librime 自己寫出的 INFO/WARNING/ERROR 日誌
 *    （`building table:`、`collecting entries ...`、`num entries:`、`dropping entry`、
 *    `invalid syllable` …），完整抄錄、只取本次新增部分；這是判斷「詞典有沒有進表」的唯一可靠證據；
 * 4. logcat：本進程從部署開始那一刻起的 App 側日誌（native 日誌已進 glog，這裡是補充）。
 *
 * 注意：這裡刻意**只**寫文件、不拋異常——部署日誌壞掉不能影響部署本身。
 */
object DeployLog {

    private const val FILE_NAME = "hkrime-deploy.log"
    private const val LOGCAT_MAX_LINES = 4000
    private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val LOGCAT_TIME = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 一次部署的上下文（部署前後的快照 + logcat 起始時間）。 */
    class Session internal constructor(
        internal val startedAt: Long,
        internal val full: Boolean,
        internal val before: Map<String, String>,
        internal val file: File?,
        internal val pid: Int,
        /** 部署開始時 librime glog 各文件的大小（之後只追加新內容，避免把舊部署的日誌重複寫進來）。 */
        internal val glogBefore: Map<String, Long>,
    )

    fun logFile(context: Context): File? = runCatching {
        val dir = File(AppPrefs.defaultInstance().conf.userDataDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        File(dir, FILE_NAME)
    }.getOrNull()

    fun begin(context: Context, full: Boolean): Session {
        val file = logFile(context)
        val userDir = runCatching { File(AppPrefs.defaultInstance().conf.userDataDir) }.getOrNull()
        val before = snapshot(userDir)
        val startedAt = System.currentTimeMillis()
        runCatching {
            file?.writeText(
                buildString {
                    append("===== hkrime deploy @ ${TIME.format(Date(startedAt))} =====\n")
                    append("mode=").append(if (full) "full" else "incremental")
                    append(" pid=").append(Process.myPid()).append('\n')
                    append("data_dir=").append(userDir?.path).append('\n')
                    // 部署前的詞典/表狀態：排查「改了 yaml 為什麼沒生效」第一眼看這裡
                    append("--- sources (before) ---\n")
                    sourcesSnapshot(userDir).forEach { append(it).append('\n') }
                    append("--- build tables (before) ---\n")
                    before.toSortedMap().forEach { (k, v) -> append(k).append("  ").append(v).append('\n') }
                    append("--- logcat (this deploy) ---\n")
                },
            )
        }.onFailure { Timber.w(it, "DeployLog: begin failed") }
        return Session(startedAt, full, before, file, Process.myPid(),
            glogOffsets(userDir))
    }

    fun end(context: Context, session: Session, error: Throwable? = null) {
        runCatching {
            val file = session.file ?: return@runCatching
            val userDir = runCatching { File(AppPrefs.defaultInstance().conf.userDataDir) }.getOrNull()
            val after = snapshot(userDir)
            val duration = System.currentTimeMillis() - session.startedAt
            val header = buildString {
                append("--- logcat end (duration=").append(duration).append("ms")
                append(", result=").append(if (error == null) "ok" else "ERROR").append(") ---\n")
                if (error != null) append("error: ").append(error).append('\n')
                append("--- build tables (after) ---\n")
                after.toSortedMap().forEach { (name, value) ->
                    val changed = session.before[name] != value
                    append(name).append("  ").append(value)
                    if (changed) append("   <<< REBUILT")
                    append('\n')
                }
            }
            val userDirForGlog = runCatching { File(AppPrefs.defaultInstance().conf.userDataDir) }.getOrNull()
            file.appendText(
                header + "\n" +
                    "--- librime glog (complete, this deploy) ---\n" +
                    collectGlog(userDirForGlog, session) + "\n" +
                    "--- app logcat (this deploy) ---\n" +
                    collectLogcat(session) + "\n",
            )
        }.onFailure { Timber.w(it, "DeployLog: end failed") }
    }

    /** librime glog 目錄：`<userDataDir>/logs/`，文件名形如 `com.hazukieq.hkrime.localhost.u0_a23.log.INFO.*`。 */
    private fun glogFiles(userDir: File?): List<File> {
        val dir = userDir?.resolve("logs") ?: return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.contains(".log.") &&
                (f.name.contains(".INFO.") || f.name.contains(".WARNING.") || f.name.contains(".ERROR."))
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun glogOffsets(userDir: File?): Map<String, Long> =
        glogFiles(userDir).associate { it.name to it.length() }

    /**
     * 把這次部署期間 librime 寫出的 glog **完整**抄進來（預設 INFO 級別，含 `building table:`、
     * `collecting entries ...`、`num entries:`、`dropping entry`、`invalid syllable` 等）。
     *
     * 只抄「部署開始之後新增的部分」：同名文件用開始時的長度做偏移；部署期間新建的文件整份抄。
     * 上游 glog 開着 `ENABLE_LOGGING`（見 `docs/15`），這是排查「詞典有沒有進表」的唯一可靠證據。
     */
    private fun collectGlog(userDir: File?, session: Session): String {
        val files = glogFiles(userDir)
        if (files.isEmpty()) return "(no librime glog files)"
        val sb = StringBuilder()
        for (f in files) {
            val from = session.glogBefore[f.name] ?: 0L
            val len = f.length()
            if (len <= from) continue  // 這次部署沒碰過它
            val text = runCatching {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(from)
                    val size = (len - from).toInt().coerceAtMost(GLOG_MAX_BYTES.toInt())
                    val buf = ByteArray(size)
                    raf.readFully(buf)
                    String(buf, Charsets.UTF_8)
                }
            }.getOrElse { "(read failed: $it)" }
            sb.append("### ").append(f.name).append(" (+")
                .append(len - from).append(" bytes)\n")
            sb.append(text)
            if (!text.endsWith("\n")) sb.append('\n')
            if (len - from > GLOG_MAX_BYTES) sb.append("(truncated at ").append(GLOG_MAX_BYTES).append(" bytes)\n")
        }
        return if (sb.isEmpty()) "(no new librime log during this deploy)" else sb.toString()
    }

    /** 單個 glog 文件最多抄 8MB（正常部署幾百 KB；防止異常日誌把部署日誌撐爆）。 */
    private const val GLOG_MAX_BYTES = 8 * 1024 * 1024L

    /** 部署前後的 `build/` 下的 `*.bin` 快照（mtime+size）。 */
    private fun snapshot(userDir: File?): Map<String, String> {
        val build = userDir?.resolve("build") ?: return emptyMap()
        val files = build.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return emptyMap()
        return files.associate { it.name to "${TIME.format(Date(it.lastModified()))} ${it.length()}" }
    }

    /** 源詞典（`<userDataDir>` 下的 `*.dict.yaml`）的 mtime+size：能看出「最近改了哪份詞表」。 */
    private fun sourcesSnapshot(userDir: File?): List<String> {
        val files = userDir?.listFiles { f -> f.isFile && f.name.endsWith(".dict.yaml") }
            ?: return emptyList()
        return files.sortedBy { it.name }.map { "${it.name}  ${TIME.format(Date(it.lastModified()))} ${it.length()}" }
    }

    /**
     * 抓本進程從部署開始那一刻起的 logcat。
     *
     * `logcat -d --pid=<pid>` 拿到的行首時間是 `MM-dd HH:mm:ss.SSS`；用時間戳過濾掉部署前的舊日誌。
     */
    private fun collectLogcat(session: Session): String {
        val lines = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "--pid=${session.pid}", "-v", "time"))
                .inputStream.bufferedReader().use { it.readLines() }
        }.getOrNull() ?: return "(logcat unavailable)"
        val from = LOGCAT_TIME.format(Date(session.startedAt - 1000)) // 給 1s 餘量，避免時鐘抖動漏行
        val kept = lines.filter { line ->
            val t = line.take(18).trim()
            t.isNotEmpty() && runCatching { LOGCAT_TIME.parse(t) }.getOrNull()?.let {
                LOGCAT_TIME.format(it) >= from
            } ?: false
        }
        val tail = if (kept.size > LOGCAT_MAX_LINES) kept.subList(kept.size - LOGCAT_MAX_LINES, kept.size) else kept
        return if (tail.isEmpty()) "(no logcat lines captured)" else tail.joinToString("\n")
    }
}
