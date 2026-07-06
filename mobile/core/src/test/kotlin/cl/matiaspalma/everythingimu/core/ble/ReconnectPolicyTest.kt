package cl.matiaspalma.everythingimu.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun reconnectsOnInvoluntaryDropWithBudgetLeft() {
        for (attempt in 0 until 5) {
            assertTrue(
                "attempt $attempt should reconnect",
                ReconnectPolicy.shouldReconnect(intentional = false, attempts = attempt, maxAttempts = 5),
            )
        }
    }

    @Test
    fun givesUpOnceBudgetExhausted() {
        assertFalse(ReconnectPolicy.shouldReconnect(intentional = false, attempts = 5, maxAttempts = 5))
        assertFalse(ReconnectPolicy.shouldReconnect(intentional = false, attempts = 6, maxAttempts = 5))
    }

    @Test
    fun neverReconnectsOnIntentionalDisconnect() {
        assertFalse(ReconnectPolicy.shouldReconnect(intentional = true, attempts = 0, maxAttempts = 5))
        assertFalse(ReconnectPolicy.shouldReconnect(intentional = true, attempts = 4, maxAttempts = 5))
    }

    @Test
    fun backoffIsExponentialAndCapped() {
        assertEquals(1_000L, ReconnectPolicy.backoffMillis(0))
        assertEquals(2_000L, ReconnectPolicy.backoffMillis(1))
        assertEquals(4_000L, ReconnectPolicy.backoffMillis(2))
        assertEquals(8_000L, ReconnectPolicy.backoffMillis(3))
        assertEquals(16_000L, ReconnectPolicy.backoffMillis(4))
        // Out-of-range index is clamped, never overflows the shift.
        assertEquals(16_000L, ReconnectPolicy.backoffMillis(5))
        assertEquals(1_000L, ReconnectPolicy.backoffMillis(-1))
    }
}
