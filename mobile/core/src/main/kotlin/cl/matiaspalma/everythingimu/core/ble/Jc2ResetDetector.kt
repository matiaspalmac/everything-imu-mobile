package cl.matiaspalma.everythingimu.core.ble

/**
 * Home short press → Yaw (on release < 1 s). Home held ≥ 1 s → Full (fires
 * once while held). Capture rising edge → Yaw. 300 ms debounce between emits.
 * Same semantics as desktop `device_traits::ResetButtonDetector`.
 */
class Jc2ResetDetector {
    enum class Reset { Yaw, Full }

    private var homePressedSinceNanos: Long = -1
    private var longFired = false
    private var capturePrev = false
    private var lastEmitNanos: Long = Long.MIN_VALUE / 2

    fun observe(home: Boolean, capture: Boolean, nowNanos: Long): Reset? {
        var out: Reset? = null
        val inDebounce = nowNanos - lastEmitNanos < DEBOUNCE_NANOS

        if (capture && !capturePrev && !inDebounce) {
            out = Reset.Yaw
            lastEmitNanos = nowNanos
        }
        capturePrev = capture

        if (home) {
            if (homePressedSinceNanos < 0) {
                homePressedSinceNanos = nowNanos
                longFired = false
            } else if (!longFired && nowNanos - homePressedSinceNanos >= LONG_NANOS && !inDebounce) {
                longFired = true
                lastEmitNanos = nowNanos
                out = Reset.Full
            }
        } else if (homePressedSinceNanos >= 0) {
            val held = nowNanos - homePressedSinceNanos
            homePressedSinceNanos = -1
            if (!longFired && held < LONG_NANOS && !inDebounce && out == null) {
                lastEmitNanos = nowNanos
                out = Reset.Yaw
            }
            longFired = false
        }
        return out
    }

    companion object {
        private const val LONG_NANOS = 1_000_000_000L
        private const val DEBOUNCE_NANOS = 300_000_000L
    }
}
