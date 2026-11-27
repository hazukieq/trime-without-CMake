package com.hazukieq.hkrime.data

import com.hazukieq.hkrime.ime.keyboard.BuiltinKeyboards

/**
 * 方案與鍵盤布局的強類型解析器（Theme v5.0，無舊版無類型主題兼容包袱）。
 *
 * 設計契約：
 * - 內置 5 種標準鍵盤類型：`qwerty`、`12key`、`9key`、`wubi`、`stroke`（布局由 [BuiltinKeyboards] 按需懶構建）。
 * - 主題可選在 `keyboards:` 中覆蓋已有布局（同 ID 或同 type）或新建自定義 `type` 並設計布局；
 *   任何未聲明 `type` 的雜亂鍵盤（除非覆蓋內置工具鍵盤 `english` / `number`）一律拒絕。
 * - 解析鏈每一步均強制校驗 `layout.type == schemaType`，嚴禁跨類型混選。
 */
object KeyboardLayoutResolver {

    const val TYPE_QWERTY = "qwerty"
    const val TYPE_12KEY = "12key"
    const val TYPE_9KEY = "9key"
    const val TYPE_WUBI = "wubi"
    const val TYPE_STROKE = "stroke"

    /** 單個可選主鍵盤布局的輕量描述。 */
    data class LayoutInfo(
        val id: String,
        val name: String,
        val type: String,
    )

    /**
     * 鍵盤類型統一能力模型：將「非 26 鍵 preedit 具體化、12 鍵確認態會話、後台預熱探針鍵」
     * 全部收口到單一真源，消除業務層散落的 `TYPE_9KEY / TYPE_STROKE` 特判。
     */
    data class KeyboardTypeCapabilities(
        val materializePreeditFromCandidate: Boolean,
        val useTwelveKeySession: Boolean,
        val warmupProbeChar: Char,
    )

    /** 根據鍵盤類型返回統一能力配置（任意非 26 鍵類型均自動遵循一致的 preedit 具體化範式）。 */
    @JvmStatic
    fun capabilitiesOf(schemaType: String?): KeyboardTypeCapabilities {
        val type = normalizeType(schemaType)
        return when (type) {
            TYPE_QWERTY, TYPE_WUBI -> KeyboardTypeCapabilities(
                materializePreeditFromCandidate = false,
                useTwelveKeySession = false,
                warmupProbeChar = 'a',
            )
            TYPE_12KEY -> KeyboardTypeCapabilities(
                materializePreeditFromCandidate = true,
                useTwelveKeySession = true,
                warmupProbeChar = 'a',
            )
            TYPE_9KEY -> KeyboardTypeCapabilities(
                materializePreeditFromCandidate = true,
                useTwelveKeySession = true,
                warmupProbeChar = '2',
            )
            TYPE_STROKE -> KeyboardTypeCapabilities(
                materializePreeditFromCandidate = true,
                useTwelveKeySession = false,
                warmupProbeChar = 'h',
            )
            else -> KeyboardTypeCapabilities(
                // 未來自定義的非 26 鍵類型默認統一走 preedit 具體化範式
                materializePreeditFromCandidate = true,
                useTwelveKeySession = false,
                warmupProbeChar = 'a',
            )
        }
    }

    /** 規範化類型名：去空白、小寫；空串時按缺省 `qwerty` 處理。 */
    @JvmStatic
    fun normalizeType(raw: String?): String {
        val clean = raw?.trim()?.lowercase().orEmpty()
        return clean.ifEmpty { TYPE_QWERTY }
    }

    /**
     * 解析方案對應的鍵盤類型：
     * 1) 優先使用方案顯式聲明的 `keyboard/type`；
     * 2) 未聲明時，自動識別常見第三方方案 ID（如霧凇拼音 `t9` → `9key`、五筆 `wubi*` → `wubi`、
     *    筆畫 `stroke` / `bihua*` → `stroke`、`hakka_jiugong` → `12key`）；
     * 3) 其餘（`rime_ice`、`radical_pinyin`、`melt_eng`、`luna_pinyin`、`ipa_yunlong` 等）默認 `qwerty`。
     */
    @JvmStatic
    fun inferSchemaType(schemaId: String?, declaredType: String?): String {
        val explicit = declaredType?.trim()?.lowercase().orEmpty()
        if (explicit.isNotEmpty()) return explicit
        val id = schemaId?.trim()?.lowercase().orEmpty()
        if (id.isEmpty() || id == ".default") return TYPE_QWERTY
        return when {
            id == "hakka_jiugong" -> TYPE_12KEY
            id == "t9" || id.startsWith("t9_") || id.endsWith("_t9") ||
                id.contains("9key") -> TYPE_9KEY
            id == "wubi" || id.startsWith("wubi") || id.contains("_wubi") -> TYPE_WUBI
            id == "stroke" || id.startsWith("stroke_") || id.endsWith("_stroke") ||
                id.startsWith("bihua") -> TYPE_STROKE
            else -> TYPE_QWERTY
        }
    }

    /** 解析某個鍵盤配置顯式聲明的 type（未聲明返回 null）。 */
    @JvmStatic
    fun declaredTypeOf(keyboardMap: Map<*, *>?): String? {
        val raw = keyboardMap?.get("type")?.toString()?.trim()?.lowercase()
        return raw?.takeIf { it.isNotEmpty() }
    }

    /**
     * 過濾主題自定義的 `keyboards:` 條目：
     * - 必須顯式聲明 `type`（允許覆蓋已有類型或新建自定義類型），或是覆蓋內置工具鍵盤（`english` / `number`）；
     * - 拒絕無 `type` 的雜亂鍵盤。
     */
    @JvmStatic
    fun filterCustomKeyboards(
        rawKeyboards: Map<String, Map<String, *>>?,
    ): Map<String, Map<String, *>> {
        if (rawKeyboards.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Map<String, *>>()
        for ((rawId, def) in rawKeyboards) {
            val id = BuiltinKeyboards.canonicalName(rawId)
            val isUtilityOverride = BuiltinKeyboards.isUtility(id)
            val declaredType = declaredTypeOf(def)
            if (declaredType == null && !isUtilityOverride) {
                continue
            }
            out[id] = def
        }
        return out
    }

    /**
     * 合併內置主打字布局與主題自定義打字布局（排除 `english` / `number` 工具鍵盤）。
     * 同 ID 時以主題自定義覆蓋內置描述。
     */
    @JvmStatic
    fun mergedTypingCatalog(
        customKeyboards: Map<String, Map<String, *>>?,
    ): List<LayoutInfo> {
        val byId = LinkedHashMap<String, LayoutInfo>()
        for (info in BuiltinKeyboards.typingMetadata()) {
            byId[info.id] = info
        }
        if (!customKeyboards.isNullOrEmpty()) {
            for ((rawId, def) in customKeyboards) {
                val id = BuiltinKeyboards.canonicalName(rawId)
                if (BuiltinKeyboards.isUtility(id)) continue
                val type = declaredTypeOf(def) ?: continue
                if (type == "english" || type == "number") continue
                val displayName = def["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: byId[id]?.name
                    ?: id
                byId[id] = LayoutInfo(id = id, name = displayName, type = type)
            }
        }
        return byId.values.toList()
    }

    /**
     * 列出與 [schemaType] 嚴格匹配的所有可選主鍵盤布局（內置 + 主題自定義）。
     */
    @JvmStatic
    fun availableLayouts(
        schemaType: String?,
        customKeyboards: Map<String, Map<String, *>>?,
    ): List<LayoutInfo> {
        val wantType = normalizeType(schemaType)
        return mergedTypingCatalog(customKeyboards).filter { it.type == wantType }
    }

    /** 檢查指定布局 ID 是否與 [schemaType] 嚴格匹配。 */
    @JvmStatic
    fun isCompatible(
        layoutId: String?,
        schemaType: String?,
        customKeyboards: Map<String, Map<String, *>>?,
    ): Boolean {
        if (layoutId.isNullOrBlank()) return false
        val canonical = BuiltinKeyboards.canonicalName(layoutId.trim())
        val wantType = normalizeType(schemaType)
        return mergedTypingCatalog(customKeyboards).any { it.id == canonical && it.type == wantType }
    }

    /**
     * 為當前方案解析 `.default` 應使用的主鍵盤布局 ID。
     *
     * 優先級（每步強制校驗 `layout.type == schemaType`）：
     * 1. 用戶在設置中為當前方案選擇的布局（[userSelectedLayout]）
     * 2. 方案可選推薦的默認布局（[schemaDefaultLayout]）
     * 3. 主題 `keyboard.defaults[schemaType]`
     * 4. 同類型第一個可用布局（內置或自定義）
     * 5. 安全兜底 `"letter"`（避免空指針）
     */
    @JvmStatic
    fun resolveDefaultLayout(
        schemaType: String?,
        schemaDefaultLayout: String?,
        userSelectedLayout: String?,
        themeDefaults: Map<*, *>?,
        customKeyboards: Map<String, Map<String, *>>?,
    ): String {
        val wantType = normalizeType(schemaType)
        val candidates = availableLayouts(wantType, customKeyboards)
        val validIds = candidates.map { it.id }.toSet()

        fun pick(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val canonical = BuiltinKeyboards.canonicalName(raw.trim())
            return canonical.takeIf { it in validIds }
        }

        pick(userSelectedLayout)?.let { return it }
        pick(schemaDefaultLayout)?.let { return it }
        pick(themeDefaults?.get(wantType)?.toString())?.let { return it }
        candidates.firstOrNull()?.let { return it.id }
        return "letter"
    }
}
