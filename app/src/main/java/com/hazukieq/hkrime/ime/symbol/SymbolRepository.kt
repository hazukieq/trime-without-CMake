package com.hazukieq.hkrime.ime.symbol

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.InputStreamReader

/**
 * Plain-text (`.txt`) symbol & emoji dictionary loader.
 *
 * Users can expand existing categories or add new category tabs simply by editing or
 * dropping `.txt` files into `<userDataDir>/symbols/` (e.g. `/sdcard/hkrime/symbols/`),
 * or managing tab order / visibility / custom tabs in Settings.
 */
object SymbolRepository {

    const val ASSET_DIR = "rime/symbols"
    const val USER_SUBDIR = "symbols"

    data class SymbolItem(
        val text: String,
        val badge: String? = null,
    )

    data class SymbolCategory(
        val id: String,
        val title: String,
        val sortKey: String,
        val items: List<SymbolItem>,
        val fileName: String = "",
        val isBundled: Boolean = false,
    )

    private val asciiBadgeCandidates = setOf(
        ",", ".", "?", "!", ":", ";", "'", "\"", "(", ")", "[", "]", "{", "}", "<", ">",
        "#", "*", "-", "_", "~", "/", "\\", "|", "+", "=", "@", "$", "%", "^", "&", "`",
    )

    private val leadingOrderPrefix = Regex("^\\d+[._\\-\\s]+")

    /**
     * Parses a single `.txt` symbol file into a [SymbolCategory].
     */
    fun parseTxt(
        fileName: String,
        content: String,
        isBundled: Boolean = false,
    ): SymbolCategory {
        val stem = fileName.removeSuffix(".txt").removeSuffix(".TXT")
        val title = stem.replaceFirst(leadingOrderPrefix, "").ifEmpty { stem }

        var splitByLine = false
        var explicitBadge: String? = null
        val items = ArrayList<SymbolItem>()

        for (rawLine in content.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val directive = trimmed.removePrefix("#").trim()
                when {
                    directive.equals("split: line", ignoreCase = true) ||
                        directive.equals("split:line", ignoreCase = true) -> splitByLine = true
                    directive.startsWith("badge:", ignoreCase = true) -> {
                        explicitBadge = directive.substringAfter(":").trim().ifEmpty { null }
                    }
                }
                continue
            }
            if (splitByLine) {
                items.add(SymbolItem(trimmed, explicitBadge))
            } else {
                for (token in trimmed.split(Regex("\\s+"))) {
                    if (token.isEmpty()) continue
                    val badge = explicitBadge ?: inferBadge(title, token)
                    items.add(SymbolItem(token, badge))
                }
            }
        }

        return SymbolCategory(
            id = title,
            title = title,
            sortKey = stem,
            items = items,
            fileName = fileName,
            isBundled = isBundled,
        )
    }

    private fun inferBadge(categoryTitle: String, token: String): String? {
        if (categoryTitle == "常用" && token in setOf(",", ":", "*", "#", ";", "?", "!")) {
            return "英"
        }
        if (categoryTitle == "英文" && token in asciiBadgeCandidates) {
            return "英"
        }
        return null
    }

    /**
     * Copies bundled `assets/rime/symbols/` `.txt` files into `<userDataDir>/symbols/` if not yet
     * present, never overwriting user-modified files.
     */
    fun ensureSeeded(context: Context, userDataDir: String) {
        runCatching {
            val targetDir = File(userDataDir, USER_SUBDIR)
            if (!targetDir.exists()) targetDir.mkdirs()
            val assetFiles = context.assets.list(ASSET_DIR) ?: return
            for (name in assetFiles) {
                if (!name.endsWith(".txt", ignoreCase = true)) continue
                val dst = File(targetDir, name)
                if (dst.exists()) continue
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }.onFailure { Timber.w(it, "SymbolRepository: seed symbols dir failed") }
    }

    /**
     * Applies user-configured tab [order] and [hidden] set to [categories].
     * When [includeHidden] is false and every category is hidden, falls back to the first category.
     */
    fun applyOrderAndFilter(
        categories: List<SymbolCategory>,
        order: List<String>,
        hidden: Set<String>,
        includeHidden: Boolean = false,
    ): List<SymbolCategory> {
        if (categories.isEmpty()) return emptyList()
        val byTitle = categories.associateBy { it.title }
        val ordered = ArrayList<SymbolCategory>(categories.size)
        val seen = HashSet<String>(categories.size)

        for (title in order) {
            val cat = byTitle[title] ?: continue
            if (seen.add(cat.title)) ordered.add(cat)
        }
        for (cat in categories) {
            if (seen.add(cat.title)) ordered.add(cat)
        }

        if (includeHidden) return ordered
        val visible = ordered.filterNot { it.title in hidden }
        return visible.ifEmpty { listOf(ordered.first()) }
    }

    /**
     * Loads all symbol categories, preferring user `.txt` files in `<userDataDir>/symbols/`
     * and falling back to bundled `assets/rime/symbols/` `.txt` files.
     */
    fun loadCategories(
        context: Context,
        userDataDir: String,
        order: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
        includeHidden: Boolean = false,
    ): List<SymbolCategory> {
        ensureSeeded(context, userDataDir)
        val byTitle = LinkedHashMap<String, SymbolCategory>()
        val bundledTitles = HashSet<String>()

        // 1. Load bundled defaults first
        runCatching {
            val assetFiles = context.assets.list(ASSET_DIR)?.sorted() ?: emptyList()
            for (name in assetFiles) {
                if (!name.endsWith(".txt", ignoreCase = true)) continue
                val text = context.assets.open("$ASSET_DIR/$name").use {
                    InputStreamReader(it, Charsets.UTF_8).readText()
                }
                val cat = parseTxt(name, text, isBundled = true)
                if (cat.items.isNotEmpty()) {
                    byTitle[cat.title] = cat
                    bundledTitles.add(cat.title)
                }
            }
        }.onFailure { Timber.w(it, "SymbolRepository: load assets failed") }

        // 2. Overlay & extend with user directory `.txt` files
        runCatching {
            val userDir = File(userDataDir, USER_SUBDIR)
            if (userDir.isDirectory) {
                val files = userDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".txt", ignoreCase = true)
                }?.sortedBy { it.name } ?: emptyList()
                for (file in files) {
                    val text = file.readText(Charsets.UTF_8)
                    val parsed = parseTxt(file.name, text)
                    if (parsed.items.isNotEmpty()) {
                        byTitle[parsed.title] = parsed.copy(
                            isBundled = parsed.title in bundledTitles,
                        )
                    }
                }
            }
        }.onFailure { Timber.w(it, "SymbolRepository: load user dir failed") }

        val sorted = byTitle.values.sortedBy { it.sortKey }
        return applyOrderAndFilter(sorted, order, hidden, includeHidden)
    }

    /**
     * Creates or updates a custom `.txt` category file under `<userDataDir>/symbols/`.
     */
    fun createCustomCategory(
        userDataDir: String,
        rawTitle: String,
        rawContent: String,
    ): SymbolCategory? {
        val cleanTitle = rawTitle.trim().replace(Regex("[/\\\\:*?\"<>|]"), "")
        if (cleanTitle.isEmpty()) return null
        val content = rawContent.trim()
        if (content.isEmpty()) return null

        val fileName = "$cleanTitle.txt"
        val parsed = parseTxt(fileName, content, isBundled = false)
        if (parsed.items.isEmpty()) return null

        return runCatching {
            val dir = File(userDataDir, USER_SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText("# 自定义分类：$cleanTitle\n$content\n", Charsets.UTF_8)
            parsed
        }.onFailure { Timber.w(it, "SymbolRepository: create custom category failed") }.getOrNull()
    }

    /**
     * Deletes a custom (non-bundled) `.txt` category file from `<userDataDir>/symbols/`.
     */
    fun deleteCustomCategory(userDataDir: String, fileName: String): Boolean {
        if (fileName.isBlank()) return false
        return runCatching {
            val target = File(File(userDataDir, USER_SUBDIR), fileName)
            target.exists() && target.delete()
        }.getOrDefault(false)
    }
}
