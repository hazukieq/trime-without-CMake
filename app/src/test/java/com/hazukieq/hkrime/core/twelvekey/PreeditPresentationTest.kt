package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class PreeditPresentationTest {
    companion object {
        @JvmStatic @BeforeClass fun load() { OfflineDictionary.load() }
    }
    private fun frame(code: String, text: String, reading: String, raw: String = code + "\u2038") =
        TwelveKeySnapshot(code, 0, code, emptyList(), null,
            barEntries = listOf(CandidateItem(text, reading, 0)), previewText = text, inlineText = text,
            rawPreedit = raw, contextKey = code)

    @Test fun panelOpeningDoesNotChangeDisplayedPreeditOrSelectedPrefix() {
        val bar = frame("i4", "哥", "go")
        assertTrue(PreeditPresentation.publish(bar, "太"))
        assertEquals("太go", PreeditPresentation.render("i4\u2038", "i4", "好", "hau"))
        assertFalse(PreeditPresentation.publish(bar.copy(groups = listOf(
            SyllableGroup("go", listOf("go"), bar.barItems))), "太"))
        assertEquals("太哥", PreeditPresentation.preview("i4\u2038", "i4"))
    }

    @Test fun stalePreferredCommentCannotRenderDifferentInputOrNativeSelection() {
        PreeditPresentation.publish(frame("9ai", "太", "tai"), "")
        assertEquals("go", PreeditPresentation.render("i4\u2038", "i4", "哥", "go"))
        // 即使原碼不變，原生選中前綴/分段已不同，也不能用舊首候選。
        assertEquals("太go", PreeditPresentation.render("太i4\u2038", "9ai", "哥", "go"))
    }

    @Test fun pendingSurvivesEmptyRawFrameButCancelClearsIt() {
        PreeditPresentation.invalidate("太")
        assertEquals("太", PreeditPresentation.render("\u2038", ""))
        assertEquals("太", PreeditPresentation.render(null, ""))
        PreeditPresentation.invalidate("")
        assertNull(PreeditPresentation.render(null, ""))
    }

    @Test fun preferredFrameIsPublishedAtomicallyAndInvalidatedBeforeNewKeys() {
        PreeditPresentation.publish(frame("i4", "哥", "go"), "太")
        PreeditPresentation.invalidate("太")
        assertEquals("太ho", PreeditPresentation.render("i4\u2038", "i4", "和", "ho"))
        assertTrue(PreeditPresentation.preview("8i\u2038", "8i").startsWith("太"))
    }
}
