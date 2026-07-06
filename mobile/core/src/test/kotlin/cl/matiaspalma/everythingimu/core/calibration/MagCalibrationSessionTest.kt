package cl.matiaspalma.everythingimu.core.calibration

import cl.matiaspalma.everythingimu.core.sensors.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host-JVM tests for the session's buffer + native-fit fallback wiring. Every
 * native interaction is injected via the constructor so these run deterministically
 * without `libjni_android.so` on the classpath.
 */
class MagCalibrationSessionTest {

    private fun v(x: Float, y: Float, z: Float) = Vec3(x, y, z)

    /**
     * Feed an asymmetric bounding box (non-zero hard-iron offset, equal per-axis
     * spread) padded to `count` samples so the bounding-box calibrator's
     * finish() gate (>= 32 samples) is satisfied. Midpoint is (20, -10, 25).
     */
    private fun feedAsymmetricCube(session: MagCalibrationSession, count: Int) {
        val corners = listOf(
            v(0f, -30f, 5f), v(0f, -30f, 45f), v(0f, 10f, 5f), v(0f, 10f, 45f),
            v(40f, -30f, 5f), v(40f, -30f, 45f), v(40f, 10f, 5f), v(40f, 10f, 45f),
        )
        corners.forEach { session.feed(it) }
        val mid = v(20f, -10f, 25f)
        repeat((count - corners.size).coerceAtLeast(0)) { session.feed(mid) }
    }

    @Test
    fun startClearsRetainedSampleBuffer() {
        var lastFit: FloatArray? = null
        val session = MagCalibrationSession(
            minSpreadTarget = 10f,
            minSamples = 3,
            nativeAvailable = { true },
            nativeFit = { lastFit = it; null },
        )
        session.start()
        repeat(5) { session.feed(v(it.toFloat(), it.toFloat(), it.toFloat())) }
        // Restart: the 5 samples above must not survive.
        session.start()
        session.feed(v(0f, 0f, 0f))
        session.feed(v(20f, 20f, 20f))
        session.feed(v(10f, 10f, 10f))
        session.accept()
        assertNotNull(lastFit)
        // Only the 3 post-restart samples reached the native fit (3 * 3 floats).
        assertEquals(9, lastFit!!.size)
    }

    @Test
    fun discardClearsRetainedSampleBuffer() {
        var lastFit: FloatArray? = null
        val session = MagCalibrationSession(
            minSpreadTarget = 10f,
            minSamples = 3,
            nativeAvailable = { true },
            nativeFit = { lastFit = it; null },
        )
        session.start()
        repeat(5) { session.feed(v(it.toFloat(), it.toFloat(), it.toFloat())) }
        session.discard()
        session.start()
        session.feed(v(0f, 0f, 0f))
        session.feed(v(20f, 20f, 20f))
        session.feed(v(10f, 10f, 10f))
        session.accept()
        assertEquals(9, lastFit!!.size)
    }

    @Test
    fun acceptReturnsNullBeforeCompletionCriteriaMet() {
        // A handful of tight samples: below both the spread and sample-count floors.
        val session = MagCalibrationSession(nativeAvailable = { false })
        session.start()
        repeat(5) { session.feed(v(1f, 1f, 1f)) }
        assertNull(session.accept())
    }

    @Test
    fun acceptFallsBackToBoundingBoxWhenNativeUnavailable() {
        val session = MagCalibrationSession(
            minSpreadTarget = 10f,
            minSamples = 32,
            nativeAvailable = { false },
        )
        session.start()
        feedAsymmetricCube(session, 40)
        val result = session.accept()
        assertNotNull(result)
        assertFalse(result!!.isIdentity)
        // Bounding-box hard-iron offset is the min/max midpoint.
        assertEquals(20f, result.offset.x, 1e-3f)
        assertEquals(-10f, result.offset.y, 1e-3f)
        assertEquals(25f, result.offset.z, 1e-3f)
    }

    @Test
    fun acceptUsesNativeFitWhenAvailable() {
        val session = MagCalibrationSession(
            minSpreadTarget = 10f,
            minSamples = 3,
            nativeAvailable = { true },
            nativeFit = { floatArrayOf(1f, 2f, 3f, 1f, 1f, 1f) },
        )
        session.start()
        session.feed(v(0f, 0f, 0f))
        session.feed(v(20f, 20f, 20f))
        session.feed(v(10f, 10f, 10f))
        val result = session.accept()
        assertNotNull(result)
        assertEquals(Vec3(1f, 2f, 3f), result!!.offset)
        assertEquals(Vec3(1f, 1f, 1f), result.scale)
    }

    @Test
    fun acceptFallsBackWhenNativeRejects() {
        // Native present but the wizard rejects the cloud (returns null): the
        // session must fall back to the bounding-box fit, not surface null.
        val session = MagCalibrationSession(
            minSpreadTarget = 10f,
            minSamples = 32,
            nativeAvailable = { true },
            nativeFit = { null },
        )
        session.start()
        feedAsymmetricCube(session, 40)
        val result = session.accept()
        assertNotNull(result)
        assertEquals(20f, result!!.offset.x, 1e-3f)
    }
}
