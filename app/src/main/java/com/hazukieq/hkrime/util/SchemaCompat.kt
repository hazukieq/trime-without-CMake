package com.hazukieq.hkrime.util

import android.content.Context
import com.hazukieq.hkrime.data.AppPrefs
import java.io.File
import timber.log.Timber

/**
 * 全字母方案（客語 26 鍵、朙月拼音…等所有非 12 鍵方案）的「App 適配 patch」。
 *
 * 為什麼需要：展開面板左列的音節索引是靠候選的 comment 分組的
 * （TwelveKeySnapshotBuilder 按 comment 分組）。而 librime 的 script_translator
 * 默認只在「詞典拼寫 ≠ 用戶輸入的 preedit」時才把拼寫寫進 candidate.comment，
 * 全字母方案常常原樣敲出拼寫（如 ni hau），comment 為空 → 左列只剩一個「…」分組。
 *
 * 適配內容（寫進 user_data_dir/<schema_id>.custom.yaml 的 patch，不動方案原文件）：
 *   translator/spelling_hints: 100      # comment = 詞典精確拼寫（100 = 全量）
 *   translator/always_show_comments     # 一律填 comment（不看 preedit 是否等於拼寫）
 *
 * 只在部署前跑（RimeUtils.deploy）：custom.yaml 的 patch 由 librime 在部署時合併，
 * 所以寫完必須走一次部署才生效；「啟用 / 停用方案…」這條路徑本來就會部署。
 */
object SchemaCompat {

    /** 12 鍵方案：它們本來就有 spelling_hints/always_show_comments，不需要（也不該）被改寫。 */
    private val TWELVE_KEY_SCHEMAS = setOf("hakka_jiugong", "hakka_giugiung", "hakka_tung")

    private const val RIME_LUA = "rime.lua"

    private val SCHEMA_ITEM = Regex("""schema:\s*[-A-Za-z0-9_.]+""")
    private val PATCH_LINE = Regex("""(?m)^patch:\s*$""")

    private const val HEADER =
        "# 由 hkrime 自動維護：全字母方案適配（展開面板左列分組需要候選 comment = 精確拼寫）\n"

    private const val PATCH_BLOCK =
        "  \"translator/spelling_hints\": 100\n" +
            "  \"translator/always_show_comments\": true\n" +
            "  \"translator/keep_comments\": true"

    /**
     * 確保 user_data_dir 裡有 rime.lua。
     *
     * librime-lua 的 `lua_translator@*xxx` / `lua_filter@*xxx` 是去全局環境找同名變量，
     * 而 rime-ice 這類方案依賴 rime.lua 把 lua/ 下的模塊 require 出來掛到全局；
     * 沒有這個前置文件時只是 Lua 增強（日期/農曆/UUID/糾錯…）靜默失效。
     * 這裡在部署前從 assets 補一份（已存在則不動，留給用戶自定義）。
     *
     * @return true = 本次從 assets 補了 rime.lua
     */
    fun ensureRimeLua(context: Context): Boolean {
        val userDir = runCatching { File(AppPrefs.defaultInstance().conf.userDataDir) }
            .getOrNull() ?: return false
        val target = File(userDir, RIME_LUA)
        if (target.isFile && target.length() > 0L) return false
        return runCatching {
            target.parentFile?.mkdirs()
            context.assets.open("rime/$RIME_LUA").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Timber.i("SchemaCompat: installed %s from assets", target.absolutePath)
            true
        }.onFailure { Timber.e(it, "SchemaCompat: install rime.lua failed") }.getOrDefault(false)
    }

    /**
     * 給當前 schema_list 裡所有「全字母」方案補上適配 patch。
     * @return 本次實際寫入的方案數（0 = 都已就緒）
     */
    fun ensureForEnabledSchemas(): Int {
        val userDir = runCatching { File(AppPrefs.defaultInstance().conf.userDataDir) }
            .getOrNull() ?: return 0
        val schemaIds = runCatching { readSchemaList(File(userDir, "default.custom.yaml")) }
            .getOrElse { emptyList() }
        if (schemaIds.isEmpty()) return 0

        var changed = 0
        for (id in schemaIds) {
            if (id in TWELVE_KEY_SCHEMAS) continue
            val patched = runCatching { ensurePatched(File(userDir, "$id.custom.yaml")) }
                .onFailure { Timber.e(it, "SchemaCompat: patch %s failed", id) }
                .getOrDefault(false)
            if (patched) {
                changed++
                Timber.i("SchemaCompat: patched app-adaptation for %s", id)
            }
        }
        return changed
    }

    /** 從 default.custom.yaml 的 schema_list 取已啟用方案 id（保持原始順序）。 */
    private fun readSchemaList(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val ids = ArrayList<String>()
        var inList = false
        file.readLines(Charsets.UTF_8).forEach { rawLine ->
            val line = rawLine.trim()
            when {
                !inList -> if (line == "schema_list:") inList = true
                line.startsWith("-") -> SCHEMA_ITEM.find(line)?.let { match ->
                    ids.add(match.value.substringAfter(':').trim())
                }
                line.isNotEmpty() && !line.startsWith("#") -> inList = false
            }
        }
        return ids
    }

    /**
     * @return true 表示補建或更新了方案適配 patch。
     */
    private fun ensurePatched(file: File): Boolean {
        val text = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (text.contains("always_show_comments")) {
            // 補 translator/keep_comments，避免 Lua corrector 清空面板需要的精確讀音。
            if (text.contains("keep_comments")) return false
            val anchor = "\"translator/always_show_comments\""
            val at = text.indexOf(anchor)
            val lineEnd = if (at >= 0) text.indexOf('\n', at).let { if (it < 0) text.length else it } else text.length
            val out = text.substring(0, lineEnd) +
                "\n  \"translator/keep_comments\": true" + text.substring(lineEnd)
            file.writeText(out, Charsets.UTF_8)
            return true
        }

        val out = when {
            text.isBlank() -> HEADER + "patch:\n" + PATCH_BLOCK + "\n"
            PATCH_LINE.containsMatchIn(text) -> {
                val match = PATCH_LINE.find(text)!!
                val insertAt = match.range.last + 1
                text.substring(0, insertAt) + "\n" + PATCH_BLOCK + text.substring(insertAt)
            }
            else -> text.trimEnd() + "\n\npatch:\n" + PATCH_BLOCK + "\n"
        }
        file.parentFile?.mkdirs()
        file.writeText(out, Charsets.UTF_8)
        return true
    }
}
