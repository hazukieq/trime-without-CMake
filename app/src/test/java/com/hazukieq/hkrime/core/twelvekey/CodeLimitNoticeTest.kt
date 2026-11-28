package com.hazukieq.hkrime.core.twelvekey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLimitNoticeTest {
    @Test
    fun limitNoticeGateThrottlesRapidOverLimitKeystrokesAndResetsOnNewSession() {
        val gate = CompositionInput.LimitNoticeGate(cooldownMs = 3000L)
        assertTrue(gate.tryAcquire(1000L))
        assertFalse(gate.tryAcquire(1100L))
        assertFalse(gate.tryAcquire(3999L))
        assertTrue(gate.tryAcquire(4000L))
        assertFalse(gate.tryAcquire(4500L))

        gate.reset()
        assertTrue(gate.tryAcquire(4600L))
    }
}
