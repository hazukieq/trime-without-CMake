package com.hazukieq.hkrime.data

import java.io.File

/**
 * System-level defaults for Theme v5.0.
 *
 * Non-visual IME behavior, floating composition layout parameters, enter key labels,
 * and fallback dimension defaults live here in code rather than cluttering `hakka.trime.yaml`.
 */
object ThemeDefaults {

    /**
     * Loads `fallback_colors.yaml` directly from [userDir] (or [sharedDir] fallback)
     * without going through librime's config compiler, so edits take effect immediately.
     */
    @JvmStatic
    fun loadFallbackColors(userDir: String?, sharedDir: String?): Map<String, String> {
        val candidates = listOfNotNull(
            userDir?.let { File(it, "fallback_colors.yaml") },
            sharedDir?.let { File(it, "fallback_colors.yaml") },
        )
        for (file in candidates) {
            if (file.isFile && file.length() > 0) {
                val parsed = runCatching { parseFallbackColorsYaml(file.readText()) }.getOrNull()
                if (!parsed.isNullOrEmpty()) return parsed
            }
        }
        return defaultFallbackColors()
    }

    /** Parses the `fallback_colors:` key-value mapping from `fallback_colors.yaml`. */
    @JvmStatic
    fun parseFallbackColorsYaml(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var inSection = false
        for (rawLine in text.lineSequence()) {
            val noComment = rawLine.substringBefore('#')
            if (noComment.isBlank()) continue
            val trimmed = noComment.trim()
            val indent = noComment.indexOfFirst { !it.isWhitespace() }
            if (indent == 0) {
                inSection = trimmed == "fallback_colors:"
                continue
            }
            if (!inSection) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val k = trimmed.substring(0, colon).trim().trim('"', '\'')
            val v = trimmed.substring(colon + 1).trim().trim('"', '\'')
            if (k.isNotEmpty() && v.isNotEmpty()) {
                out[k] = v
            }
        }
        return out
    }

    /**
     * Builds the flat internal style map expected by [Config], starting from system defaults
     * and overlaying the v5.0 `keyboard:` / `candidate:` / `color_scheme` sections.
     */
    @JvmStatic
    fun buildStyleMap(themeRoot: Map<String, *>?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(systemStyleDefaults())
        if (themeRoot == null) return out

        // Top-level color_scheme
        themeRoot["color_scheme"]?.let { out["color_scheme"] = it }

        // 1) keyboard: block → flat Config keys
        val kb = themeRoot["keyboard"] as? Map<*, *>
        if (kb != null) {
            kb["height"]?.let { out["keyboard_height"] = it }
            kb["height_land"]?.let { out["keyboard_height_land"] = it }
            kb["key_width"]?.let { out["key_width"] = it }
            kb["key_height"]?.let { out["key_height"] = it }
            kb["horizontal_gap"]?.let { out["horizontal_gap"] = it }
            kb["vertical_gap"]?.let { out["vertical_gap"] = it }
            kb["round_corner"]?.let { out["round_corner"] = it }
            kb["text_size"]?.let { out["key_text_size"] = it }
            kb["long_text_size"]?.let { out["key_long_text_size"] = it }
            kb["symbol_text_size"]?.let { out["symbol_text_size"] = it }
            kb["background_folder"]?.let { out["background_folder"] = it }
            kb["defaults"]?.let { out["keyboard_defaults"] = it }
            // Optional advanced keyboard tweaks
            kb["press_offset_x"]?.let { out["key_press_offset_x"] = it }
            kb["press_offset_y"]?.let { out["key_press_offset_y"] = it }
            kb["text_offset_x"]?.let { out["key_text_offset_x"] = it }
            kb["text_offset_y"]?.let { out["key_text_offset_y"] = it }
            kb["symbol_offset_x"]?.let { out["key_symbol_offset_x"] = it }
            kb["symbol_offset_y"]?.let { out["key_symbol_offset_y"] = it }
            kb["preview_height"]?.let { out["preview_height"] = it }
            kb["preview_offset"]?.let { out["preview_offset"] = it }
            kb["preview_text_size"]?.let { out["preview_text_size"] = it }
        }

        // 2) candidate: block → flat Config keys
        val cand = themeRoot["candidate"] as? Map<*, *>
        if (cand != null) {
            cand["height"]?.let { out["candidate_view_height"] = it }
            cand["text_size"]?.let { out["candidate_text_size"] = it }
            cand["padding"]?.let { out["candidate_padding"] = it }
            cand["spacing"]?.let { out["candidate_spacing"] = it }
            cand["comment_text_size"]?.let { out["comment_text_size"] = it }
            cand["comment_height"]?.let { out["comment_height"] = it }
            cand["comment_on_top"]?.let { out["comment_on_top"] = it }
            cand["label_text_size"]?.let { out["label_text_size"] = it }
            cand["preedit_text_size"]?.let { out["text_size"] = it }
        }

        return out
    }

    /**
     * System-level defaults for behavior, floating preedit layout, and baseline dimensions.
     */
    @JvmStatic
    fun systemStyleDefaults(): Map<String, Any> = linkedMapOf(
        // Behavior & system
        "locale" to "zh_CN",
        "auto_caps" to false,
        "reset_ascii_mode" to true,
        "proximity_correction" to true,
        "vertical_correction" to -5,
        "background_dim_amount" to 0.5,
        "root_background" to "keyboard_back_color",
        "color_scheme" to "default",
        "horizontal" to true,
        "candidate_use_cursor" to false,
        "preedit_disable_touch" to true,
        "enter_label_mode" to 0,
        "enter_labels" to linkedMapOf(
            "go" to "前往",
            "done" to "完成",
            "next" to "下个",
            "pre" to "上个",
            "search" to "搜索",
            "send" to "发送",
            "default" to "Enter",
        ),
        // Baseline keyboard dimensions
        "keyboard_height" to 250,
        "keyboard_height_land" to 200,
        "key_width" to 10.0,
        "key_height" to 48,
        "horizontal_gap" to 4,
        "vertical_gap" to 8,
        "round_corner" to 12,
        "key_text_size" to 24,
        "key_long_text_size" to 18,
        "symbol_text_size" to 10,
        "key_press_offset_x" to 2,
        "key_press_offset_y" to 2,
        // Baseline candidate dimensions
        "candidate_view_height" to 28,
        "candidate_text_size" to 22,
        "candidate_padding" to 5,
        "candidate_spacing" to 0.5,
        "comment_text_size" to 20,
        "comment_height" to 20,
        "comment_on_top" to true,
        "label_text_size" to 22,
        "text_size" to 14,
        // Fixed composition / floating window layout
        "layout" to linkedMapOf(
            "position" to "fixed",
            "min_length" to 10,
            "max_length" to 14,
            "sticky_lines" to 1,
            "sticky_lines_land" to 0,
            "max_entries" to 5,
            "border" to 0,
            "max_width" to 360,
            "max_height" to 300,
            "min_width" to 0,
            "min_height" to 50,
            "margin_x" to 5,
            "margin_y" to 5,
            "line_spacing" to 0,
            "line_spacing_multiplier" to 1.2,
            "real_margin" to 3,
            "spacing" to 0,
            "round_corner" to 0,
            "alpha" to 255,
            "elevation" to 0,
            "movable" to false,
            "all_phrases" to false,
        ),
        "window" to listOf(
            linkedMapOf(
                "start" to "",
                "composition" to "%s",
                "end" to "",
                "letter_spacing" to 0.12,
            ),
        ),
        // Default layout ID per standard keyboard type
        "keyboard_defaults" to linkedMapOf(
            KeyboardLayoutResolver.TYPE_12KEY to "jiugong_simp",
            KeyboardLayoutResolver.TYPE_QWERTY to "letter",
            KeyboardLayoutResolver.TYPE_9KEY to "pinyin_9key",
            KeyboardLayoutResolver.TYPE_WUBI to "wubi_qwerty",
            KeyboardLayoutResolver.TYPE_STROKE to "stroke_9key",
        ),
    )

    /**
     * Built-in safety-net color fallback chain used only if `fallback_colors.yaml` is missing.
     */
    @JvmStatic
    fun defaultFallbackColors(): Map<String, String> = linkedMapOf(
        "keyboard_back_color" to "back_color",
        "root_background" to "keyboard_back_color",
        "border_color" to "keyboard_back_color",
        "key_border_color" to "border_color",
        "candidate_separator_color" to "border_color",
        "shadow_color" to "border_color",
        "candidate_background" to "back_color",
        "text_back_color" to "back_color",
        "hilited_back_color" to "back_color",
        "hilited_candidate_back_color" to "back_color",
        "key_back_color" to "back_color",
        "long_text_back_color" to "key_back_color",
        "key_symbol_color" to "key_text_color",
        "off_key_back_color" to "border_color",
        "off_key_text_color" to "key_text_color",
        "on_key_back_color" to "hilited_key_back_color",
        "on_key_text_color" to "hilited_key_text_color",
        "hilited_key_symbol_color" to "hilited_key_text_color",
        "hilited_off_key_back_color" to "hilited_key_back_color",
        "hilited_on_key_back_color" to "hilited_key_back_color",
        "hilited_off_key_text_color" to "hilited_key_text_color",
        "hilited_on_key_text_color" to "hilited_key_text_color",
        "preview_back_color" to "key_back_color",
        "preview_text_color" to "key_text_color",
        "candidate_text_color" to "key_text_color",
        "comment_text_color" to "key_symbol_color",
        "text_color" to "comment_text_color",
        "label_color" to "comment_text_color",
        "hilited_comment_text_color" to "comment_text_color",
        "hilited_candidate_text_color" to "hilited_key_back_color",
        "hilited_text_color" to "hilited_candidate_text_color",
        "hilited_label_color" to "hilited_candidate_text_color",
    )
}
