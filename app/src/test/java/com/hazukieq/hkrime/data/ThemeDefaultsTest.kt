package com.hazukieq.hkrime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDefaultsTest {

    @Test
    fun buildStyleMapOverlaysV5KeyboardAndCandidateBlocksOntoSystemDefaults() {
        val v5Theme = mapOf(
            "color_scheme" to "dark",
            "keyboard" to mapOf(
                "height" to 260,
                "round_corner" to 10,
                "text_size" to 25,
                "defaults" to mapOf("12key" to "my_12key"),
            ),
            "candidate" to mapOf(
                "height" to 32,
                "text_size" to 24,
                "comment_on_top" to false,
            ),
        )
        val flat = ThemeDefaults.buildStyleMap(v5Theme)

        // Overridden from v5 blocks
        assertEquals("dark", flat["color_scheme"])
        assertEquals(260, flat["keyboard_height"])
        assertEquals(10, flat["round_corner"])
        assertEquals(25, flat["key_text_size"])
        assertEquals(32, flat["candidate_view_height"])
        assertEquals(24, flat["candidate_text_size"])
        assertEquals(false, flat["comment_on_top"])
        assertEquals(mapOf("12key" to "my_12key"), flat["keyboard_defaults"])

        // Preserved system behavior defaults
        assertEquals("zh_CN", flat["locale"])
        assertEquals(true, flat["reset_ascii_mode"])
        assertEquals(true, flat["preedit_disable_touch"])
        assertNotNull(flat["enter_labels"])
        assertNotNull(flat["layout"])
        assertNotNull(flat["window"])
    }

    @Test
    fun defaultFallbackColorsContainNoCycles() {
        val chain = ThemeDefaults.defaultFallbackColors()
        assertTrue(chain.isNotEmpty())
        for (start in chain.keys) {
            val visited = HashSet<String>()
            var cur: String? = start
            while (cur != null && chain.containsKey(cur)) {
                assertFalse("Cycle detected at $cur", cur in visited)
                visited.add(cur)
                cur = chain[cur]
            }
        }
    }

    @Test
    fun parseFallbackColorsYamlReadsStandaloneFile() {
        val yaml = """
            # comment
            config_version: "5.0"
            fallback_colors:
              root_background: keyboard_back_color # inline comment
              key_border_color: border_color
        """.trimIndent()
        val parsed = ThemeDefaults.parseFallbackColorsYaml(yaml)
        assertEquals(
            mapOf(
                "root_background" to "keyboard_back_color",
                "key_border_color" to "border_color",
            ),
            parsed,
        )
    }
}
