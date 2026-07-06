package cl.matiaspalma.everythingimu.core.calibration

import cl.matiaspalma.everythingimu.core.sensors.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives a figure-8 magnetometer calibration session. The UI starts the
 * session, then forwards every raw mag sample through [feed] until
 * [progress] reports `complete`. UI calls [accept] to persist or [discard]
 * to bail.
 */
class MagCalibrationSession(
    /** µT spread required on each axis before we consider coverage complete. */
    private val minSpreadTarget: Float = 30f,
    /** Floor on sample count to avoid converging on a small noisy bubble. */
    private val minSamples: Int = 256,
    /**
     * Cap on retained raw samples fed to the native fit. Bounds memory over a
     * long session; the newest samples are kept once the cap is reached. Matches
     * the wizard's own internal cap so nothing is silently dropped native-side.
     */
    private val maxSamples: Int = 4096,
    /** Whether the canonical native sphere-fit backend is loadable. Injectable for tests. */
    private val nativeAvailable: () -> Boolean = { MagCalNative.isAvailable() },
    /** Native batch fit; returns [offX, offY, offZ, sX, sY, sZ] or null. Injectable for tests. */
    private val nativeFit: (FloatArray) -> FloatArray? = { MagCalNative.nativeFit(it) },
) {
    private val calibrator = MagCalibrator()

    // Raw finite samples retained for the canonical native fit, in addition to
    // (not instead of) the bounding-box calibrator that drives the live
    // preview/coverage gauge. Guarded by [sampleLock]: feed() runs on the sensor
    // thread while accept()/discard()/start() run on the UI thread, and
    // ArrayDeque is not thread-safe.
    private val sampleLock = Any()
    private val samples = ArrayDeque<Vec3>()

    private val _progress = MutableStateFlow(MagProgress.IDLE)
    val progress: StateFlow<MagProgress> = _progress.asStateFlow()

    @Volatile private var active: Boolean = false

    fun start() {
        calibrator.reset()
        synchronized(sampleLock) { samples.clear() }
        _progress.value = MagProgress.IDLE
        active = true
    }

    fun stop() {
        active = false
    }

    fun feed(sample: Vec3) {
        if (!active) return
        calibrator.addSample(sample)
        // Retain the raw sample for the native fit. Mirror the calibrator's
        // guard and drop non-finite samples before they enter the buffer; cap
        // the buffer, evicting oldest, so a very long session stays bounded.
        if (sample.x.isFinite() && sample.y.isFinite() && sample.z.isFinite()) {
            synchronized(sampleLock) {
                samples.addLast(sample)
                while (samples.size > maxSamples) {
                    samples.removeFirst()
                }
            }
        }
        val spread = calibrator.minSpread()
        val ratio = (spread / minSpreadTarget).coerceIn(0f, 1f)
        val sampleCount = calibrator.sampleCount()
        val complete = spread >= minSpreadTarget && sampleCount >= minSamples
        _progress.value = MagProgress(
            samples = sampleCount,
            coverage = calibrator.coverage(),
            ratio = ratio,
            complete = complete,
            preview = calibrator.partial,
        )
    }

    /** Accept the current best-fit. Returns null if not yet good enough. */
    fun accept(): MagCalibration? {
        // Detach the sink first so the sensor thread cannot mutate the
        // calibrator's extents (or the sample buffer) while we read them.
        active = false
        // Enforce the documented completion criteria, not just non-identity: a
        // barely-collected session with a tiny-spread axis yields a blown-up
        // scale that still passes isIdentity.
        if (calibrator.minSpread() < minSpreadTarget || calibrator.sampleCount() < minSamples) {
            return null
        }
        // Prefer the canonical native sphere-fit wizard: same algorithm as
        // desktop, stronger accept/reject gates, finite-centre guarantee. It
        // models hard-iron offset only, so the returned scale is unity.
        if (nativeAvailable()) {
            val fit = nativeFit(flattenSamples())
            if (fit != null) {
                // A malformed or non-finite native result is rejected outright
                // rather than committing garbage onto the fusion path.
                if (fit.size != 6 || fit.any { !it.isFinite() }) return null
                val result = MagCalibration(
                    offset = Vec3(fit[0], fit[1], fit[2]),
                    scale = Vec3(fit[3], fit[4], fit[5]),
                )
                return if (result.isIdentity) null else result
            }
            // fit == null: the wizard rejected the cloud (coverage/residual);
            // fall through to the bounding-box fit below.
        }
        // Native unavailable or wizard returned null: fall back to the on-device
        // bounding-box fit (unchanged behaviour).
        val result = calibrator.finish()
        if (result.isIdentity) return null
        return result
    }

    fun discard() {
        active = false
        calibrator.reset()
        synchronized(sampleLock) { samples.clear() }
        _progress.value = MagProgress.IDLE
    }

    /** Snapshot the retained samples as a flat [x0, y0, z0, x1, ...] batch. */
    private fun flattenSamples(): FloatArray = synchronized(sampleLock) {
        val out = FloatArray(samples.size * 3)
        var i = 0
        for (s in samples) {
            out[i++] = s.x
            out[i++] = s.y
            out[i++] = s.z
        }
        out
    }
}

data class MagProgress(
    val samples: Int,
    val coverage: Vec3,
    val ratio: Float,
    val complete: Boolean,
    val preview: MagCalibration,
) {
    companion object {
        val IDLE = MagProgress(
            samples = 0,
            coverage = Vec3.ZERO,
            ratio = 0f,
            complete = false,
            preview = MagCalibration.IDENTITY,
        )
    }
}
