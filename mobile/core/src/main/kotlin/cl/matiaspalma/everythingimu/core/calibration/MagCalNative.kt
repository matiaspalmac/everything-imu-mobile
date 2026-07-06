package cl.matiaspalma.everythingimu.core.calibration

/**
 * JNI bridge to the canonical magnetometer sphere-fit wizard in
 * `crates/imu-math` (loaded from `libjni_android.so`). This is the committed-fit
 * backend: given a flattened batch of raw XYZ magnetometer samples (µT) it
 * returns the hard-iron calibration the same algorithm produces on desktop, or
 * null when the wizard's gates (sample count, direction coverage, RMS residual)
 * reject the cloud.
 *
 * The on-device bounding-box [MagCalibrator] stays the live figure-8 preview /
 * coverage gauge and the graceful fallback used when this native library is
 * unavailable (see [MagCalibrationSession]).
 */
internal object MagCalNative {

    /**
     * Fit a flattened `[x0, y0, z0, x1, y1, z1, ...]` batch of raw magnetometer
     * samples (length a multiple of 3, µT). Returns a 6-element array
     * `[offsetX, offsetY, offsetZ, scaleX, scaleY, scaleZ]` on an accepted fit,
     * or null if the batch is malformed or the wizard rejects it. The wizard
     * models hard-iron offset only, so scale is always `(1, 1, 1)`.
     *
     * Only invoke after [isAvailable] has returned true.
     */
    @JvmStatic external fun nativeFit(samples: FloatArray): FloatArray?

    @Volatile private var loaded: Boolean = false
    @Volatile private var loadAttempted: Boolean = false

    @Synchronized
    fun isAvailable(): Boolean {
        ensureLoaded()
        return loaded
    }

    @Synchronized
    private fun ensureLoaded() {
        // Attempt the dlopen exactly once. Track "attempted" separately from
        // "loaded": on failure `loaded` stays false, so guarding on `loaded`
        // alone would re-run the failing System.loadLibrary (and Log.e) on every
        // accept(). Latching `loadAttempted` stops that.
        if (loadAttempted) return
        loadAttempted = true
        loaded = try {
            System.loadLibrary("jni_android")
            true
        } catch (t: Throwable) {
            android.util.Log.e("MagCalNative", "failed to load libjni_android.so", t)
            false
        }
    }
}
