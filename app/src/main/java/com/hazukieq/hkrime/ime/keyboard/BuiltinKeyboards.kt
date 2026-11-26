package com.hazukieq.hkrime.ime.keyboard

/**
 * App-side native utility keyboards:
 * - `number` (123 numeric pad)
 * - `edit` (cursor & clipboard editing pad; legacy alias `numberb`)
 *
 * Note: Symbols, emojis, and kaomoji are handled by the fixed native `SymbolPanelController`
 * backed by plain `.txt` files under `<userDataDir>/symbols/`, not by theme-editable keyboards.
 *
 * Themes can optionally customize lightweight parameters for `number` / `edit` under
 * `utility_keyboards:` (e.g. `height`, `width`), or override them in `preset_keyboards:`.
 */
object BuiltinKeyboards {

    private val allBuiltinIds: List<String> = listOf("number", "edit")
    private val allBuiltinSet: Set<String> = allBuiltinIds.toSet()

    @JvmStatic
    fun allNames(): List<String> = allBuiltinIds

    @JvmStatic
    fun canonicalName(name: String?): String = when (name) {
        "numberb" -> "edit"
        else -> name ?: "default"
    }

    @JvmStatic
    fun isBuiltin(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return canonicalName(name) in allBuiltinSet
    }

    /**
     * Registers default `Keyboard_*` switching actions and numeric/edit helper keys
     * into [target] without overwriting keys explicitly defined by the theme.
     */
    @JvmStatic
    fun registerPresetKeys(target: MutableMap<String, Map<String, String>>) {
        fun putIfAbsentKey(key: String, label: String, select: String, textSize: String? = null) {
            if (target.containsKey(key)) return
            val map = linkedMapOf(
                "label" to label,
                "functional" to "false",
                "send" to "Eisu_toggle",
                "select" to select,
            )
            if (textSize != null) map["key_text_size"] = textSize
            target[key] = map
        }

        // Utility keyboards (number / edit)
        putIfAbsentKey("Keyboard_number", "123", "number")
        putIfAbsentKey("Keyboard_edit", "编辑", "edit")

        // Native SymbolPanel triggers (intercepted by TextInputManager → SymbolPanelController)
        putIfAbsentKey("Keyboard_symbols", "符", "symbols")
        putIfAbsentKey("Keyboard_symbolsnum", "符号", "symbols")
        putIfAbsentKey("Keyboard_emoji", "🙂", "emoji")
        putIfAbsentKey("Keyboard_kaomoji", "^_^", "kaomoji")
        putIfAbsentKey("Keyboard_bqrw", "符", "symbols")
        putIfAbsentKey("Keyboard_bqsy1", "中文", "symbols", "14")
        putIfAbsentKey("Keyboard_bqsy2", "英文", "bqfh3", "14")
        putIfAbsentKey("Keyboard_bqsy3", "表情", "emoji", "14")
        putIfAbsentKey("Keyboard_bqsy4", "颜文", "kaomoji", "14")
        putIfAbsentKey("Keyboard_kao", "^_^", "kaomoji")

        // Numeric pad digits (1..9) & edit pad Tab
        for (d in 1..9) {
            val k = "num_$d"
            if (!target.containsKey(k)) {
                target[k] = linkedMapOf(
                    "label" to d.toString(),
                    "send" to d.toString(),
                    "functional" to "false",
                )
            }
        }
        if (!target.containsKey("Tab1")) {
            target["Tab1"] = linkedMapOf(
                "label" to "Tab",
                "send" to "Tab",
                "functional" to "false",
            )
        }
    }

    /**
     * Builds the `Keyboard` map definition for a built-in utility keyboard (`number` or `edit`),
     * applying optional theme-level overrides from `utility_keyboards:`.
     */
    @JvmStatic
    fun build(name: String, utilityOverrides: Map<*, *>?): Map<String, Any> {
        val canonical = canonicalName(name)
        val overrides = extractOverride(utilityOverrides, canonical)
        return when (canonical) {
            "edit" -> buildEditKeyboard(overrides)
            else -> buildNumberKeyboard(overrides)
        }
    }

    private fun extractOverride(root: Map<*, *>?, key: String): Map<String, Any> {
        val raw = root?.get(key) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in raw) {
            if (k != null && v != null) out[k.toString()] = v
        }
        return out
    }

    private fun applyBoardOverrides(
        board: MutableMap<String, Any>,
        overrides: Map<String, Any>,
    ) {
        overrides["height"]?.let { board["height"] = it }
        overrides["width"]?.let { board["width"] = it }
        overrides["horizontal_gap"]?.let { board["horizontal_gap"] = it }
        overrides["vertical_gap"]?.let { board["vertical_gap"] = it }
    }

    private fun key(vararg pairs: Pair<String, Any>): Map<String, Any> = linkedMapOf(*pairs)

    private fun buildNumberKeyboard(overrides: Map<String, Any>): Map<String, Any> {
        val rowHeight = overrides["height"] ?: 55
        val bottomRowHeight = overrides["bottom_height"] ?: 50
        val spacer = key("key_back_color" to 0, "width" to 100, "height" to 7)
        val keys = listOf(
            // 与 12 键 jiugong_simp 保持完全一致的垂直节奏（5×7 间隔 + 3×55 + 50 = 250，scale=1.0）
            spacer,
            key("click" to "{}{Left}", "long_click" to "【】{Left}", "height" to rowHeight),
            key("click" to "+"),
            key("click" to "num_1"),
            key("click" to "num_2"),
            key("click" to "num_3"),
            key("click" to "BackSpace"),

            spacer,
            key("click" to "_", "long_click" to "[]{Left}", "height" to rowHeight),
            key("click" to "-", "long_click" to "%"),
            key("click" to "num_4"),
            key("click" to "num_5"),
            key("click" to "num_6"),
            key("click" to "=", "long_click" to "≠"),

            spacer,
            key("click" to "(){Left}{Keyboard_default}", "long_click" to "@", "height" to rowHeight),
            key("click" to "*", "long_click" to "±"),
            key("click" to "num_7", "long_click" to "select_all"),
            key("click" to "num_8", "long_click" to "copy"),
            key("click" to "num_9", "long_click" to "paste"),
            key("click" to "space"),

            spacer,
            key("click" to "#", "long_click" to ":", "height" to bottomRowHeight),
            key("click" to "Keyboard_default"),
            key("click" to "."),
            key("click" to 0, "long_click" to "cut"),
            key("click" to "/", "long_click" to "\\"),
            key("click" to "Return"),
            spacer,
        )
        return linkedMapOf<String, Any>(
            "name" to "数字",
            "width" to 16.66,
            "height" to rowHeight,
            "horizontal_gap" to 5,
            "vertical_gap" to 0,
            "ascii_mode" to 1,
            "keys" to keys,
        ).also { applyBoardOverrides(it, overrides) }
    }

    private fun buildEditKeyboard(overrides: Map<String, Any>): Map<String, Any> {
        val keys = listOf(
            key("click" to "select_all"),
            key("click" to "Left1"),
            key("click" to "Up"),
            key("click" to "Right1"),
            key("click" to "space1", "key_back_color" to "gnb", "key_text_color" to "gnt", "width" to 19),

            key("click" to "cut"),
            key("click" to "Left"),
            key("click" to "Shift_L2"),
            key("click" to "Right"),
            key("click" to "Keyboard_number", "key_back_color" to "gnb", "key_text_color" to "gnt", "width" to 19),

            key("click" to "copy"),
            key("click" to "Home"),
            key("click" to "Down"),
            key("click" to "End"),
            key("click" to "BackSpace", "key_back_color" to "gnb", "key_text_color" to "gnt", "width" to 19),

            key("click" to "paste"),
            key("click" to "undo"),
            key("click" to "Tab1"),
            key("click" to "redo"),
            key("click" to "Return", "key_back_color" to "gnb", "key_text_color" to "gnt", "width" to 19),

            // Bottom quick bar
            key("click" to "Keyboard_default", "height" to 26.5, "width" to 20, "key_text_size" to "14", "key_back_color" to "benter", "key_text_color" to "tenter"),
            key("label" to "符号", "click" to "Keyboard_symbols", "width" to 20, "key_text_size" to "14"),
            key("label" to "表情", "click" to "Keyboard_emoji", "width" to 20, "key_text_size" to "14"),
            key("label" to "颜文", "click" to "Keyboard_kaomoji", "width" to 20, "key_text_size" to "14"),
            key("click" to "Keyboard_last", "width" to 20, "key_text_size" to "14", "key_back_color" to "benter", "key_text_color" to "tenter"),
        )
        return linkedMapOf<String, Any>(
            "name" to "编辑",
            "width" to 20.25,
            "height" to 39.5,
            "ascii_mode" to 1,
            "keys" to keys,
        ).also { applyBoardOverrides(it, overrides) }
    }
}
