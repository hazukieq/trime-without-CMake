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
}
