package com.hazukieq.hkrime.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinKeyboardsTest {

    @Test
    fun builtinIdsBuildValidMaps() {
        val names = BuiltinKeyboards.allNames()
        assertEquals(listOf("number", "edit"), names)

        for (id in names) {
            assertTrue("isBuiltin($id) should be true", BuiltinKeyboards.isBuiltin(id))
            val map = BuiltinKeyboards.build(id, null)
            assertNotNull("name missing for $id", map["name"])
            val keys = map["keys"] as? List<*>
            assertNotNull("keys missing for $id", keys)
            assertTrue("keys empty for $id", keys!!.isNotEmpty())
        }
    }

    @Test
    fun canonicalAliasesAndOverridesWork() {
        assertEquals("edit", BuiltinKeyboards.canonicalName("numberb"))
        assertTrue(BuiltinKeyboards.isBuiltin("numberb"))
        assertFalse(BuiltinKeyboards.isBuiltin("jiugong_simp"))
        assertFalse(BuiltinKeyboards.isBuiltin("symbols"))
        assertFalse(BuiltinKeyboards.isBuiltin(null))

        val overrides = mapOf(
            "number" to mapOf("height" to 52, "width" to 16.5),
            "edit" to mapOf("height" to 42),
        )
        val num = BuiltinKeyboards.build("number", overrides)
        assertEquals(52, num["height"])
        assertEquals(16.5, num["width"])

        val edit = BuiltinKeyboards.build("edit", overrides)
        assertEquals(42, edit["height"])
    }

    @Test
    fun registerPresetKeysAddsDefaultsWithoutOverwritingTheme() {
        val custom = linkedMapOf<String, Map<String, String>>(
            "Keyboard_number" to mapOf("label" to "自定义123", "select" to "number"),
        )
        BuiltinKeyboards.registerPresetKeys(custom)

        assertEquals("自定义123", custom["Keyboard_number"]?.get("label"))
        assertEquals("edit", custom["Keyboard_edit"]?.get("select"))
        assertEquals("symbols", custom["Keyboard_symbols"]?.get("select"))
        assertEquals("emoji", custom["Keyboard_emoji"]?.get("select"))
        assertEquals("kaomoji", custom["Keyboard_kaomoji"]?.get("select"))
        assertEquals("9", custom["num_9"]?.get("send"))
        assertEquals("Tab", custom["Tab1"]?.get("send"))
    }
}
