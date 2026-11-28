package com.hazukieq.hkrime.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaSpecValidatorTest {

    private fun assetsRimeDir(): File {
        val candidates = listOf(
            File("app/src/main/assets/rime"),
            File("src/main/assets/rime"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate src/main/assets/rime from ${File(".").absolutePath}")
    }

    @Test
    fun allBuiltinAssetsSchemasPassLatestSpec() {
        val dir = assetsRimeDir()
        val builtinIds = listOf(
            "hakka_jiugong",
            "hakka_qwerty",
            "rime_ice_qwerty",
            "rime_ice_t9",
        )
        for (id in builtinIds) {
            val res = SchemaSpecValidator.validateSchemaFiles(dir, null, id)
            assertTrue("Expected $id to pass, but failed: ${res.formatMessage()}", res.valid)
        }
    }

    @Test
    fun legacyHakka12KeyWithXlitAndDigitsIsRejected() {
        val legacyHakka = """
            schema:
              schema_id: hakka_jiugong
              name: "12键客家话"
            keyboard:
              type: 12key
            speller:
              alphabet: "aei5479381wy;"
              initials: "aei5479381wy"
              algebra:
                - "xlit|abcdefghijklmnopqrstuvwxyz;|aei5479381wy;|"
        """.trimIndent()
        val res = SchemaSpecValidator.validateYamlText("hakka_jiugong", legacyHakka)
        assertFalse(res.valid)
        assertTrue("Reasons should mention multi_key: ${res.reasons}", res.reasons.any { it.contains("multi_key") })
        assertTrue("Reasons should mention alphabet digits: ${res.reasons}", res.reasons.any { it.contains("alphabet") })
        assertTrue("Reasons should mention xlit: ${res.reasons}", res.reasons.any { it.contains("xlit") })
    }

    @Test
    fun legacyRimeIceT9WithDeriveDigitsIsRejected() {
        val parentQwerty = """
            keyboard:
              type: qwerty
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba'
              initials: zyxwvutsrqponmlkjihgfedcba
              algebra:
                - abbrev/^([a-z]).+$/${'$'}1/
        """.trimIndent()
        val legacyT9 = """
            __include: rime_ice_qwerty.schema.yaml:/
            keyboard:
              type: 9key
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba9876543210
              initials: zyxwvutsrqponmlkjihgfedcba9876543210
              algebra:
                - abbrev/^([a-z]).+$/${'$'}1/
                - derive/[abc]/2/
                - derive/[def]/3/
        """.trimIndent()
        val res = SchemaSpecValidator.validateYamlText("rime_ice_t9", legacyT9) { file ->
            if (file == "rime_ice_qwerty.schema.yaml") parentQwerty else null
        }
        assertFalse(res.valid)
        assertTrue("Reasons should mention multi_key: ${res.reasons}", res.reasons.any { it.contains("multi_key") })
        assertTrue("Reasons should mention alphabet digits: ${res.reasons}", res.reasons.any { it.contains("alphabet") })
        assertTrue("Reasons should mention digit mapping rule: ${res.reasons}", res.reasons.any { it.contains("数字映射") })
    }

    @Test
    fun legacySchemaWithoutExplicitKeyboardTypeIsRejected() {
        val untypedLegacy = """
            schema:
              schema_id: t9
              name: "旧式无类型方案"
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
              algebra:
                - abbrev/^([a-z]).+$/${'$'}1/
        """.trimIndent()
        val res = SchemaSpecValidator.validateYamlText("t9", untypedLegacy)
        assertFalse(res.valid)
        assertTrue("Reasons should mention keyboard/type: ${res.reasons}", res.reasons.any { it.contains("keyboard/type") })
    }

    @Test
    fun modernMultiKeySchemaPassesEvenWithBackrefsAndInheritance() {
        val parentQwerty = """
            keyboard:
              type: qwerty
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba'
              initials: zyxwvutsrqponmlkjihgfedcba
              algebra:
                - abbrev/^([a-z]).+$/${'$'}1/
        """.trimIndent()
        val modern9Key = """
            __include: rime_ice_qwerty.schema.yaml:/
            keyboard:
              type: 9key
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba'
              initials: zyxwvutsrqponmlkjihgfedcba
              multi_key: true
              algebra:
                - abbrev/^([a-z]).+$/${'$'}1/
                - derive/^([nl])ve$/${'$'}1ue/
        """.trimIndent()
        val res = SchemaSpecValidator.validateYamlText("custom_9key", modern9Key) { file ->
            if (file == "rime_ice_qwerty.schema.yaml") parentQwerty else null
        }
        assertTrue("Unexpected failure: ${res.formatMessage()}", res.valid)
    }
}
