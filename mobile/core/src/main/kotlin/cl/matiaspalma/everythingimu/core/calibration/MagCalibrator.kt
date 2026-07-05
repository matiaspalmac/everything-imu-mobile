package cl.matiaspalma.everythingimu.core.calibration

import cl.matiaspalma.everythingimu.core.sensors.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Magnetometer calibration via bounding-box (min/max midpoint) fit.
 *
 * Captures the user's figure-8 motion, then estimates:
 *   - hard-iron offset: midpoint of axis-wise min/max
 *   - soft-iron scale: per-axis normalization so that the half-range is
 *     equal across X / Y / Z (the simplest approximation to an ellipsoid fit)
 *
 * Apply with [transform] to produce a calibrated magnetic vector that
 * the VQF MARG path will consume.
 */
class MagCalibrator {

    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY
    private var count: Int = 0

    /** Most recent partial result for live preview during the figure-8. */
    @Volatile
    var partial: MagCalibration = MagCalibration.IDENTITY
        private set

    /** Reset to a clean slate (call when the user re-starts calibration). */
    fun reset() {
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY
        count = 0
        partial = MagCalibration.IDENTITY
    }

    fun addSample(sample: Vec3) {
        // Drop non-finite samples before they reach the min/max fold: NaN
        // propagates through kotlin.math.min/max and every downstream guard is a
        // comparison that is false for NaN, so a single bad sample would poison
        // the committed (and persisted) calibration.
        if (!sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite()) return
        minX = min(minX, sample.x); minY = min(minY, sample.y); minZ = min(minZ, sample.z)
        maxX = max(maxX, sample.x); maxY = max(maxY, sample.y); maxZ = max(maxZ, sample.z)
        count += 1
        if (count >= 8) {
            partial = computeUnsafe()
        }
    }

    fun sampleCount(): Int = count

    /** Spread (max - min) per axis; used by the UI to gauge coverage. */
    fun coverage(): Vec3 {
        if (count == 0) return Vec3.ZERO
        return Vec3(maxX - minX, maxY - minY, maxZ - minZ)
    }

    /** Smallest axis spread — figure-8 is "complete" once this exceeds some threshold (~30 µT). */
    fun minSpread(): Float {
        val cov = coverage()
        if (count == 0) return 0f
        return min(min(cov.x, cov.y), cov.z)
    }

    fun finish(): MagCalibration {
        if (count < 32 || minSpread() <= 0f) return MagCalibration.IDENTITY
        val result = computeUnsafe()
        // Never commit/persist a non-finite calibration: transform() would emit
        // NaN into the MARG fusion path / the wire.
        if (!result.offset.x.isFinite() || !result.offset.y.isFinite() || !result.offset.z.isFinite() ||
            !result.scale.x.isFinite() || !result.scale.y.isFinite() || !result.scale.z.isFinite()
        ) {
            return MagCalibration.IDENTITY
        }
        return result
    }

    private fun computeUnsafe(): MagCalibration {
        val cx = (maxX + minX) * 0.5f
        val cy = (maxY + minY) * 0.5f
        val cz = (maxZ + minZ) * 0.5f
        val rx = (maxX - minX) * 0.5f
        val ry = (maxY - minY) * 0.5f
        val rz = (maxZ - minZ) * 0.5f
        val avg = (rx + ry + rz) / 3f
        if (rx <= 0f || ry <= 0f || rz <= 0f || avg <= 0f) return MagCalibration.IDENTITY
        // Clamp soft-iron scale to a sane band: a tiny-spread axis makes avg/r
        // blow up, so a barely-collected (or spiked) session would otherwise
        // emit a runaway scale that distorts every mag sample.
        val sx = (avg / rx).coerceIn(MIN_SCALE, MAX_SCALE)
        val sy = (avg / ry).coerceIn(MIN_SCALE, MAX_SCALE)
        val sz = (avg / rz).coerceIn(MIN_SCALE, MAX_SCALE)
        if (!cx.isFinite() || !cy.isFinite() || !cz.isFinite() ||
            !sx.isFinite() || !sy.isFinite() || !sz.isFinite()
        ) {
            return MagCalibration.IDENTITY
        }
        return MagCalibration(
            offset = Vec3(cx, cy, cz),
            scale = Vec3(sx, sy, sz),
        )
    }

    companion object {
        // Sane band for the per-axis soft-iron scale.
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 10f
    }
}

/**
 * Stored magnetometer calibration. [transform] is the cheap inline applied
 * by [cl.matiaspalma.everythingimu.core.sensors.SensorRepository] before
 * each MARG update.
 */
data class MagCalibration(val offset: Vec3, val scale: Vec3) {
    val isIdentity: Boolean
        get() = offset.x == 0f && offset.y == 0f && offset.z == 0f &&
            scale.x == 1f && scale.y == 1f && scale.z == 1f

    fun transform(raw: Vec3): Vec3 = Vec3(
        (raw.x - offset.x) * scale.x,
        (raw.y - offset.y) * scale.y,
        (raw.z - offset.z) * scale.z,
    )

    /** Field-strength sanity check (post-calibration norm should be ~25-65 µT on Earth). */
    fun fieldNorm(raw: Vec3): Float {
        val v = transform(raw)
        return sqrt((v.x * v.x + v.y * v.y + v.z * v.z).toDouble()).toFloat()
    }

    companion object {
        val IDENTITY = MagCalibration(Vec3.ZERO, Vec3(1f, 1f, 1f))
    }
}
