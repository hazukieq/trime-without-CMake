package com.hazukieq.hkrime.ime.keyboard

import com.hazukieq.hkrime.data.KeyboardLayoutResolver

/**
 * App-side built-in keyboards (code-defined, lazily instantiated on demand):
 *
 * 1) Standard typing keyboards (matched by schema `keyboard/type`):
 *    - `jiugong_simp` (`type: 12key`)  — 客语 12 键
 *    - `letter`       (`type: qwerty`) — 26 键标准拼音
 *    - `pinyin_9key`  (`type: 9key`)   — 9 键拼音
 *    - `wubi_qwerty`  (`type: wubi`)   — 五笔 26 键
 *    - `stroke_9key`  (`type: stroke`) — 笔画键盘
 *
 * 2) Built-in switchable utility keyboards (follow theme colors automatically;
 *    advanced users may still override by defining the same ID in theme `keyboards:`):
 *    - `english` (legacy alias `qwerty_cn2en`) — 原生英文 26 键
 *    - `number`                                — 123 数字键盘
 *
 * Note: Symbols/emoji/kaomoji are handled by native `SymbolPanelController`, and
 * text editing (`edit` / `numberb`) is handled by native `EditPanelController`.
 */
object BuiltinKeyboards {

    private val typingCatalog: List<KeyboardLayoutResolver.LayoutInfo> = listOf(
        KeyboardLayoutResolver.LayoutInfo("jiugong_simp", "12键·标准客语", KeyboardLayoutResolver.TYPE_12KEY),
        KeyboardLayoutResolver.LayoutInfo("letter", "26键·标准拼音", KeyboardLayoutResolver.TYPE_QWERTY),
        KeyboardLayoutResolver.LayoutInfo("pinyin_9key", "9键·标准拼音", KeyboardLayoutResolver.TYPE_9KEY),
        KeyboardLayoutResolver.LayoutInfo("wubi_qwerty", "五笔·26键", KeyboardLayoutResolver.TYPE_WUBI),
        KeyboardLayoutResolver.LayoutInfo("stroke_9key", "笔画·五笔划", KeyboardLayoutResolver.TYPE_STROKE),
    )

    private val typingIdSet: Set<String> = typingCatalog.map { it.id }.toSet()
    private val utilityIds: List<String> = listOf("english", "number")
    private val utilityIdSet: Set<String> = utilityIds.toSet()

    /** Lightweight metadata for all built-in typing keyboards (no key maps built). */
    @JvmStatic
    fun typingMetadata(): List<KeyboardLayoutResolver.LayoutInfo> = typingCatalog

    /** Built-in switchable utility keyboard IDs (`english`, `number`). */
    @JvmStatic
    fun utilityNames(): List<String> = utilityIds

    /** All built-in keyboard IDs (typing + utility). */
    @JvmStatic
    fun allNames(): List<String> = typingCatalog.map { it.id } + utilityIds

    @JvmStatic
    fun canonicalName(name: String?): String = when (name) {
        "qwerty_cn2en" -> "english"
        "numberb" -> "edit"
        else -> name ?: "letter"
    }

    @JvmStatic
    fun isUtility(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return canonicalName(name) in utilityIdSet
    }

    @JvmStatic
    fun isBuiltin(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val canonical = canonicalName(name)
        return canonical in typingIdSet || canonical in utilityIdSet
    }

    /**
     * Registers all built-in core keys, 12-key/9-key/stroke input keys, and keyboard
     * switching triggers into [target] without overwriting keys explicitly defined by the theme.
     */
    @JvmStatic
    fun registerPresetKeys(target: MutableMap<String, Map<String, String>>) {
        fun putIfAbsent(key: String, vararg pairs: Pair<String, String>) {
            if (!target.containsKey(key)) {
                target[key] = linkedMapOf(*pairs)
            }
        }

        fun putSwitch(key: String, label: String, select: String, textSize: String? = null) {
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

        // 1) Hakka 12-key phonetic code keys
        putIfAbsent("letter_1", "label" to "ng", "send" to "1", "functional" to "false")
        putIfAbsent("letter_3", "label" to "vur", "send" to "3", "functional" to "false")
        putIfAbsent("letter_4", "label" to "mno", "send" to "4", "functional" to "false")
        putIfAbsent("letter_5", "label" to "kl", "send" to "5", "functional" to "false")
        putIfAbsent("letter_7", "label" to "ps", "send" to "7", "functional" to "false")
        putIfAbsent("letter_8", "label" to "zj", "send" to "8", "functional" to "false")
        putIfAbsent("letter_9", "label" to "qt", "send" to "9", "functional" to "false")
        putIfAbsent("letter_a", "label" to "abc", "send" to "a", "functional" to "false")
        putIfAbsent("letter_e", "label" to "def", "send" to "e", "functional" to "false")
        putIfAbsent("letter_i", "label" to "ghi", "send" to "i", "functional" to "false")
        putIfAbsent("letter_w", "label" to "th", "send" to "w", "functional" to "false")
        putIfAbsent("letter_y", "label" to "nz", "send" to "y", "functional" to "false")

        // 2) Standard T9 9-key Pinyin code keys (compatible with rime_ice t9.schema.yaml)
        putIfAbsent("t9_1", "label" to "@分词", "send" to "1", "functional" to "false")
        putIfAbsent("t9_2", "label" to "ABC", "send" to "2", "functional" to "false")
        putIfAbsent("t9_3", "label" to "DEF", "send" to "3", "functional" to "false")
        putIfAbsent("t9_4", "label" to "GHI", "send" to "4", "functional" to "false")
        putIfAbsent("t9_5", "label" to "JKL", "send" to "5", "functional" to "false")
        putIfAbsent("t9_6", "label" to "MNO", "send" to "6", "functional" to "false")
        putIfAbsent("t9_7", "label" to "PQRS", "send" to "7", "functional" to "false")
        putIfAbsent("t9_8", "label" to "TUV", "send" to "8", "functional" to "false")
        putIfAbsent("t9_9", "label" to "WXYZ", "send" to "9", "functional" to "false")

        // 3) Standard 5-stroke keys (h=横 s=竖 p=撇 n=捺/点 z=折)
        putIfAbsent("stroke_h", "label" to "一", "send" to "h", "functional" to "false")
        putIfAbsent("stroke_s", "label" to "丨", "send" to "s", "functional" to "false")
        putIfAbsent("stroke_p", "label" to "丿", "send" to "p", "functional" to "false")
        putIfAbsent("stroke_n", "label" to "丶", "send" to "n", "functional" to "false")
        putIfAbsent("stroke_z", "label" to "乙", "send" to "z", "functional" to "false")
        putIfAbsent("stroke_wild", "label" to "通配", "send" to "question", "functional" to "false")

        // 4) Core typing & navigation keys
        putIfAbsent("space", "label" to "空格", "preview" to " ", "send" to "space", "repeatable" to "false", "functional" to "false")
        putIfAbsent("BackSpace", "label" to "退格", "preview" to "⇦", "send" to "BackSpace", "repeatable" to "true", "functional" to "false")
        putIfAbsent("Return", "label" to "回车", "send" to "Return", "functional" to "false")
        putIfAbsent("Shift_L", "label" to "锁定", "preview" to "⇪", "send" to "Shift_L", "functional" to "false")
        putIfAbsent("Escape", "label" to "取消", "send" to "Escape", "functional" to "false")
        putIfAbsent("delimiter", "label" to "分词", "text" to "'")
        putIfAbsent("Page_Up", "label" to "上页", "send" to "Page_Up", "functional" to "false")
        putIfAbsent("Page_Down", "label" to "下页", "send" to "Page_Down", "functional" to "false")

        // 5) Cursor & clipboard actions
        putIfAbsent("Left", "label" to "←", "send" to "Left", "repeatable" to "true", "functional" to "false")
        putIfAbsent("Right", "label" to "→", "send" to "Right", "repeatable" to "true", "functional" to "false")
        putIfAbsent("Up", "label" to "↑", "send" to "Up", "functional" to "false")
        putIfAbsent("Down", "label" to "↓", "send" to "Down", "functional" to "false")
        putIfAbsent("Home", "label" to "行首", "send" to "Home", "functional" to "false")
        putIfAbsent("End", "label" to "行尾", "send" to "End", "functional" to "false")
        putIfAbsent("select_all", "label" to "全选", "send" to "Control+a", "functional" to "false")
        putIfAbsent("copy", "label" to "复制", "send" to "Control+c", "functional" to "false")
        putIfAbsent("cut", "label" to "剪切", "send" to "Control+x", "functional" to "false")
        putIfAbsent("paste", "label" to "粘贴", "send" to "Control+v", "functional" to "false")
        putIfAbsent("undo", "label" to "撤销", "send" to "Control+z", "functional" to "false")
        putIfAbsent("redo", "label" to "重做", "send" to "Control+y", "functional" to "false")

        // 6) Keyboard & mode switching keys
        putSwitch("Keyboard_default", "返回", ".default")
        putSwitch("Keyboard_last", "后退", ".last")
        putSwitch("Keyboard_en", "英文", "english")
        putSwitch("Keyboard_cn", "中文", ".default")
        putSwitch("Keyboard_number", "123", "number")
        putSwitch("Keyboard_edit", "编辑", "edit")
        putIfAbsent("Mode_switch", "toggle" to "ascii_mode", "send" to "Mode_switch", "states" to "[中文, 英文]", "functional" to "false")
        putIfAbsent("IME_switch", "label" to "ime", "send" to "LANGUAGE_SWITCH")
        putIfAbsent("Menu", "label" to "菜单", "send" to "Menu", "functional" to "false")
        putIfAbsent("Color_switch", "label" to "配色", "send" to "PROG_RED", "functional" to "false")

        // 7) Native SymbolPanel triggers (intercepted by TextInputManager → SymbolPanelController)
        putSwitch("Keyboard_symbols", "符", "symbols")
        putSwitch("Keyboard_symbolsnum", "符号", "symbols")
        putSwitch("Keyboard_emoji", "🙂", "emoji")
        putSwitch("Keyboard_kaomoji", "^_^", "kaomoji")
        putSwitch("Keyboard_bqrw", "符", "symbols")

        // 8) Numeric pad digits (1..9) & Tab
        for (d in 1..9) {
            putIfAbsent("num_$d", "label" to d.toString(), "send" to d.toString(), "functional" to "false")
        }
        putIfAbsent("Tab1", "label" to "Tab", "send" to "Tab", "functional" to "false")
    }

    /**
     * Lazily builds the keyboard map definition for any built-in keyboard ID.
     */
    @JvmStatic
    fun build(name: String): Map<String, Any> = when (canonicalName(name)) {
        "jiugong_simp" -> buildTwelveKeyKeyboard()
        "letter" -> buildQwertyKeyboard()
        "pinyin_9key" -> buildNineKeyPinyinKeyboard()
        "wubi_qwerty" -> buildWubiKeyboard()
        "stroke_9key" -> buildStrokeKeyboard()
        "english" -> buildEnglishKeyboard()
        "number" -> buildNumberKeyboard()
        else -> buildQwertyKeyboard()
    }

    private fun key(vararg pairs: Pair<String, Any>): Map<String, Any> = linkedMapOf(*pairs)

    private fun spacer(): Map<String, Any> = key("key_back_color" to 0, "width" to 100, "height" to 7)

    // -------------------------------------------------------------------------
    // 1. 客语 12 键（type: 12key）
    // -------------------------------------------------------------------------
    private fun buildTwelveKeyKeyboard(): Map<String, Any> {
        val sp = spacer()
        val keys = listOf(
            // Row 1
            sp,
            key("click" to "，", "long_click" to "-", "key_back_color" to 0, "width" to 16),
            key("click" to "letter_y", "long_click" to "@"),
            key("click" to "letter_9", "long_click" to "`"),
            key("click" to "letter_a", "long_click" to "Keyboard_edit"),
            key("click" to "letter_e", "long_click" to "~"),
            key("click" to "BackSpace", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 2
            sp,
            key("click" to "。", "long_click" to "#", "key_back_color" to 0, "width" to 16),
            key("click" to "letter_1", "long_click" to "'"),
            key("click" to "letter_i", "long_click" to "="),
            key("click" to "letter_5", "long_click" to "_"),
            key("click" to "letter_4", "long_click" to "*"),
            key("click" to "/", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 3
            sp,
            key("click" to "？", "long_click" to "！", "key_back_color" to 0, "width" to 16),
            key("click" to "letter_w", "long_click" to "copy"),
            key("click" to "letter_7", "long_click" to "paste"),
            key("click" to "letter_3", "long_click" to "cut"),
            key("click" to "letter_8", "long_click" to "[]{Left}"),
            key("click" to "、", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 4
            sp,
            key("click" to "Keyboard_bqrw", "key_back_color" to 0, "long_click" to "Menu", "width" to 16, "height" to 50),
            key("click" to "Keyboard_number", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "space", "width" to 34),
            key("click" to "Keyboard_en", "composing" to "delimiter", "long_click" to "IME_switch", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "Return", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            sp,
        )
        return linkedMapOf(
            "name" to "12键·标准客语",
            "type" to KeyboardLayoutResolver.TYPE_12KEY,
            "ascii_mode" to 0,
            "full_shape" to 0,
            "width" to 17,
            "height" to 55,
            "horizontal_gap" to 5,
            "vertical_gap" to 0,
            "key_press_offset_x" to 2,
            "key_press_offset_y" to 2,
            "keys" to keys,
        )
    }

    // -------------------------------------------------------------------------
    // 2. 26 键标准拼音（type: qwerty）
    // -------------------------------------------------------------------------
    private fun buildQwertyKeyboard(): Map<String, Any> {
        val keys = listOf(
            key("click" to "q", "long_click" to "`"),
            key("click" to "w", "long_click" to "@"),
            key("click" to "e", "long_click" to "#"),
            key("click" to "r", "long_click" to "$"),
            key("click" to "t", "long_click" to "%"),
            key("click" to "y", "long_click" to "^"),
            key("click" to "u", "long_click" to "&"),
            key("click" to "i", "long_click" to "*"),
            key("click" to "o", "long_click" to "(){Left}"),
            key("click" to "p", "long_click" to "|"),

            key("width" to 5),
            key("click" to "a", "long_click" to "!"),
            key("click" to "s", "long_click" to "~"),
            key("click" to "d", "long_click" to "-"),
            key("click" to "f", "long_click" to "_"),
            key("click" to "g", "long_click" to "\\"),
            key("click" to "h", "long_click" to "[]{Left}", "swipe_left" to "[", "swipe_right" to "]"),
            key("click" to "j", "long_click" to "{}{Left}", "swipe_left" to "{", "swipe_right" to "}"),
            key("click" to "k", "long_click" to ":"),
            key("click" to "l", "long_click" to ";"),
            key("width" to 5),

            key("click" to "Shift_L", "send_bindings" to false, "width" to 13, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "z", "long_click" to "?"),
            key("click" to "x", "long_click" to "cut"),
            key("click" to "c", "long_click" to "copy"),
            key("click" to "v", "long_click" to "paste"),
            key("click" to "b", "long_click" to "/"),
            key("click" to "n", "long_click" to "\""),
            key("click" to "m", "long_click" to "'"),
            key("click" to "BackSpace", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),

            key("click" to "Keyboard_number", "width" to 14, "long_click" to "Keyboard_symbols", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to ",", "long_click" to "Keyboard_edit"),
            key("click" to "space", "width" to 35),
            key("click" to ".", "long_click" to ">"),
            key("click" to "Keyboard_en", "long_click" to "Menu", "width" to 14, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "Return", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
        )
        return linkedMapOf(
            "name" to "26键·标准拼音",
            "type" to KeyboardLayoutResolver.TYPE_QWERTY,
            "ascii_mode" to 0,
            "full_shape" to 0,
            "width" to 10,
            "height" to 55,
            "key_press_offset_x" to 2,
            "key_press_offset_y" to 2,
            "keys" to keys,
        )
    }

    // -------------------------------------------------------------------------
    // 3. 9 键拼音（type: 9key）
    // -------------------------------------------------------------------------
    private fun buildNineKeyPinyinKeyboard(): Map<String, Any> {
        val sp = spacer()
        val keys = listOf(
            // Row 1
            sp,
            key("click" to "，", "long_click" to "-", "key_back_color" to 0, "width" to 16),
            key("click" to "t9_1", "long_click" to "1", "width" to 22.66),
            key("click" to "t9_2", "long_click" to "2", "width" to 22.67),
            key("click" to "t9_3", "long_click" to "3", "width" to 22.67),
            key("click" to "BackSpace", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 2
            sp,
            key("click" to "。", "long_click" to "#", "key_back_color" to 0, "width" to 16),
            key("click" to "t9_4", "long_click" to "4", "width" to 22.66),
            key("click" to "t9_5", "long_click" to "Keyboard_edit", "width" to 22.67),
            key("click" to "t9_6", "long_click" to "6", "width" to 22.67),
            key("click" to "Escape", "label" to "重输", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 3
            sp,
            key("click" to "？", "long_click" to "！", "key_back_color" to 0, "width" to 16),
            key("click" to "t9_7", "long_click" to "7", "width" to 22.66),
            key("click" to "t9_8", "long_click" to "8", "width" to 22.67),
            key("click" to "t9_9", "long_click" to "9", "width" to 22.67),
            key("click" to "、", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 4
            sp,
            key("click" to "Keyboard_symbols", "key_back_color" to 0, "long_click" to "Menu", "width" to 16, "height" to 50),
            key("click" to "Keyboard_number", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "space", "width" to 34),
            key("click" to "Keyboard_en", "long_click" to "IME_switch", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "Return", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            sp,
        )
        return linkedMapOf(
            "name" to "9键·标准拼音",
            "type" to KeyboardLayoutResolver.TYPE_9KEY,
            "ascii_mode" to 0,
            "full_shape" to 0,
            "width" to 17,
            "height" to 55,
            "horizontal_gap" to 5,
            "vertical_gap" to 0,
            "key_press_offset_x" to 2,
            "key_press_offset_y" to 2,
            "keys" to keys,
        )
    }

    // -------------------------------------------------------------------------
    // 4. 五笔 26 键（type: wubi）
    // -------------------------------------------------------------------------
    private fun buildWubiKeyboard(): Map<String, Any> {
        val base = buildQwertyKeyboard().toMutableMap()
        base["name"] = "五笔·26键"
        base["type"] = KeyboardLayoutResolver.TYPE_WUBI
        return base
    }

    // -------------------------------------------------------------------------
    // 5. 笔画键盘（type: stroke）
    // -------------------------------------------------------------------------
    private fun buildStrokeKeyboard(): Map<String, Any> {
        val sp = spacer()
        val keys = listOf(
            // Row 1: 横 竖 撇
            sp,
            key("click" to "，", "long_click" to "-", "key_back_color" to 0, "width" to 16),
            key("click" to "stroke_h", "long_click" to "1", "width" to 22.66),
            key("click" to "stroke_s", "long_click" to "2", "width" to 22.67),
            key("click" to "stroke_p", "long_click" to "3", "width" to 22.67),
            key("click" to "BackSpace", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 2: 点 折 通配
            sp,
            key("click" to "。", "long_click" to "#", "key_back_color" to 0, "width" to 16),
            key("click" to "stroke_n", "long_click" to "4", "width" to 22.66),
            key("click" to "stroke_z", "long_click" to "5", "width" to 22.67),
            key("click" to "stroke_wild", "long_click" to "Keyboard_edit", "width" to 22.67),
            key("click" to "Escape", "label" to "重输", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 3: 标点与分词
            sp,
            key("click" to "？", "long_click" to "！", "key_back_color" to 0, "width" to 16),
            key("click" to "delimiter", "label" to "分词", "width" to 22.66),
            key("click" to "：", "width" to 22.67),
            key("click" to "！", "width" to 22.67),
            key("click" to "、", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            // Row 4
            sp,
            key("click" to "Keyboard_symbols", "key_back_color" to 0, "long_click" to "Menu", "width" to 16, "height" to 50),
            key("click" to "Keyboard_number", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "space", "width" to 34),
            key("click" to "Keyboard_en", "long_click" to "IME_switch", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "Return", "width" to 16, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            sp,
        )
        return linkedMapOf(
            "name" to "笔画·五笔划",
            "type" to KeyboardLayoutResolver.TYPE_STROKE,
            "ascii_mode" to 0,
            "full_shape" to 0,
            "width" to 17,
            "height" to 55,
            "horizontal_gap" to 5,
            "vertical_gap" to 0,
            "key_press_offset_x" to 2,
            "key_press_offset_y" to 2,
            "keys" to keys,
        )
    }

    // -------------------------------------------------------------------------
    // 6. 内置英文 26 键（id: english，原 qwerty_cn2en）
    // -------------------------------------------------------------------------
    private fun buildEnglishKeyboard(): Map<String, Any> {
        val keys = listOf(
            key("click" to "q", "long_click" to "`", "swipe_up" to "1"),
            key("click" to "w", "long_click" to "@", "swipe_up" to "2"),
            key("click" to "e", "long_click" to "#", "swipe_up" to "3"),
            key("click" to "r", "long_click" to "$", "swipe_up" to "4"),
            key("click" to "t", "long_click" to "%", "swipe_up" to "5"),
            key("click" to "y", "long_click" to "^", "swipe_up" to "6"),
            key("click" to "u", "long_click" to "&", "swipe_up" to "7"),
            key("click" to "i", "long_click" to "*", "swipe_up" to "8"),
            key("click" to "o", "long_click" to "(){Left}", "swipe_up" to "9", "swipe_right" to "+"),
            key("click" to "p", "long_click" to "|", "swipe_up" to "0", "swipe_right" to "="),

            key("width" to 5),
            key("click" to "a", "long_click" to "!"),
            key("click" to "s", "long_click" to "~"),
            key("click" to "d", "long_click" to "-"),
            key("click" to "f", "long_click" to "_"),
            key("click" to "g", "long_click" to "\\"),
            key("click" to "h", "long_click" to "[]{Left}", "swipe_left" to "[", "swipe_right" to "]"),
            key("click" to "j", "long_click" to "{}{Left}", "swipe_left" to "{", "swipe_right" to "}"),
            key("click" to "k", "long_click" to ":"),
            key("click" to "l", "long_click" to ";"),
            key("width" to 5),

            key("click" to "Shift_L", "send_bindings" to false, "width" to 13, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "z", "long_click" to "?"),
            key("click" to "x", "long_click" to "cut"),
            key("click" to "c", "long_click" to "copy"),
            key("click" to "v", "long_click" to "paste"),
            key("click" to "b", "long_click" to "/"),
            key("click" to "n", "long_click" to "\""),
            key("click" to "m", "long_click" to "'"),
            key("click" to "BackSpace", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),

            key("click" to "Keyboard_number", "width" to 14, "long_click" to "Keyboard_symbols", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to ",", "long_click" to "Keyboard_edit"),
            key("click" to "space", "width" to 35),
            key("click" to ".", "long_click" to ">"),
            key("click" to "Keyboard_cn", "long_click" to "Menu", "width" to 14, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "Return", "width" to 17, "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
        )
        return linkedMapOf(
            "name" to "英文26键",
            "type" to "english",
            "ascii_mode" to 1,
            "full_shape" to 0,
            "width" to 10,
            "height" to 55,
            "key_press_offset_x" to 2,
            "key_press_offset_y" to 2,
            "keys" to keys,
        )
    }

    // -------------------------------------------------------------------------
    // 7. 内置 123 数字键盘（id: number）
    // -------------------------------------------------------------------------
    private fun buildNumberKeyboard(): Map<String, Any> {
        val sp = spacer()
        val keys = listOf(
            // Row 1：左侧透明符号列 | 运算符功能列 | 中间 1-2-3 主键区 | 右侧功能列
            sp,
            key("click" to "{}{Left}", "long_click" to "【】{Left}", "key_back_color" to 0, "height" to 55),
            key("click" to "+", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "num_1"),
            key("click" to "num_2"),
            key("click" to "num_3"),
            key("click" to "BackSpace", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),

            // Row 2
            sp,
            key("click" to "_", "long_click" to "[]{Left}", "key_back_color" to 0, "height" to 55),
            key("click" to "-", "long_click" to "%", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "num_4"),
            key("click" to "num_5"),
            key("click" to "num_6"),
            key("click" to "=", "long_click" to "≠", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),

            // Row 3
            sp,
            key("click" to "(){Left}{Keyboard_default}", "long_click" to "@", "key_back_color" to 0, "height" to 55),
            key("click" to "*", "long_click" to "±", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "num_7", "long_click" to "select_all"),
            key("click" to "num_8", "long_click" to "copy"),
            key("click" to "num_9", "long_click" to "paste"),
            key("click" to "space", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),

            // Row 4
            sp,
            key("click" to "#", "long_click" to ":", "key_back_color" to 0, "height" to 50),
            key("click" to "Keyboard_default", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            key("click" to "."),
            key("click" to 0, "long_click" to "cut"),
            key("click" to "/", "long_click" to "\\"),
            key("click" to "Return", "key_back_color" to "off_key_back_color", "key_text_color" to "off_key_text_color"),
            sp,
        )
        return linkedMapOf(
            "name" to "数字",
            "type" to "number",
            "width" to 16.66,
            "height" to 55,
            "horizontal_gap" to 5,
            "vertical_gap" to 0,
            "key_press_offset_x" to 2,
            "key_press_offset_y" to 2,
            "ascii_mode" to 1,
            "keys" to keys,
        )
    }
}
