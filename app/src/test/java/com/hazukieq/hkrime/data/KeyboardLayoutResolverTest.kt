package com.hazukieq.hkrime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutResolverTest {

    @Test
    fun builtinStandardTypesResolveWithoutAnyThemeKeyboards() {
        val defaults = ThemeDefaults.systemStyleDefaults()["keyboard_defaults"] as Map<*, *>

        assertEquals(
            "jiugong_simp",
            KeyboardLayoutResolver.resolveDefaultLayout("12key", null, null, defaults, emptyMap()),
        )
        assertEquals(
            "letter",
            KeyboardLayoutResolver.resolveDefaultLayout("qwerty", null, null, defaults, emptyMap()),
        )
        assertEquals(
            "pinyin_9key",
            KeyboardLayoutResolver.resolveDefaultLayout("9key", null, null, defaults, emptyMap()),
        )
        assertEquals(
            "wubi_qwerty",
            KeyboardLayoutResolver.resolveDefaultLayout("wubi", null, null, defaults, emptyMap()),
        )
        assertEquals(
            "stroke_9key",
            KeyboardLayoutResolver.resolveDefaultLayout("stroke", null, null, defaults, emptyMap()),
        )
    }

    @Test
    fun missingSchemaTypeDefaultsToQwerty() {
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaType = null,
            schemaDefaultLayout = null,
            userSelectedLayout = null,
            themeDefaults = null,
            customKeyboards = null,
        )
        assertEquals("letter", resolved)
    }

    @Test
    fun twelveKeySchemaForbidsQwertyUserOverride() {
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaType = "12key",
            schemaDefaultLayout = null,
            userSelectedLayout = "letter",
            themeDefaults = null,
            customKeyboards = null,
        )
        assertEquals("jiugong_simp", resolved)
        assertFalse(KeyboardLayoutResolver.isCompatible("letter", "12key", null))
        assertTrue(KeyboardLayoutResolver.isCompatible("jiugong_simp", "12key", null))
    }

    @Test
    fun filterCustomKeyboardsRejectsUntypedMessyLayoutsAndAllowsTypedOrUtilityOverrides() {
        val raw: Map<String, Map<String, Any>> = linkedMapOf(
            "messy_no_type" to mapOf("name" to "无类型杂乱键盘"),
            "english" to mapOf("name" to "自定义英文"),
            "my_12key" to mapOf("name" to "自定义12键", "type" to "12key"),
            "my_chord" to mapOf("name" to "自创并击", "type" to "chord"),
        )
        val filtered = KeyboardLayoutResolver.filterCustomKeyboards(raw)
        assertFalse(filtered.containsKey("messy_no_type"))
        assertTrue(filtered.containsKey("english"))
        assertTrue(filtered.containsKey("my_12key"))
        assertTrue(filtered.containsKey("my_chord"))

        val twelveKey = KeyboardLayoutResolver.availableLayouts("12key", filtered)
        assertEquals(listOf("jiugong_simp", "my_12key"), twelveKey.map { it.id })

        val chord = KeyboardLayoutResolver.availableLayouts("chord", filtered)
        assertEquals(listOf("my_chord"), chord.map { it.id })
        assertEquals(
            "my_chord",
            KeyboardLayoutResolver.resolveDefaultLayout("chord", null, null, null, filtered),
        )
    }

    @Test
    fun customKeyboardCanOverrideBuiltinSameIdAndKeepType() {
        val custom: Map<String, Map<String, Any>> = linkedMapOf(
            "jiugong_simp" to mapOf("name" to "12键·自定义版", "type" to "12key"),
        )
        val twelveKey = KeyboardLayoutResolver.availableLayouts("12key", custom)
        assertEquals(1, twelveKey.size)
        assertEquals("jiugong_simp", twelveKey[0].id)
        assertEquals("12键·自定义版", twelveKey[0].name)
    }

    @Test
    fun inferSchemaTypeMatchesRimeIceAndCommonThirdPartySchemas() {
        assertEquals("9key", KeyboardLayoutResolver.inferSchemaType("t9", null))
        assertEquals("9key", KeyboardLayoutResolver.inferSchemaType("rime_ice_t9", null))
        assertEquals("qwerty", KeyboardLayoutResolver.inferSchemaType("rime_ice", null))
        assertEquals("qwerty", KeyboardLayoutResolver.inferSchemaType("radical_pinyin", null))
        assertEquals("qwerty", KeyboardLayoutResolver.inferSchemaType("melt_eng", null))
        assertEquals("qwerty", KeyboardLayoutResolver.inferSchemaType("ipa_yunlong", null))
        assertEquals("wubi", KeyboardLayoutResolver.inferSchemaType("wubi86", null))
        assertEquals("stroke", KeyboardLayoutResolver.inferSchemaType("stroke", null))
        assertEquals("12key", KeyboardLayoutResolver.inferSchemaType("hakka_jiugong", null))
        // Explicit keyboard/type always overrides schemaId heuristic
        assertEquals("custom_type", KeyboardLayoutResolver.inferSchemaType("t9", "custom_type"))
    }

    @Test
    fun capabilitiesUnifyAllNonQwertyPreeditParadigm() {
        assertFalse(KeyboardLayoutResolver.capabilitiesOf("qwerty").materializePreeditFromCandidate)
        assertFalse(KeyboardLayoutResolver.capabilitiesOf("wubi").materializePreeditFromCandidate)
        assertTrue(KeyboardLayoutResolver.capabilitiesOf("12key").materializePreeditFromCandidate)
        assertTrue(KeyboardLayoutResolver.capabilitiesOf("12key").useTwelveKeySession)
        assertTrue(KeyboardLayoutResolver.capabilitiesOf("9key").materializePreeditFromCandidate)
        assertTrue(KeyboardLayoutResolver.capabilitiesOf("9key").useTwelveKeySession)
        assertTrue(KeyboardLayoutResolver.capabilitiesOf("stroke").materializePreeditFromCandidate)
        // Future custom non-26-key types inherit the same paradigm automatically
        assertTrue(KeyboardLayoutResolver.capabilitiesOf("14key_custom").materializePreeditFromCandidate)
    }

    @Test
    fun validateAndFilterThemeRejectsLegacyMultiKeyDigitsAndAcceptsAtoms() {
        val legacyPresets: Map<String, Map<String, Any>> = linkedMapOf(
            "t9_2" to mapOf("label" to "ABC", "send" to "2", "functional" to "false"),
            "old_code_5" to mapOf("label" to "kl", "send" to "5"),
            "modern_th" to mapOf("label" to "th", "atoms" to listOf("th"), "functional" to "false"),
        )
        val rawKeyboards: Map<String, Map<String, Any>> = linkedMapOf(
            "legacy_t9_click_digit" to mapOf(
                "name" to "旧9键裸数字",
                "type" to "9key",
                "keys" to listOf(mapOf("click" to "2")),
            ),
            "legacy_12key_preset_send" to mapOf(
                "name" to "旧12键占位发送",
                "type" to "12key",
                "keys" to listOf(mapOf("click" to "old_code_5")),
            ),
            "modern_custom_12key" to mapOf(
                "name" to "新规范12键",
                "type" to "12key",
                "keys" to listOf(
                    mapOf("click" to "modern_th"),
                    mapOf("label" to "abc", "atoms" to listOf("a", "b", "c"), "long_click" to "2"),
                    mapOf("click" to "BackSpace"),
                ),
            ),
        )

        val result = KeyboardLayoutResolver.validateAndFilterTheme(rawKeyboards, legacyPresets)
        assertFalse("legacy t9_2 preset without atoms should be rejected", result.presetKeys.containsKey("t9_2"))
        assertTrue("modern_th preset with atoms should pass", result.presetKeys.containsKey("modern_th"))
        assertFalse(result.keyboards.containsKey("legacy_t9_click_digit"))
        assertFalse(result.keyboards.containsKey("legacy_12key_preset_send"))
        assertTrue(result.keyboards.containsKey("modern_custom_12key"))
        assertTrue(result.rejectedReasons.size >= 3)
    }

    @Test
    fun fullTemplate12KeyAnd9KeyThemeDefinitionsPassValidation() {
        val full12KeyKeys = listOf(
            mapOf("key_back_color" to 0, "width" to 100, "height" to 7),
            mapOf("click" to "，", "long_click" to "-", "width" to 16),
            mapOf("label" to "nz", "atoms" to listOf("nz"), "long_click" to "@"),
            mapOf("label" to "qt", "atoms" to listOf("q", "t"), "long_click" to "`"),
            mapOf("label" to "abc", "atoms" to listOf("a", "b", "c"), "long_click" to "Keyboard_edit"),
            mapOf("label" to "def", "atoms" to listOf("d", "e", "f"), "long_click" to "~"),
            mapOf("click" to "BackSpace", "width" to 16),
            mapOf("click" to "。", "long_click" to "#", "width" to 16),
            mapOf("label" to "ng", "atoms" to listOf("ng"), "long_click" to "'"),
            mapOf("label" to "ghi", "atoms" to listOf("g", "h", "i"), "long_click" to "="),
            mapOf("label" to "kl", "atoms" to listOf("k", "l"), "long_click" to "_"),
            mapOf("label" to "mno", "atoms" to listOf("m", "n", "o"), "long_click" to "*"),
            mapOf("click" to "/", "width" to 16),
            mapOf("click" to "？", "long_click" to "！", "width" to 16),
            mapOf("label" to "th", "atoms" to listOf("th"), "long_click" to "copy"),
            mapOf("label" to "ps", "atoms" to listOf("p", "s"), "long_click" to "paste"),
            mapOf("label" to "vur", "atoms" to listOf("v", "u", "r"), "long_click" to "cut"),
            mapOf("label" to "zj", "atoms" to listOf("z", "j"), "long_click" to "[]{Left}"),
            mapOf("click" to "、", "width" to 16),
            mapOf("click" to "Keyboard_bqrw", "long_click" to "Menu", "width" to 16),
            mapOf("click" to "Keyboard_number"),
            mapOf("click" to "space", "width" to 34),
            mapOf("click" to "Keyboard_en", "composing" to "delimiter", "long_click" to "IME_switch"),
            mapOf("click" to "Return", "width" to 16),
        )
        val full9Presets = linkedMapOf<String, Map<String, Any>>(
            "custom_t9_1" to mapOf("label" to "@分词", "text" to "@", "functional" to "false"),
            "custom_t9_2" to mapOf("label" to "ABC", "atoms" to listOf("a", "b", "c"), "functional" to "false"),
            "custom_t9_3" to mapOf("label" to "DEF", "atoms" to listOf("d", "e", "f"), "functional" to "false"),
            "custom_t9_4" to mapOf("label" to "GHI", "atoms" to listOf("g", "h", "i"), "functional" to "false"),
            "custom_t9_5" to mapOf("label" to "JKL", "atoms" to listOf("j", "k", "l"), "functional" to "false"),
            "custom_t9_6" to mapOf("label" to "MNO", "atoms" to listOf("m", "n", "o"), "functional" to "false"),
            "custom_t9_7" to mapOf("label" to "PQRS", "atoms" to listOf("p", "q", "r", "s"), "functional" to "false"),
            "custom_t9_8" to mapOf("label" to "TUV", "atoms" to listOf("t", "u", "v"), "functional" to "false"),
            "custom_t9_9" to mapOf("label" to "WXYZ", "atoms" to listOf("w", "x", "y", "z"), "functional" to "false"),
        )
        val full9KeyKeys = listOf(
            mapOf("click" to "，", "long_click" to "-", "width" to 16),
            mapOf("click" to "custom_t9_1", "composing" to "delimiter", "long_click" to "1", "width" to 22.66),
            mapOf("click" to "custom_t9_2", "long_click" to "2", "width" to 22.67),
            mapOf("click" to "custom_t9_3", "long_click" to "3", "width" to 22.67),
            mapOf("click" to "BackSpace", "width" to 16),
            mapOf("click" to "。", "long_click" to "#", "width" to 16),
            mapOf("click" to "custom_t9_4", "long_click" to "4", "width" to 22.66),
            mapOf("click" to "custom_t9_5", "long_click" to "Keyboard_edit", "width" to 22.67),
            mapOf("click" to "custom_t9_6", "long_click" to "6", "width" to 22.67),
            mapOf("click" to "Escape", "label" to "重输", "width" to 16),
            mapOf("click" to "？", "long_click" to "！", "width" to 16),
            mapOf("click" to "custom_t9_7", "long_click" to "7", "width" to 22.66),
            mapOf("click" to "custom_t9_8", "long_click" to "8", "width" to 22.67),
            mapOf("click" to "custom_t9_9", "long_click" to "9", "width" to 22.67),
            mapOf("click" to "、", "width" to 16),
            mapOf("click" to "Keyboard_symbols", "long_click" to "Menu", "width" to 16),
            mapOf("click" to "Keyboard_number", "width" to 17),
            mapOf("click" to "space", "width" to 34),
            mapOf("click" to "Keyboard_en", "long_click" to "IME_switch", "width" to 17),
            mapOf("click" to "Return", "width" to 16),
        )
        val rawKeyboards = linkedMapOf<String, Map<String, Any>>(
            "my_custom_12key" to mapOf("name" to "自定义12键客语", "type" to "12key", "keys" to full12KeyKeys),
            "my_custom_9key" to mapOf("name" to "自定义9键拼音", "type" to "9key", "keys" to full9KeyKeys),
        )

        val result = KeyboardLayoutResolver.validateAndFilterTheme(rawKeyboards, full9Presets)
        assertTrue("Unexpected rejections: ${result.rejectedReasons}", result.rejectedReasons.isEmpty())
        assertTrue(result.keyboards.containsKey("my_custom_12key"))
        assertTrue(result.keyboards.containsKey("my_custom_9key"))
        assertEquals(9, result.presetKeys.size)
    }
}
