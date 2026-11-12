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
     * 重新初始化並部署 Rime，默認 full=false 增量檢測變更；full=true 強制完整重建。
     * 在 IO 線程執行，記錄部署前後狀態與本次 glog，完成後通知界面。
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
     * 初始化 Rime 後排本地同步任務，等待維護寫盤完成再銷毀並重新初始化。
     * 只在 IO 線程 join；同步導出不代表上傳至網絡。
     * @return 同步任務是否成功排隊執行
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
