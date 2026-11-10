package com.hazukieq.hkrime.core

import android.content.Context
import com.hazukieq.hkrime.data.AppPrefs
import java.io.File
import timber.log.Timber

/**
 * 12 鍵 App 側那些「字典表」的統一讀取入口。
 *
 * 背景（用戶 2026-11-09 在 dsh mobile 反饋「添加新條目到詞庫不生效」，2026-11-11 落 PC 修）：
 * `TwelveKeyPreedit` 以前只用 `context.assets.open("rime/hakka.dict.yaml")` 讀 **APK 內置**
 * 的那份字典 —— 既不讀共享目錄 `/sdcard/hkrime`，也不展開 YAML 頭裡的 `import_tables`。
 * 於是「磁盤上編譯進 `hakka_zon` 的新詞」對引擎（librime 直接讀共享目錄）可見，
 * 對 12 鍵的段組字表 / 聯想詞 / 字頻排序卻完全不可見。
 *
 * 現在統一改成：
 * 1. **共享目錄優先**：`<userDataDir>/<name>.dict.yaml` 存在就用它，否則才退回 APK 內置
 *    `assets/rime/<name>.dict.yaml`（發佈版不帶用戶改動時行為不變）；
 * 2. **自己展開 `import_tables`**：支持 block（`- name`）與 inline（`[a, b]`）兩種寫法，
 *    遞歸上限 [MAX_IMPORT_DEPTH] 並按名字去重，所以「新建一個詞表掛進 import_tables」
 *    不改代碼也能被 App 看到；
 * 3. 只取**字典本體**（跳過 `---` 頭與 `...` 終結符之前的 YAML 元數據），
 *    避免把 `version:`/`name:` 這類行餵給字表解析。
 *
 * 生效時機：字典在輸入法進程啟動時讀入（`TwelveKeyPreedit.init`），改完 yaml 需要
 * **重開輸入法（或重啟 App）** 才生效——組裝/編譯（deploy）本身不會通知 App 重讀。
 */
object RimeDictSource {

    private const val DICT_SUFFIX = ".dict.yaml"
    private const val MAX_IMPORT_DEPTH = 4

    /** 用戶數據目錄（默認 `/sdcard/hkrime`），拿不到就返回 null（例如 prefs 還沒初始化）。 */
    fun userDir(): File? =
        runCatching { File(AppPrefs.defaultInstance().conf.userDataDir) }.getOrNull()

    /** 共享目錄裡的這個詞典文件（不存在返回 null）。 */
    fun dictFile(name: String): File? {
        val base = normalizeName(name)
        return userDir()?.resolve("$base$DICT_SUFFIX")?.takeIf { it.isFile }
    }

    /** 讀原始行（含 YAML 頭），共享目錄優先、assets 兜底；兩邊都沒有返回 null。 */
    fun rawLines(context: Context, name: String): List<String>? {
        val base = normalizeName(name)
        dictFile(base)?.let { file ->
            runCatching { file.readLines(Charsets.UTF_8) }.getOrNull()?.let { return it }
        }
        return runCatching {
            context.assets.open("rime/$base$DICT_SUFFIX")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
        }.getOrNull()
    }

    /** 只讀字典本體（跳過 YAML 頭），這是字表/詞表解析真正要的部分。 */
    fun body(context: Context, name: String): List<String> {
        val lines = rawLines(context, name) ?: return emptyList()
        return lines.subList(bodyStart(lines), lines.size)
    }

    /**
     * `name` 自己 + 它 `import_tables` 展開出的所有詞典名（先導入、後本體）。
     *
     * 只返回「確實存在」的表：共享目錄或 assets 任一處能讀到。順序穩定、按名字去重。
     */
    fun namesWithImports(context: Context, name: String): List<String> {
        val out = LinkedHashSet<String>()
        collectNames(context, name, 0, HashSet(), out)
        return out.toList()
    }

    private fun collectNames(
        context: Context,
        name: String,
        depth: Int,
        visited: MutableSet<String>,
        out: MutableSet<String>,
    ) {
        val base = normalizeName(name)
        if (depth > MAX_IMPORT_DEPTH || !visited.add(base)) return
        // 只讀到 YAML 頭結束（`...`）為止：展開 import_tables 不需要詞典本體那幾萬行，
        // 否則 8 個詞表會被「解析頭部」和「讀本體」各讀一遍（2MB×2 的 IO 開銷）。
        val head = readHead(context, base) ?: return
        parseImports(head).forEach { collectNames(context, it, depth + 1, visited, out) }
        out.add(base)
    }

    /**
     * 只讀 YAML 頭（到 `...` 為止，最多 [MAX_HEAD_LINES] 行），用來展開 `import_tables`。
     *
     * 用戶詞典本體可能有幾萬行，展開導入表時沒必要全讀。
     */
    private fun readHead(context: Context, name: String): List<String>? {
        val base = normalizeName(name)
        val reader = dictFile(base)?.let { file ->
            runCatching { file.bufferedReader(Charsets.UTF_8) }.getOrNull()
        } ?: runCatching {
            context.assets.open("rime/$base$DICT_SUFFIX").bufferedReader(Charsets.UTF_8)
        }.getOrNull() ?: return null
        return runCatching {
            reader.use { r ->
                val out = ArrayList<String>(32)
                var line = r.readLine()
                while (line != null && out.size < MAX_HEAD_LINES) {
                    out.add(line)
                    if (line.trim() == "...") break
                    line = r.readLine()
                }
                out
            }
        }.getOrNull()
    }

    private const val MAX_HEAD_LINES = 200

    /** 共享目錄優先讀一個**輔助文本文件**（`hakka_char_freq.txt` 這類），返回行列表。 */
    fun textLines(context: Context, fileName: String): List<String>? {
        userDir()?.resolve(fileName)?.takeIf { it.isFile }?.let { file ->
            runCatching { file.readLines(Charsets.UTF_8) }.getOrNull()?.let { return it }
        }
        return runCatching {
            context.assets.open("rime/$fileName").bufferedReader(Charsets.UTF_8).readLines()
        }.getOrNull()
    }

    /** `hakka_zon` / `hakka_zon.dict.yaml` 都歸一到 `hakka_zon`（import_tables 兩種寫法都有）。 */
    internal fun normalizeName(name: String): String =
        File(name.trim().trim('"', '\'')).name.removeSuffix(DICT_SUFFIX)

    /** 字典本體的起始行號：YAML 頭以單獨一行 `...` 結束；沒有 `...` 時視為整份都是本體。 */
    internal fun bodyStart(lines: List<String>): Int {
        val end = lines.indexOfFirst { it.trim() == "..." }
        return if (end < 0) 0 else end + 1
    }

    /**
     * 從 YAML 頭裡取 `import_tables`（兩條寫法都認）：
     *
     * ```yaml
     * import_tables:   # 导入拓展词典
     *     - hakka_places
     *     - hakka_zon
     * ```
     * ```yaml
     * import_tables: [hakka_places, hakka_zon]
     * ```
     *
     * 只在 `...` 之前找，避免字典本體裡的詞條誤匹配；`#` 之後當註釋剝掉。
     */
    internal fun parseImports(lines: List<String>): List<String> {
        val limit = bodyStart(lines).let { if (it == 0) lines.size else it }
        val out = ArrayList<String>()
        var i = 0
        while (i < limit) {
            val line = lines[i].substringBefore('#').trim()
            if (!line.startsWith("import_tables")) {
                i++
                continue
            }
            val inline = line.substringAfter(':', "").trim()
            if (inline.isNotEmpty()) {
                // inline：`[a, b]` 或 `a, b`
                inline.trim('[', ']', ' ')
                    .split(',')
                    .map { normalizeName(it) }
                    .filter { it.isNotEmpty() }
                    .forEach { out.add(it) }
                i++
                continue
            }
            // block：後續以 `- ` 開頭的行
            i++
            while (i < limit) {
                val item = lines[i].substringBefore('#').trim()
                if (item.isEmpty()) {
                    i++
                    continue
                }
                if (!item.startsWith("-")) break
                val name = normalizeName(item.removePrefix("-").trim())
                if (name.isNotEmpty()) out.add(name)
                i++
            }
        }
        return out.distinct()
    }

    /** 診斷用：這次到底讀的是共享目錄還是內置 assets（寫進日誌方便排查「改了為啥沒生效」）。 */
    fun describe(context: Context, name: String): String {
        val base = normalizeName(name)
        dictFile(base)?.let { return "file:${it.path}" }
        return "assets:rime/$base$DICT_SUFFIX"
    }

    /** 一次性把「用的是哪份字典」記進日誌（每個進程只記一次，避免刷屏）。 */
    fun logSourceOnce(context: Context, mainName: String) {
        Timber.i(
            "RimeDictSource: main=%s imports=%s",
            describe(context, mainName),
            namesWithImports(context, mainName).joinToString(","),
        )
    }
}
