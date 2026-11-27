package com.hazukieq.hkrime.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipboardStoreTest {

    @Test
    fun recordsDeduplicatesPinsAndRespectsUnpinnedCap() {
        val store = ClipboardStore(storageFile = null, maxUnpinned = 3)
        assertTrue(store.record("hello", now = 100L))
        assertTrue(store.record("world", now = 200L))
        assertFalse(store.record("   ", now = 250L))
        assertEquals(listOf("world", "hello"), store.entries().map { it.text })

        // Re-copying promotes to top without duplicating
        assertTrue(store.record("hello", now = 300L))
        assertEquals(listOf("hello", "world"), store.entries().map { it.text })

        // Pin "world" -> stays at top even when newer unpinned entries arrive
        val worldId = store.entries().first { it.text == "world" }.id
        assertTrue(store.togglePin(worldId))
        assertEquals(listOf("world", "hello"), store.entries().map { it.text })

        // Add 3 more unpinned entries -> cap of 3 unpinned evicts oldest unpinned ("hello"), keeps pinned "world"
        store.record("a", now = 400L)
        store.record("b", now = 500L)
        store.record("c", now = 600L)
        assertEquals(listOf("world", "c", "b", "a"), store.entries().map { it.text })

        // Clear unpinned keeps pinned entries intact
        assertTrue(store.clearUnpinned())
        assertEquals(listOf("world"), store.entries().map { it.text })
        assertFalse(store.hasUnpinned())
    }

    @Test
    fun persistsAndReloadsFromJsonFile() {
        val tmp = File.createTempFile("clip_store_test", ".json")
        try {
            tmp.delete()
            val s1 = ClipboardStore(storageFile = tmp, maxUnpinned = 10)
            s1.record("first", now = 10L)
            s1.record("second", now = 20L)
            val firstId = s1.entries().first { it.text == "first" }.id
            s1.togglePin(firstId)

            val s2 = ClipboardStore(storageFile = tmp, maxUnpinned = 10)
            val loaded = s2.entries()
            assertEquals(listOf("first", "second"), loaded.map { it.text })
            assertTrue(loaded[0].pinned)
            assertFalse(loaded[1].pinned)
        } finally {
            tmp.delete()
        }
    }
}
