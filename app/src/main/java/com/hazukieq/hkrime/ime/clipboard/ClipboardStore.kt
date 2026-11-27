package com.hazukieq.hkrime.ime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.File

/**
 * 剪切板歷史存儲（純本地 JSON 持久化 + 系統剪切板實時監聽）：
 * - 置頂條目始終排在最前且不會被容量上限淘汰；
 * - 普通條目按時間倒序最多保留 [MAX_UNPINNED] 條；
 * - 重複複製同一段文字會將原條目提升到頂部並保留其置頂狀態。
 */
class ClipboardStore(
    private val storageFile: File?,
    private val maxUnpinned: Int = MAX_UNPINNED,
) {
    data class Entry(
        val id: Long,
        val text: String,
        val pinned: Boolean,
        val timestamp: Long,
    )

    private val lock = Any()
    private val items = ArrayList<Entry>()
    private var nextId: Long = 1L
    private var lastSystemText: String? = null
    private var lastSystemTimestamp: Long = Long.MIN_VALUE

    var onChanged: (() -> Unit)? = null

    init {
        loadFromDisk()
    }

    fun entries(): List<Entry> = synchronized(lock) {
        items.sortedWith(
            compareByDescending<Entry> { it.pinned }
                .thenByDescending { it.timestamp }
                .thenByDescending { it.id },
        )
    }

    fun hasUnpinned(): Boolean = synchronized(lock) {
        items.any { !it.pinned }
    }

    /** 記錄一條剪切板文本；若文本為空白則忽略。返回是否產生了實際變化。 */
    fun record(rawText: CharSequence?, now: Long = System.currentTimeMillis()): Boolean {
        val text = rawText?.toString() ?: return false
        if (text.isBlank()) return false
        val changed = synchronized(lock) {
            val existingIdx = items.indexOfFirst { it.text == text }
            if (existingIdx >= 0) {
                val old = items.removeAt(existingIdx)
                items.add(0, old.copy(timestamp = now))
            } else {
                items.add(0, Entry(id = nextId++, text = text, pinned = false, timestamp = now))
                trimUnpinnedLocked()
            }
            saveToDiskLocked()
            true
        }
        if (changed) onChanged?.invoke()
        return changed
    }

    fun togglePin(id: Long): Boolean {
        val changed = synchronized(lock) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val old = items[idx]
            items[idx] = old.copy(pinned = !old.pinned)
            trimUnpinnedLocked()
            saveToDiskLocked()
            true
        }
        if (changed) onChanged?.invoke()
        return changed
    }

    fun delete(id: Long, context: Context? = null): Boolean {
        var deletedText: String? = null
        val changed = synchronized(lock) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx < 0) return false
            deletedText = items.removeAt(idx).text
            saveToDiskLocked()
            true
        }
        if (changed) {
            if (context != null && deletedText != null && deletedText == lastSystemText) {
                clearSystemClipboardIfMatches(context, deletedText!!)
            }
            onChanged?.invoke()
        }
        return changed
    }

    fun clearUnpinned(context: Context? = null): Boolean {
        var clearedTexts: Set<String> = emptySet()
        val changed = synchronized(lock) {
            val toRemove = items.filter { !it.pinned }
            if (toRemove.isEmpty()) return false
            clearedTexts = toRemove.map { it.text }.toSet()
            items.removeAll { !it.pinned }
            saveToDiskLocked()
            true
        }
        if (changed) {
            val sysText = lastSystemText
            if (context != null && sysText != null && sysText in clearedTexts) {
                clearSystemClipboardIfMatches(context, sysText)
            }
            onChanged?.invoke()
        }
        return changed
    }

    /**
     * 從系統 ClipboardManager 同步當前主剪切板內容：
     * 僅當系統剪切板文本或系統時間戳發生變化時才錄入，避免已被用戶刪除/清空的條目在重新打開面板時復活。
     */
    fun syncFromSystem(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            if (text.isBlank()) return
            val clipTs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clip.description?.timestamp ?: 0L
            } else {
                0L
            }
            val shouldRecord = synchronized(lock) {
                if (text == lastSystemText && clipTs == lastSystemTimestamp) {
                    false
                } else {
                    lastSystemText = text
                    lastSystemTimestamp = clipTs
                    true
                }
            }
            if (shouldRecord) {
                record(text)
            }
        }.onFailure {
            Timber.w(it, "ClipboardStore: syncFromSystem failed")
        }
    }

    private fun clearSystemClipboardIfMatches(context: Context, targetText: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val current = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            if (current != targetText) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }.onFailure {
            Timber.w(it, "ClipboardStore: clearSystemClipboard failed")
        }
    }

    private fun trimUnpinnedLocked() {
        var unpinnedCount = 0
        val iter = items.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!e.pinned) {
                unpinnedCount++
                if (unpinnedCount > maxUnpinned) {
                    iter.remove()
                }
            }
        }
    }

    private fun loadFromDisk() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val lines = file.readLines(Charsets.UTF_8)
            var maxId = 0L
            synchronized(lock) {
                items.clear()
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val p1 = line.indexOf('\t')
                    val p2 = if (p1 >= 0) line.indexOf('\t', p1 + 1) else -1
                    val p3 = if (p2 >= 0) line.indexOf('\t', p2 + 1) else -1
                    if (p1 < 0 || p2 < 0 || p3 < 0) continue
                    val id = line.substring(0, p1).toLongOrNull() ?: continue
                    val pinned = line.substring(p1 + 1, p2) == "1"
                    val ts = line.substring(p2 + 1, p3).toLongOrNull() ?: 0L
                    val text = unescape(line.substring(p3 + 1))
                    if (text.isBlank()) continue
                    if (id > maxId) maxId = id
                    items.add(Entry(id = id, text = text, pinned = pinned, timestamp = ts))
                }
                nextId = maxId + 1L
                trimUnpinnedLocked()
            }
        }.onFailure {
            Timber.w(it, "ClipboardStore: loadFromDisk failed")
        }
    }

    private fun saveToDiskLocked() {
        val file = storageFile ?: return
        runCatching {
            val sb = StringBuilder()
            for (e in items) {
                sb.append(e.id).append('\t')
                    .append(if (e.pinned) '1' else '0').append('\t')
                    .append(e.timestamp).append('\t')
                    .append(escape(e.text)).append('\n')
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(sb.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure {
            Timber.w(it, "ClipboardStore: saveToDisk failed")
        }
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { append('\\'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    else -> { append(ch); i++ }
                }
            } else {
                append(ch)
                i++
            }
        }
    }

    companion object {
        const val MAX_UNPINNED = 60
        private const val FILE_NAME = "clipboard_history.tsv"

        fun create(context: Context): ClipboardStore =
            ClipboardStore(File(context.filesDir, FILE_NAME))
    }
}
