package cl.matiaspalma.everythingimu.core.ble

/**
 * Home short press → Yaw (on release < 1 s). Home held ≥ 1 s → Full (fires
 * once while still held). Capture rising edge → Yaw, emitted and returned
 * before the Home branch so a co-incident Home long-press in the same call
 * cannot overwrite (drop) it. 300 ms debounce between emits.
 *
 * NOTE: desktop `device_traits::ResetButtonDetector` decides Full-vs-Yaw only
 * on the Home *release* edge, whereas this detector fires Full while the button
 * is still held, so the long-press timing is not byte-identical to desktop.
 */
class Jc2ResetDetector {
    enum class Reset { Yaw, Full }

    private var homePressedSinceNanos: Long = -1
    private var longFired = false
    private var capturePrev = false
    private var lastEmitNanos: Long = Long.MIN_VALUE / 2

    fun observe(home: Boolean, capture: Boolean, nowNanos: Long): Reset? {
        val inDebounce = nowNanos - lastEmitNanos < DEBOUNCE_NANOS

        // Capture button: Yaw on the press edge only. Return immediately after
        // emitting so a co-incident Home event in the same call cannot reuse the
        // (now stale) inDebounce and overwrite this Yaw with a Full.
        val captureEdge = capture && !capturePrev
        capturePrev = capture
        if (captureEdge) {
            if (inDebounce) return null
            lastEmitNanos = nowNanos
            return Reset.Yaw
        }

        if (home) {
            if (homePressedSinceNanos < 0) {
                homePressedSinceNanos = nowNanos
                longFired = false
            } else if (!longFired && nowNanos - homePressedSinceNanos >= LONG_NANOS && !inDebounce) {
                longFired = true
                lastEmitNanos = nowNanos
                return Reset.Full
            }
        } else if (homePressedSinceNanos >= 0) {
            val held = nowNanos - homePressedSinceNanos
            homePressedSinceNanos = -1
            val alreadyFired = longFired
            longFired = false
            if (!alreadyFired && held < LONG_NANOS && !inDebounce) {
                lastEmitNanos = nowNanos
                return Reset.Yaw
            }
        }
        return null
    }

    companion object {
        private const val LONG_NANOS = 1_000_000_000L
        private const val DEBOUNCE_NANOS = 300_000_000L
    }
}
