package com.hazukieq.hkrime.core.twelvekey

import com.hazukieq.hkrime.core.PackedCandidates
import com.hazukieq.hkrime.ime.hardware.HardwareEnginePage
import com.hazukieq.hkrime.ime.text.Candidate
import com.hazukieq.hkrime.ime.text.CandidatePanelAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenccModifiedFlagTest {
    @Test
    fun packedRecordsAndSnapshotModelsPreserveOpenccModifiedFlag() {
        val rows = PackedCandidates.parse(
            "其实吧\u0001ki sit ba\u00010\u00017\u0001ki sit ba\u00010\u00010\u0002" +
                "其係实吧\u0001ki sit ba\u00010\u00017\u0001ki sit ba\u00011\u00011\u0002",
        )
        assertEquals(2, rows.size)
        assertEquals("0", rows[0][6])
        assertEquals("1", rows[1][6])

        val local = TwelveKeySnapshotBuilder.Local(
            input = "kisitba",
            consumedEnd = 0,
            remaining = "kisitba",
            segmentsKey = "",
            rawPreedit = "ki sit ba",
        )
        val snap = GenericPanelSnapshotBuilder.build(
            local = local,
            candidatesOverride = listOf(
                CandidateItem("其实吧", "ki sit ba", 0, openccModified = false),
                CandidateItem("其係实吧", "ki sit ba", 1, openccModified = true),
            ),
        )
        assertFalse(snap.barEntries[0].openccModified)
        assertTrue(snap.barEntries[1].openccModified)
        assertFalse(snap.recommend[0].openccModified)
        assertTrue(snap.recommend[1].openccModified)

        assertFalse(Candidate.BarEntry("其实吧", "ki sit ba").openccModified)
        assertTrue(Candidate.BarEntry("其係实吧", "ki sit ba", true).openccModified)
        assertFalse(CandidatePanelAdapter.Item("其实吧", "ki sit ba", 0).openccModified)
        assertTrue(CandidatePanelAdapter.Item("其係实吧", "ki sit ba", 1, openccModified = true).openccModified)
        assertFalse(HardwareEnginePage.Item("其实吧", "ki sit ba").openccModified)
        assertTrue(HardwareEnginePage.Item("其係实吧", "ki sit ba", openccModified = true).openccModified)
    }
}
