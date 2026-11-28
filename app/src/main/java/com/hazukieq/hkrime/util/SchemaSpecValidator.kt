package com.hazukieq.hkrime.util

import com.hazukieq.hkrime.core.Rime
import java.io.File
import timber.log.Timber

/**
 * 方案規範校驗器（拒絕加載舊版規範的方案）。
 *
 * 最新版規範要求：
 * 1. 所有方案必須顯式聲明 `keyboard/type`（`12key` / `9key` / `qwerty` 等），禁止隱式推斷。
 * 2. 多鍵方案（`12key` / `9key`）必須啟用原生 Token-Set 引擎（`speller/multi_key: true`），
 *    `speller/alphabet` 與 `speller/initials` 禁止包含數字 `0-9` 或 `;` 佔位符。
 * 3. `speller/algebra` 禁止包含舊版 `xlit` 字符替換或把字母映射到按鍵數字的規則
 *    （如 `derive/[abc]/2/`、`xform/.../9/`）。
 */
object SchemaSpecValidator {

    private val MULTI_KEY_TYPES = setOf("12key", "9key")
    private val XLIT_RULE = Regex("""^xlit(?:\W|$)""")
    private val DERIVE_OR_XFORM = Regex("""^(derive|xform)([^\sA-Za-z0-9])(.+)$""")
    private val CAPTURE_REF = Regex("""\$\d+""")
    private val DIGIT_OR_SURROGATE = Regex("""[0-9;]""")
    private val PURE_DIGITS_OR_SURROGATE = Regex("""^[0-9;]+$""")

    data class ValidationResult(
        val schemaId: String,
        val valid: Boolean,
        val keyboardType: String?,
        val reasons: List<String>,
    ) {
        fun formatMessage(): String =
            if (valid) "" else "拒绝加载旧版/不合规方案 $schemaId：${reasons.joinToString("；")}"
    }

    internal data class ParsedSchemaSpec(
        val keyboardType: String? = null,
        val multiKey: Boolean? = null,
        val alphabet: String? = null,
        val initials: String? = null,
        val algebra: List<String> = emptyList(),
    )

    /**
     * 校驗已由 librime 編譯並讀取到的運行時方案配置。
     */
    @JvmStatic
    fun validateLoadedSchema(schemaId: String): ValidationResult {
        if (schemaId.isBlank() || schemaId == ".default") {
            return ValidationResult(schemaId, valid = false, keyboardType = null, reasons = listOf("方案 ID 无效"))
        }
        val keyboardType = Rime.getSchemaValueCached(schemaId, "keyboard/type")?.toString()?.trim()
        val multiKeyRaw = Rime.getSchemaValueCached(schemaId, "speller/multi_key")
        val multiKey = when (multiKeyRaw) {
            is Boolean -> multiKeyRaw
            null -> null
            else -> multiKeyRaw.toString().trim().equals("true", ignoreCase = true)
        }
        val alphabet = Rime.getSchemaValueCached(schemaId, "speller/alphabet")?.toString()
        val initials = Rime.getSchemaValueCached(schemaId, "speller/initials")?.toString()
        val algebraRaw = Rime.getSchemaValueCached(schemaId, "speller/algebra")
        val algebra = if (algebraRaw is List<*>) {
            algebraRaw.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        } else {
            emptyList()
        }
        return validateParsed(
            schemaId = schemaId,
            spec = ParsedSchemaSpec(
                keyboardType = keyboardType,
                multiKey = multiKey,
                alphabet = alphabet,
                initials = initials,
                algebra = algebra,
            ),
        )
    }

    /**
     * 從磁盤目錄讀取 `<schemaId>.schema.yaml`（支持 `__include:` 繼承）並校驗是否符合最新規範。
     */
    @JvmStatic
    fun validateSchemaFiles(
        userDataDir: File?,
        sharedDataDir: File?,
        schemaId: String,
    ): ValidationResult {
        if (schemaId.isBlank() || schemaId == ".default") {
            return ValidationResult(schemaId, valid = false, keyboardType = null, reasons = listOf("方案 ID 无效"))
        }
        val resolver: (String) -> String? = { fileName ->
            val cleanName = fileName.trim()
            val candidateUser = userDataDir?.let { File(it, cleanName) }?.takeIf { it.isFile }
            val candidateShared = sharedDataDir?.let { File(it, cleanName) }?.takeIf { it.isFile }
            runCatching { (candidateUser ?: candidateShared)?.readText(Charsets.UTF_8) }.getOrNull()
        }
        val rootText = resolver("$schemaId.schema.yaml")
            ?: return ValidationResult(
                schemaId = schemaId,
                valid = false,
                keyboardType = null,
                reasons = listOf("找不到方案文件 $schemaId.schema.yaml"),
            )
        return validateYamlText(schemaId, rootText, resolver)
    }

    /**
     * 純 Kotlin YAML 校驗入口（便於 JVM 單元測試與文件預檢，不依賴 JNI）。
     */
    fun validateYamlText(
        schemaId: String,
        rawYaml: String,
        resolver: ((String) -> String?)? = null,
    ): ValidationResult {
        val parsed = parseYamlSpec(rawYaml, resolver, HashSet())
        return validateParsed(schemaId, parsed)
    }

    internal fun validateParsed(schemaId: String, spec: ParsedSchemaSpec): ValidationResult {
        val reasons = ArrayList<String>()
        val type = spec.keyboardType?.trim()?.takeIf { it.isNotEmpty() }
        if (type == null) {
            reasons.add("缺少显式 keyboard/type 声明（不再支持无类型旧方案）")
        }

        val isMultiKeyType = type != null && type in MULTI_KEY_TYPES
        if (isMultiKeyType) {
            if (spec.multiKey != true) {
                reasons.add("多键方案($type)缺少 speller/multi_key: true")
            }
            if (!spec.alphabet.isNullOrEmpty() && DIGIT_OR_SURROGATE.containsMatchIn(spec.alphabet)) {
                reasons.add("多键方案($type)的 speller/alphabet 禁止包含数字 0-9 或 ';' 占位符")
            }
            if (!spec.initials.isNullOrEmpty() && DIGIT_OR_SURROGATE.containsMatchIn(spec.initials)) {
                reasons.add("多键方案($type)的 speller/initials 禁止包含数字 0-9 或 ';' 占位符")
            }
        }

        for (rawRule in spec.algebra) {
            val rule = rawRule.trim()
            if (rule.isEmpty()) continue
            if (XLIT_RULE.containsMatchIn(rule)) {
                reasons.add("speller/algebra 禁止旧版 xlit 映射规则：$rule")
                break
            }
            if (isLegacyDigitMappingRule(rule, isMultiKeyType)) {
                reasons.add("speller/algebra 禁止旧版按键数字映射规则：$rule")
                break
            }
        }

        return ValidationResult(
            schemaId = schemaId,
            valid = reasons.isEmpty(),
            keyboardType = type,
            reasons = reasons,
        )
    }

    private fun isLegacyDigitMappingRule(rule: String, strictMultiKey: Boolean): Boolean {
        val match = DERIVE_OR_XFORM.matchEntire(rule) ?: return false
        val sep = match.groupValues[2][0]
        val rest = match.groupValues[3]
        val parts = rest.split(sep)
        if (parts.size < 2) return false
        val rhsWithoutBackrefs = CAPTURE_REF.replace(parts[1], "")
        if (rhsWithoutBackrefs.isEmpty()) return false
        return if (strictMultiKey) {
            DIGIT_OR_SURROGATE.containsMatchIn(rhsWithoutBackrefs)
        } else {
            PURE_DIGITS_OR_SURROGATE.matches(rhsWithoutBackrefs)
        }
    }

    private fun parseYamlSpec(
        rawYaml: String,
        resolver: ((String) -> String?)?,
        visited: MutableSet<String>,
    ): ParsedSchemaSpec {
        val lines = rawYaml.lines()
        var merged = ParsedSchemaSpec()

        // 先處理頂層 __include（如 rime_ice_qwerty.schema.yaml:/）
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (indent == 0 && trimmed.startsWith("__include:")) {
                val target = unquoteScalar(trimmed.substringAfter(':').trim())
                val fileName = target.substringBefore(":/").trim()
                if (fileName.isNotEmpty() && resolver != null && visited.add(fileName)) {
                    val parentYaml = resolver(fileName)
                    if (!parentYaml.isNullOrBlank()) {
                        merged = parseYamlSpec(parentYaml, resolver, visited)
                    }
                }
            }
        }

        var keyboardType = merged.keyboardType
        var multiKey = merged.multiKey
        var alphabet = merged.alphabet
        var initials = merged.initials
        var algebra: MutableList<String>? = null

        var section: String? = null
        var sectionIndent = -1
        var spellerSubkey: String? = null
        var spellerSubIndent = -1

        for (rawLine in lines) {
            val noLeading = rawLine.trimStart()
            if (noLeading.isEmpty() || noLeading.startsWith("#")) continue
            val indent = rawLine.length - noLeading.length

            if (indent == 0) {
                spellerSubkey = null
                spellerSubIndent = -1
                if (!noLeading.startsWith("-")) {
                    val colon = noLeading.indexOf(':')
                    if (colon > 0) {
                        val key = noLeading.substring(0, colon).trim()
                        section = key
                        sectionIndent = indent
                    } else {
                        section = null
                    }
                } else {
                    section = null
                }
                continue
            }

            if (section == "keyboard" && indent > sectionIndent) {
                val colon = noLeading.indexOf(':')
                if (colon > 0) {
                    val key = noLeading.substring(0, colon).trim()
                    if (key == "type") {
                        keyboardType = unquoteScalar(noLeading.substring(colon + 1))
                    }
                }
                continue
            }

            if (section == "speller" && indent > sectionIndent) {
                if (spellerSubkey != null && indent <= spellerSubIndent && !noLeading.startsWith("-")) {
                    spellerSubkey = null
                    spellerSubIndent = -1
                }
                if (spellerSubkey == "algebra" && noLeading.startsWith("-")) {
                    val item = unquoteScalar(noLeading.removePrefix("-"))
                    if (item.isNotEmpty()) {
                        (algebra ?: ArrayList<String>().also { algebra = it }).add(item)
                    }
                    continue
                }
                val colon = noLeading.indexOf(':')
                if (colon > 0 && !noLeading.startsWith("-")) {
                    val key = noLeading.substring(0, colon).trim()
                    val rawVal = noLeading.substring(colon + 1)
                    when (key) {
                        "multi_key" -> multiKey = unquoteScalar(rawVal).equals("true", ignoreCase = true)
                        "alphabet" -> alphabet = unquoteScalar(rawVal)
                        "initials" -> initials = unquoteScalar(rawVal)
                        "algebra", "algebra/+" -> {
                            spellerSubkey = "algebra"
                            spellerSubIndent = indent
                            if (key == "algebra" && algebra == null) {
                                algebra = ArrayList()
                            } else if (key == "algebra/+" && algebra == null) {
                                algebra = merged.algebra.toMutableList()
                            }
                        }
                        else -> {
                            spellerSubkey = key
                            spellerSubIndent = indent
                        }
                    }
                }
            }
        }

        return ParsedSchemaSpec(
            keyboardType = keyboardType,
            multiKey = multiKey,
            alphabet = alphabet,
            initials = initials,
            algebra = algebra ?: merged.algebra,
        )
    }

    private fun unquoteScalar(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val first = trimmed[0]
        if (first == '\'' || first == '"') {
            val end = trimmed.indexOf(first, startIndex = 1)
            if (end > 0) return trimmed.substring(1, end)
        }
        val commentIdx = findUnquotedCommentStart(trimmed)
        val noComment = if (commentIdx >= 0) trimmed.substring(0, commentIdx) else trimmed
        return noComment.trim()
    }

    private fun findUnquotedCommentStart(s: String): Int {
        for (i in s.indices) {
            if (s[i] == '#' && (i == 0 || s[i - 1].isWhitespace())) {
                return i
            }
        }
        return -1
    }

    /** 輔助日誌輸出：記錄並返回結果，便於調用處一行攔截。 */
    @JvmStatic
    fun logAndCheckLoaded(schemaId: String): ValidationResult {
        val res = validateLoadedSchema(schemaId)
        if (!res.valid) {
            Timber.w("SchemaSpecValidator: %s", res.formatMessage())
        }
        return res
    }
}
