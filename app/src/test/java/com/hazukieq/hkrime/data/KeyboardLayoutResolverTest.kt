package com.hazukieq.hkrime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutResolverTest {

    private val typedPresets: Map<String, Map<String, Any>> = linkedMapOf(
        "letter" to mapOf("name" to "26键·标准拼音", "type" to "qwerty"),
        "qwerty_cn2en" to mapOf("name" to "26键·中英混排", "type" to "qwerty"),
        "jiugong_simp" to mapOf("name" to "12键·标准客语", "type" to "12key"),
        "giugiung_simp" to mapOf("name" to "12键·传统布局", "type" to "12key"),
        "qiugong_simph" to mapOf("name" to "9键·通用拼音", "type" to "9key"),
        "number" to mapOf("name" to "数字键盘"),
        "default" to mapOf("name" to "默认", "import_preset" to "letter"),
    )

    private val themeDefaults = mapOf(
        "qwerty" to "letter",
        "12key" to "jiugong_simp",
        "9key" to "qiugong_simph",
    )

    @Test
    fun missingSchemaTypeDefaultsToQwerty() {
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaId = "rime_ice",
            schemaType = null,
            schemaDefaultLayout = null,
            userSelectedLayout = null,
            themeDefaults = themeDefaults,
            presetKeyboards = typedPresets,
        )
        assertEquals("letter", resolved)
    }

    @Test
    fun twelveKeySchemaResolvesToTwelveKeyDefaultAndForbidsQwertyOverride() {
        // 用戶偏好裡如果誤填了 26 鍵布局，必須被強類型攔截並安全回落到 12 鍵默認！
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaId = "hakka_jiugong",
            schemaType = "12key",
            schemaDefaultLayout = null,
            userSelectedLayout = "letter",
            themeDefaults = themeDefaults,
            presetKeyboards = typedPresets,
        )
        assertEquals("jiugong_simp", resolved)
        assertFalse(KeyboardLayoutResolver.isCompatible("letter", "12key", typedPresets))
        assertTrue(KeyboardLayoutResolver.isCompatible("giugiung_simp", "12key", typedPresets))
    }

    @Test
    fun userCanSwitchBetweenCompatibleTwelveKeyLayouts() {
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaId = "hakka_jiugong",
            schemaType = "12key",
            schemaDefaultLayout = "jiugong_simp",
            userSelectedLayout = "giugiung_simp",
            themeDefaults = themeDefaults,
            presetKeyboards = typedPresets,
        )
        assertEquals("giugiung_simp", resolved)
    }

    @Test
    fun userCanSwitchBetweenCompatibleQwertyLayouts() {
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaId = "hakka_qwerty",
            schemaType = "qwerty",
            schemaDefaultLayout = null,
            userSelectedLayout = "qwerty_cn2en",
            themeDefaults = themeDefaults,
            presetKeyboards = typedPresets,
        )
        assertEquals("qwerty_cn2en", resolved)
    }

    @Test
    fun availableLayoutsStrictlyFiltersByTypeAndExcludesUtilityKeyboards() {
        val twelveKey = KeyboardLayoutResolver.availableLayouts("12key", typedPresets)
        assertEquals(listOf("jiugong_simp", "giugiung_simp"), twelveKey.map { it.id })

        val qwerty = KeyboardLayoutResolver.availableLayouts("qwerty", typedPresets)
        assertEquals(listOf("letter", "qwerty_cn2en"), qwerty.map { it.id })
    }

    @Test
    fun unknownUserOverrideAfterThemeSwitchFallsBackSafely() {
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaId = "hakka_jiugong",
            schemaType = "12key",
            schemaDefaultLayout = null,
            userSelectedLayout = "some_other_theme_12key",
            themeDefaults = themeDefaults,
            presetKeyboards = typedPresets,
        )
        assertEquals("jiugong_simp", resolved)
    }

    @Test
    fun legacyUntypedThemeFallsBackToSchemaIdMatching() {
        val legacyPresets: Map<String, Map<String, Any>> = linkedMapOf(
            "hakka_jiugong" to mapOf("name" to "旧12键"),
            "default" to mapOf("name" to "旧默认"),
        )
        val resolved = KeyboardLayoutResolver.resolveDefaultLayout(
            schemaId = "hakka_jiugong",
            schemaType = "12key",
            schemaDefaultLayout = null,
            userSelectedLayout = null,
            themeDefaults = null,
            presetKeyboards = legacyPresets,
        )
        assertEquals("hakka_jiugong", resolved)
    }
}
