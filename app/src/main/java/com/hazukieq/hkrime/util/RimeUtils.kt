package com.hazukieq.hkrime.util

import android.content.Context
import com.blankj.utilcode.util.ToastUtils
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess
import timber.log.Timber

/**
 * Wrapper of often-used native methods in [Rime].
 **/
object RimeUtils {
    /** Check Rime backend health. **/
    fun check() {
        Rime.check(true)
        exitProcess(0) // Clear the memory
    }

    /**
     * Deploy means reset Rime instance.
     *
     * @param full 完整重建（重編譯全部詞典/表）。**默認 false = 增量部署**：
     *   librime 的 start_maintenance(false) 會先 detect_modifications，只重建改動過的
     *   文件。裝了大方案（如霧凇拼音，table 60MB）時，full=true 每次都全量重建，
     *   要 1~2 分鐘，看起來像「卡住」（用戶 2026-11-09 反饋）；增量一般幾秒。
     *   只有需要強制重建（開發/排查）才傳 true。
     */
    suspend fun deploy(context: Context, full: Boolean = false) = withContext(Dispatchers.IO) {
        // 全字母方案（客語 26 鍵、朙月拼音…）先補 App 適配 patch，再部署讓 patch 生效。
        // 見 SchemaCompat：面板左列的音節分組靠候選 comment = 精確拼寫。
        runCatching { SchemaCompat.ensureRimeLua(context) }
            .onFailure { Timber.e(it, "SchemaCompat: rime.lua failed") }
        runCatching { SchemaCompat.ensureForEnabledSchemas() }
            .onFailure { Timber.e(it, "SchemaCompat failed before deploy") }
        // 部署日誌（覆蓋寫 `<userDataDir>/logs/hkrime-deploy.log`）：librime 的
        // `collecting entries from ...` / `rebuilding pack ...` / `Encode failure` 只在 logcat，
        // 用戶查「改了詞典為什麼不生效」時拿不到；這裡把這次部署的 logcat 整段落盤。
        val session = DeployLog.begin(context, full)
        var failure: Throwable? = null
        try {
            val t0 = System.nanoTime()
            Rime.destroy()
            val t1 = System.nanoTime()
            Rime.get(context, full)
            val t2 = System.nanoTime()
            Timber.i(
                "deploy: full=%b destroy=%dms reinit=%dms total=%dms",
                full, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t2 - t0) / 1_000_000,
            )
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            DeployLog.end(context, session, failure)
            Timber.i("deploy log: %s", session.file?.path ?: "(unavailable)")
        }
        ToastUtils.showLong(R.string.deploy_finish)
    }

    /**
     * 同步用戶數據（librime 的 `sync_user_data`）。
     *
     * **注意時序**（用戶 2026-11-11 報「設置裡點同步，hkrime 裡沒有 sync 文件夾」）：
     * `RimeSyncUserData()` 只是「排任務（installation_update / backup_config_files /
     * user_dict_sync）+ 啟動 maintenance 線程」，真正的寫盤在後台線程裡。
     * 舊實現排完任務立刻 `destroy()`，維護線程還沒跑就被拆掉 → `sync/` 永遠不會出現。
     *
     * 所以：先確保 librime 按 App 的目錄初始化（設置界面可能是冷啟動進程），
     * 排任務後 `join_maintenance_thread()` 等它跑完，再 destroy + 全量部署。
     *
     * @return `true` 表示同步任務已排隊並執行完成（librime 的語義）
     */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        Rime.get(context, false)          // 冷啟動時先按 App 的 shared/user dir 初始化
        val scheduled = Rime.sync_user_data()
        val syncDir = runCatching { Rime.get_sync_dir() }.getOrNull()
        Timber.i("sync: scheduled=%b syncDir=%s", scheduled, syncDir ?: "(null)")
        // 無論 scheduled 真假都 join：可能是別的 maintenance 正在跑，等它收尾；
        // 沒有線程時 join 是 no-op。
        Rime.join_maintenance_thread()
        val files = syncDir?.let { dir ->
            runCatching {
                File(dir).walkTopDown().filter { it.isFile }.take(20).map { it.name }.toList()
            }.getOrNull()
        } ?: emptyList()
        Timber.i(
            "sync: finished dir=%s exists=%b files(%d)=%s",
            syncDir ?: "(null)", syncDir?.let { File(it).isDirectory } == true, files.size, files,
        )
        Rime.destroy()
        Rime.get(context, true)
        scheduled
    }
}
