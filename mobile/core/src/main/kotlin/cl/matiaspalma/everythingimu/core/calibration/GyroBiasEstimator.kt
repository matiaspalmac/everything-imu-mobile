package cl.matiaspalma.everythingimu.core.calibration

import cl.matiaspalma.everythingimu.core.sensors.Vec3
import kotlin.math.sqrt

/**
 * Online gyro bias estimator. While the device is stationary
 * (gyro magnitude below [stationaryThreshold] for [stationaryHoldNanos]),
 * we average the raw samples and treat the mean as the bias.
 *
 * Stationary detection follows the SlimeVR / wrangler approach: low-pass
 * the gyro norm and require the magnitude to stay under threshold for a
 * minimum window before accepting a bias update.
 */
class GyroBiasEstimator(
    private val stationaryThreshold: Float = 0.05f,   // rad/s
    private val stationaryHoldNanos: Long = 3_000_000_000L,  // 3 s
) {

    @Volatile private var biasX: Float = 0f
    @Volatile private var biasY: Float = 0f
    @Volatile private var biasZ: Float = 0f
    @Volatile private var sampleCount: Int = 0
    @Volatile private var calibrated: Boolean = false

    private var sumX: Double = 0.0
    private var sumY: Double = 0.0
    private var sumZ: Double = 0.0
    private var samples: Int = 0
    private var windowStartNanos: Long = 0L
    // `0L` is a valid Android timestamp, so it can't double as "not started".
    private var windowActive: Boolean = false

    // Low-passed gyro magnitude used for the stillness decision (see [apply]).
    private var magLp: Float = 0f
    private var magLpInit: Boolean = false

    // Last finite corrected reading, returned when a non-finite sample arrives.
    @Volatile private var lastCorrected: Vec3 = Vec3.ZERO

    fun bias(): Vec3 = Vec3(biasX, biasY, biasZ)
    fun isCalibrated(): Boolean = calibrated

    /** Seed the estimator with a previously persisted bias. */
    fun seed(bias: Vec3) {
        biasX = bias.x; biasY = bias.y; biasZ = bias.z
        calibrated = bias.x != 0f || bias.y != 0f || bias.z != 0f
    }

    fun reset() {
        biasX = 0f; biasY = 0f; biasZ = 0f
        sampleCount = 0
        calibrated = false
        magLp = 0f
        magLpInit = false
        lastCorrected = Vec3.ZERO
        clearWindow()
    }

    /**
     * Feed a raw gyro sample. Returns the bias-corrected reading.
     * The bias itself updates only while stationary; consumers should pass
     * `corrected` to fusion.
     */
    fun apply(raw: Vec3, timestampNanos: Long): Vec3 {
        // Reject non-finite samples: a NaN/Inf raw reading would otherwise pass
        // straight through as a NaN corrected vector and permanently poison the
        // fusion integrator. Return the last finite corrected reading instead.
        if (!raw.x.isFinite() || !raw.y.isFinite() || !raw.z.isFinite()) {
            return lastCorrected
        }

        // Judge stillness on the BIAS-CORRECTED vector (not the raw magnitude):
        // a device whose true idle offset exceeds the raw threshold would never
        // look stationary, so the estimator could never converge or re-trigger.
        // The raw samples are still what we average into the bias estimate.
        val cx = raw.x - biasX
        val cy = raw.y - biasY
        val cz = raw.z - biasZ
        val mag = sqrt((cx * cx + cy * cy + cz * cz).toDouble()).toFloat()

        // Low-pass the magnitude (as the class doc promises) so a single
        // above-threshold noise spike no longer discards the whole accumulated
        // window and starves convergence on a noisy device.
        magLp = if (magLpInit) {
            MAG_LP_ALPHA * magLp + (1f - MAG_LP_ALPHA) * mag
        } else {
            magLpInit = true
            mag
        }

        if (magLp < stationaryThreshold) {
            if (!windowActive) {
                windowStartNanos = timestampNanos
                windowActive = true
            }
            sumX += raw.x; sumY += raw.y; sumZ += raw.z
            samples += 1
            if (timestampNanos - windowStartNanos >= stationaryHoldNanos && samples >= 32) {
                biasX = (sumX / samples).toFloat()
                biasY = (sumY / samples).toFloat()
                biasZ = (sumZ / samples).toFloat()
                sampleCount = samples
                calibrated = true
                clearWindow()
            }
        } else {
            clearWindow()
        }

        val corrected = Vec3(raw.x - biasX, raw.y - biasY, raw.z - biasZ)
        lastCorrected = corrected
        return corrected
    }

    private fun clearWindow() {
        sumX = 0.0; sumY = 0.0; sumZ = 0.0
        samples = 0
        windowStartNanos = 0L
        windowActive = false
    }

    companion object {
        // Gyro-magnitude low-pass factor for stillness detection. Light
        // smoothing: rejects single-sample spikes without lagging real motion.
        private const val MAG_LP_ALPHA = 0.8f
    }
}
