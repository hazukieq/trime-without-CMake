package com.hazukieq.hkrime.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinKeyboardsTest {

    @Test
    fun allStandardAndUtilityBuiltinsBuildValidMaps() {
        assertEquals(listOf("english", "number"), BuiltinKeyboards.utilityNames())
        assertEquals(
            listOf("jiugong_simp", "letter", "pinyin_9key", "wubi_qwerty", "stroke_9key"),
            BuiltinKeyboards.typingMetadata().map { it.id },
        )

        for (id in BuiltinKeyboards.allNames()) {
            assertTrue("isBuiltin($id) should be true", BuiltinKeyboards.isBuiltin(id))
            val map = BuiltinKeyboards.build(id)
            assertNotNull("name missing for $id", map["name"])
            assertNotNull("type missing for $id", map["type"])
            val keys = map["keys"] as? List<*>
            assertNotNull("keys missing for $id", keys)
            assertTrue("keys empty for $id", keys!!.isNotEmpty())
        }
    }

    @Test
    fun canonicalAliasesMapLegacyIdsCleanly() {
        assertEquals("english", BuiltinKeyboards.canonicalName("qwerty_cn2en"))
        assertTrue(BuiltinKeyboards.isUtility("qwerty_cn2en"))
        assertTrue(BuiltinKeyboards.isUtility("english"))
        assertTrue(BuiltinKeyboards.isUtility("number"))
        assertFalse(BuiltinKeyboards.isUtility("jiugong_simp"))
        assertFalse(BuiltinKeyboards.isBuiltin("symbols"))
        assertFalse(BuiltinKeyboards.isBuiltin(null))
    }

    @Test
    fun registerPresetKeysAddsAllCoreKeysWithoutOverwritingTheme() {
        val custom = linkedMapOf<String, Map<String, String>>(
            "Keyboard_number" to mapOf("label" to "自定义123", "select" to "number"),
        )
        BuiltinKeyboards.registerPresetKeys(custom)

        assertEquals("自定义123", custom["Keyboard_number"]?.get("label"))
        assertEquals("english", custom["Keyboard_en"]?.get("select"))
        assertEquals("edit", custom["Keyboard_edit"]?.get("select"))
        assertEquals("symbols", custom["Keyboard_symbols"]?.get("select"))
        assertEquals("ng", custom["letter_1"]?.get("label"))
        assertEquals("ABC", custom["t9_2"]?.get("label"))
        assertEquals("一", custom["stroke_h"]?.get("label"))
        assertEquals("9", custom["num_9"]?.get("send"))
    }
}
