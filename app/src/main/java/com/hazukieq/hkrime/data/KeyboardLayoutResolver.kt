package com.hazukieq.hkrime.data

/**
 * 方案與主題鍵盤布局的強類型解析器（純邏輯，無 Android/JNI 依賴）。
 *
 * 設計契約：
 * - 方案在 `*.schema.yaml` 聲明 `keyboard/type`（如 `12key`、`qwerty`、`9key`），缺省為 `qwerty`；
 *   可選聲明 `keyboard/default` 推薦默認布局 ID。
 * - 主題在 `preset_keyboards/<id>` 聲明 `type` 與展示名 `name`，在 `style/keyboard_defaults` 聲明各類型的默認布局。
 * - 解析鏈每一步都強制校驗 `layout.type == schemaType`，嚴禁 12 鍵方案落入 26 鍵布局（反之亦然）。
 * - 若加載未升級的第三方舊主題（`preset_keyboards` 中未聲明任何 `type`），安全回退到舊版按方案 ID / alphabet 匹配邏輯。
 */
object KeyboardLayoutResolver {

    const val TYPE_QWERTY = "qwerty"
    const val TYPE_12KEY = "12key"
    const val TYPE_9KEY = "9key"

    /** 單個主題內可選布局的輕量描述。 */
    data class LayoutInfo(
        val id: String,
        val name: String,
        val type: String,
    )

    /** 規範化類型名：去空白、小寫；空串時按缺省 `qwerty` 處理。 */
    @JvmStatic
    fun normalizeType(raw: String?): String {
        val clean = raw?.trim()?.lowercase().orEmpty()
        return clean.ifEmpty { TYPE_QWERTY }
    }

    /** 解析某個 preset_keyboard 條目顯式聲明的 type（未聲明返回 null）。 */
    @JvmStatic
    fun declaredTypeOf(keyboardMap: Map<*, *>?): String? {
        val raw = keyboardMap?.get("type")?.toString()?.trim()?.lowercase()
        return raw?.takeIf { it.isNotEmpty() }
    }

    /** 判斷主題是否已啟用類型標註（任意一個 preset_keyboard 聲明了 `type` 即為新版主題）。 */
    @JvmStatic
    fun isTypedTheme(presetKeyboards: Map<String, Map<String, *>>?): Boolean {
        if (presetKeyboards.isNullOrEmpty()) return false
        return presetKeyboards.values.any { declaredTypeOf(it) != null }
    }

    /**
     * 列出當前主題中「與 [schemaType] 嚴格匹配」的所有可選主鍵盤布局（保持主題聲明順序）。
     * 排除純別名（`import_preset` 轉發項），只返回真實布局。
     */
    @JvmStatic
    fun availableLayouts(
        schemaType: String?,
        presetKeyboards: Map<String, Map<String, *>>?,
    ): List<LayoutInfo> {
        if (presetKeyboards.isNullOrEmpty()) return emptyList()
        val wantType = normalizeType(schemaType)
        val out = ArrayList<LayoutInfo>()
        for ((id, raw) in presetKeyboards) {
            if (raw.containsKey("import_preset")) continue
            val type = declaredTypeOf(raw) ?: continue
            if (type != wantType) continue
            val displayName = raw["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: id
            out.add(LayoutInfo(id = id, name = displayName, type = type))
        }
        return out
    }

    /** 檢查指定布局 ID 是否存在於主題且與 [schemaType] 嚴格匹配。 */
    @JvmStatic
    fun isCompatible(
        layoutId: String?,
        schemaType: String?,
        presetKeyboards: Map<String, Map<String, *>>?,
    ): Boolean {
        if (layoutId.isNullOrBlank() || presetKeyboards.isNullOrEmpty()) return false
        val target = resolveImportPreset(layoutId, presetKeyboards) ?: return false
        val type = declaredTypeOf(presetKeyboards[target]) ?: return false
        return type == normalizeType(schemaType)
    }

    /**
     * 為當前方案解析 `.default` 應使用的主鍵盤布局 ID。
     *
     * 優先級（新版帶 `type` 主題，每步強制校驗 `layout.type == schemaType`）：
     * 1. 用戶在設置中為當前方案選擇的布局（[userSelectedLayout]）
     * 2. 方案可選推薦的默認布局（[schemaDefaultLayout]）
     * 3. 主題 `style/keyboard_defaults[schemaType]`
     * 4. 主題中第一個 `type == schemaType` 的布局
     * 5. 舊主題兼容回退（僅當主題未聲明任何 `type` 時生效）
     */
    @JvmStatic
    fun resolveDefaultLayout(
        schemaId: String?,
        schemaType: String?,
        schemaDefaultLayout: String?,
        userSelectedLayout: String?,
        themeDefaults: Map<*, *>?,
        presetKeyboards: Map<String, Map<String, *>>?,
        spellerAlphabet: String? = null,
    ): String {
        if (presetKeyboards.isNullOrEmpty()) return "default"
        val wantType = normalizeType(schemaType)

        if (isTypedTheme(presetKeyboards)) {
            // 1) 用戶設置覆蓋（強類型校驗：換主題或類型不符時安全回落）
            pickIfCompatible(userSelectedLayout, wantType, presetKeyboards)?.let { return it }
            // 2) 方案顯式推薦布局
            pickIfCompatible(schemaDefaultLayout, wantType, presetKeyboards)?.let { return it }
            // 3) 主題 style/keyboard_defaults[type]
            val themeDefault = themeDefaults?.get(wantType)?.toString()?.trim()
            pickIfCompatible(themeDefault, wantType, presetKeyboards)?.let { return it }
            // 4) 主題中第一個同類型布局
            availableLayouts(wantType, presetKeyboards).firstOrNull()?.let { return it.id }
            // 5) 最後兜底（主題完全未提供該類型布局）
            return legacyFallback(schemaId, presetKeyboards, spellerAlphabet)
        }

        // 未標註 type 的第三方舊主題：保持原 Trime 行為
        return legacyFallback(schemaId, presetKeyboards, spellerAlphabet)
    }

    private fun pickIfCompatible(
        candidateId: String?,
        wantType: String,
        presetKeyboards: Map<String, Map<String, *>>,
    ): String? {
        if (candidateId.isNullOrBlank()) return null
        val target = resolveImportPreset(candidateId.trim(), presetKeyboards) ?: return null
        val type = declaredTypeOf(presetKeyboards[target]) ?: return null
        return if (type == wantType) target else null
    }

    private fun resolveImportPreset(
        id: String,
        presetKeyboards: Map<String, Map<String, *>>,
    ): String? {
        var current = id
        var guard = 0
        while (guard++ < 4) {
            val map = presetKeyboards[current] ?: return null
            val imp = map["import_preset"]?.toString()?.trim()
            if (imp.isNullOrEmpty() || imp == current) return current
            current = imp
        }
        return if (presetKeyboards.containsKey(current)) current else null
    }

    private fun legacyFallback(
        schemaId: String?,
        presetKeyboards: Map<String, Map<String, *>>,
        spellerAlphabet: String?,
    ): String {
        var name = "default"
        if (!schemaId.isNullOrEmpty()) {
            if (presetKeyboards.containsKey(schemaId)) {
                name = schemaId
            } else {
                val prefix = if (schemaId.contains("_")) schemaId.substringBefore("_") else schemaId
                if (presetKeyboards.containsKey(prefix)) {
                    name = prefix
                } else {
                    var candidate = TYPE_QWERTY
                    if (!spellerAlphabet.isNullOrEmpty()) {
                        if (presetKeyboards.containsKey(spellerAlphabet)) {
                            candidate = spellerAlphabet
                        } else {
                            if (spellerAlphabet.contains(",") || spellerAlphabet.contains(";")) candidate += "_"
                            if (spellerAlphabet.contains("0") || spellerAlphabet.contains("1")) candidate += "0"
                        }
                    }
                    if (presetKeyboards.containsKey(candidate)) name = candidate
                }
            }
        }
        if (!presetKeyboards.containsKey(name)) name = "default"
        return resolveImportPreset(name, presetKeyboards) ?: "default"
    }
}
