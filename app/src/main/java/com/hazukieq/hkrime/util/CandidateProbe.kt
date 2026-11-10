package com.hazukieq.hkrime.util

import com.hazukieq.hkrime.core.Rime
import java.io.File
import timber.log.Timber

/**
 * 引擎候選探針（**僅 debug**）：排查「某個詞在詞典裡、引擎卻給不出來」。
 *
 * 為什麼要這個：App 側只能看到候選條拿到的東西，分不清是「引擎根本沒查到」、
 * 還是「查到了被過濾器（simplifier/uniquifier…）濾掉」。這個探針直接問引擎要
 * 指定碼串的全量候選，把原文寫盤，避免再靠人肉猜。
 *
 * 用法（不需要打開界面，也不需要 IME 進程活著）：
 * ```bash
 * adb shell "echo '5i4a57i' > /sdcard/hkrime/dbg_candidates.txt"
 * # 之後任意一次彈出輸入法（點任何輸入框）就會觸發
 * adb shell cat /sdcard/hkrime/dbg_candidates.log
 * ```
 * 觸發後請求文件會改名成 `dbg_candidates.txt.done`，避免每次彈鍵盤都跑一遍。
 */
object CandidateProbe {

    private const val DIR = "/sdcard/hkrime"
    private const val REQUEST = "$DIR/dbg_candidates.txt"
    private const val DONE = "$REQUEST.done"
    private const val RESULT = "$DIR/dbg_candidates.log"

    fun runIfRequested(context: android.content.Context) {
        val request = File(REQUEST)
        if (!request.isFile) return
        val codes = runCatching {
            request.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrNull() ?: run {
            request.renameTo(File(DONE))
            return
        }
        if (codes.isEmpty()) {
            request.renameTo(File(DONE))
            return
        }
        val startedAt = System.currentTimeMillis()
        val out = StringBuilder()
        out.append("=== candidate probe @ ${java.util.Date()} ===\n")
        runCatching { Rime.get(context, false) }  // 設置進程等場景下引擎可能還沒初始化
        // 指令行：`!deploy` = 先做一次增量部署（重建改過的詞典表），再跑探針。
        // 省得為了驗證「改詞庫/加權重」還得開界面點部署。
        if (codes.any { it.equals("!deploy", ignoreCase = true) }) {
            out.append("deploy: start\n")
            runCatching {
                Rime.destroy()
                Rime.get(context, false)
                // 部署是異步維護線程：等它真的做完，否則後面探針問到的是舊表
                val deadline = System.currentTimeMillis() + 120_000
                while (Rime.is_maintenance_mode() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                Rime.join_maintenance_thread()
            }.onFailure { out.append("deploy ERROR: ").append(it).append('\n') }
            out.append("deploy: done\n")
        }
        // `!schema hakka_jiugong` = 先切方案再探（默認可能是 26 鍵 hakka_qwerty）
        codes.filter { it.startsWith("!schema ") }.forEach { line ->
            val id = line.removePrefix("!schema ").trim()
            val switched = runCatching {
                var ok = false
                for (i in 0 until 32) {
                    if (Rime.getSchemaId() == id) { ok = true; break }
                    if (!Rime.selectSchema(i)) continue
                    Thread.sleep(400)
                }
                ok
            }.getOrElse { "ERROR: $it" }
            out.append("schema: ").append(id).append(" -> ").append(switched)
                .append(" current=").append(runCatching { Rime.getSchemaId() }.getOrNull())
                .append('\n')
            Thread.sleep(1200)
        }
        // `!feed 5i4a57i` = 逐字餵鍵，印每個字符 onText 的返回值與當前 input（查「輸入被截斷」）
        codes.filter { it.startsWith("!feed ") }.forEach { line ->
            val text = line.removePrefix("!feed ").trim()
            out.append("--- feed probe: ").append(text).append(" ---\n")
            runCatching {
                Rime.clearComposition()
                for ((i, ch) in text.withIndex()) {
                    val ok = Rime.onText(ch.toString())
                    out.append("feed[").append(i).append("]='").append(ch)
                        .append("' ok=").append(ok)
                        .append(" input=").append(runCatching { Rime.RimeGetInput() }.getOrElse { "?" })
                        .append('\n')
                    // 模擬真實輸入：每鍵都取一次候選（真機鍵盤就是這麼刷新菜單的）
                    runCatching { Rime.getCandidatesWithoutSwitch() }
                }
                val all = runCatching { Rime.enumerateCandidates(2000) }.getOrElse { emptyArray() }
                out.append("feed candidates=").append(all.size)
                    .append(" hasLinux=").append(all.any { it.contains("Linux") })
                    .append('\n')
                all.take(8).forEachIndexed { i, c -> out.append("  #").append(i).append(' ').append(c).append('\n') }
            }.onFailure { out.append("feed ERROR: ").append(it).append('\n') }
        }
        // `@li nak si` = 直接問詞典（不走輸入法譯碼路徑），看詞條到底在不在表裡
        codes.filter { it.startsWith("@") }.forEach { line ->
            val spaced = line.removePrefix("@").trim()
            out.append("--- dict probe: ").append(spaced).append(" ---\n")
            out.append(runCatching { Rime.probe_dict(spaced) }.getOrElse { "probe ERROR: $it" })
            out.append('\n')
        }
        codes.filterNot { it.startsWith("!") || it.startsWith("@") }.forEach { code ->
        out.append("request=").append(code).append('\n')
        runCatching {
            Rime.clearComposition()
            val fed = Rime.onText(code)
            val composing = Rime.getComposition()
            // 全量枚舉（RimeCandidateListNext 惰性 Prepare，不受 page_size 限制），
            // 返回扁平 [text, comment, text, comment…]；之前用 getCandidatesWithoutSwitch()
            // 只拿到第一頁 9 條，把真正的詞擋在視野外了（2026-11-11）。
            val flat = Rime.enumerateCandidates(2000)
            val cands = (flat.indices step 2).map {
                Rime.RimeCandidate().also { c ->
                    c.text = flat[it]
                    c.comment = flat.getOrNull(it + 1) ?: ""
                }
            }
            out.append("feedOk=").append(fed).append('\n')
            out.append("composing=").append(composing).append('\n')
            out.append("engineCandidates=").append(cands.size).append('\n')
            cands.forEachIndexed { i, c ->
                out.append("  #").append(i).append(' ').append(c.text)
                if (!c.comment.isNullOrEmpty()) out.append("  ·/· ").append(c.comment)
                out.append('\n')
            }
            Rime.clearComposition()
            // App 側解析結果（詞表/聯想詞/字表）也一起寫進來，方便對照
            val syl = runCatching { com.hazukieq.hkrime.core.TwelveKeyPreedit.decode(code) }.getOrNull()
            out.append("appDecode=").append(syl).append('\n')
        }.onFailure {
            out.append("ERROR: ").append(it).append('\n')
            Timber.w(it, "CandidateProbe: probe failed")
        }
        }
        // 把 librime 這段時間的日誌一起抄進來（部署時的 dropping/collecting/Pass 1 都在裡面）
        runCatching {
            val lines = Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "-v", "time", "-t", "4000"))
                .inputStream.bufferedReader().use { it.readLines() }
            val interesting = lines.filter { l ->
                l.contains("dropping entry", true) || l.contains("collecting entries", true) ||
                    l.contains("Pass 1", true) || l.contains("Pass 2", true) ||
                    l.contains("pack", true) || l.contains("hakka_zon", true) ||
                    l.contains("invalid syllable", true) || l.contains("dictionary", true)
            }.takeLast(120)
            out.append("--- rime logcat (").append(interesting.size).append(" lines) ---\n")
            interesting.forEach { out.append(it).append('\n') }
        }.onFailure { out.append("logcat ERROR: ").append(it).append('\n') }
        out.append("elapsed=").append(System.currentTimeMillis() - startedAt).append("ms\n")
        runCatching { File(RESULT).writeText(out.toString()) }
            .onFailure { Timber.w(it, "CandidateProbe: write result failed") }
        request.renameTo(File(DONE))
        Timber.i("CandidateProbe: done -> %s", RESULT)
    }
}
