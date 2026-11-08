package com.hazukieq.hkrime.util

import android.content.Context
import com.blankj.utilcode.util.ToastUtils
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.core.Rime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        runCatching { SchemaCompat.ensureForEnabledSchemas() }
            .onFailure { Timber.e(it, "SchemaCompat failed before deploy") }
        val t0 = System.nanoTime()
        Rime.destroy()
        val t1 = System.nanoTime()
        Rime.get(context, full)
        val t2 = System.nanoTime()
        Timber.i(
            "deploy: full=%b destroy=%dms reinit=%dms total=%dms",
            full, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t2 - t0) / 1_000_000,
        )
        ToastUtils.showLong(R.string.deploy_finish)
    }

    /** Sync the user data.
     * @return `true` if successfully **/
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        Rime.sync_user_data().also {
            Rime.destroy()
            Rime.get(context, true)
        }
    }
}
