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

    /** Deploy means reset Rime instance. **/
    suspend fun deploy(context: Context) = withContext(Dispatchers.IO) {
        // 全字母方案（客語 26 鍵、朙月拼音…）先補 App 適配 patch，再部署讓 patch 生效。
        // 見 SchemaCompat：面板左列的音節分組靠候選 comment = 精確拼寫。
        runCatching { SchemaCompat.ensureForEnabledSchemas() }
            .onFailure { Timber.e(it, "SchemaCompat failed before deploy") }
        Rime.destroy()
        Rime.get(context, true)
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
