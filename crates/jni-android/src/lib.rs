//! JNI bindings exposing the `imu-fusion` and `imu-math` workspace crates to
//! the Android `mobile/core` module.
//!
//! Exported classes:
//!   cl.matiaspalma.everythingimu.core.fusion.VqfNative
//!   cl.matiaspalma.everythingimu.core.calibration.MagCalNative

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use imu_fusion::Vqf;
use imu_math::mag_cal_wizard::{MagCalWizard, WizardState};

pub fn fusion_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// Fit a batch of flattened XYZ magnetometer samples (µT) with the canonical
/// sphere-fit wizard, shaping the accepted result into a fixed 6-float layout
/// `[offsetX, offsetY, offsetZ, scaleX, scaleY, scaleZ]`.
///
/// The wizard models hard-iron offset only (an algebraic sphere fit), so scale
/// is always unity — the mobile calibration format carries a per-axis scale for
/// the bounding-box path, and unit scale keeps a wizard fit compatible with it
/// without a persistence migration.
///
/// Returns `None` when the input is empty or not a whole number of XYZ triples,
/// or when the wizard's gates (minimum sample count, direction coverage, RMS
/// residual) reject the sample cloud. Factored out of the JNI entry point so the
/// batch-to-`[6]` shaping is unit-testable on the host without a JVM.
fn fit_mag_batch(flat: &[f32]) -> Option<[f32; 6]> {
    if flat.is_empty() || flat.len() % 3 != 0 {
        return None;
    }
    let mut wizard = MagCalWizard::new();
    wizard.start();
    for triple in flat.chunks_exact(3) {
        wizard.ingest([triple[0], triple[1], triple[2]]);
    }
    match wizard.finalize() {
        WizardState::Ready { calibration } => {
            let o = calibration.offset;
            Some([o[0], o[1], o[2], 1.0, 1.0, 1.0])
        }
        _ => None,
    }
}

/// Allocate a VQF instance on the heap and return a raw handle.
/// Caller must release with `vqf_free`.
fn vqf_alloc(sample_rate_hz: f64) -> i64 {
    let gyr_ts = if sample_rate_hz > 0.0 {
        1.0 / sample_rate_hz
    } else {
        1.0 / 400.0
    };
    let vqf = Box::new(Vqf::new(gyr_ts));
    Box::into_raw(vqf) as i64
}

unsafe fn vqf_mut<'a>(handle: i64) -> &'a mut Vqf {
    &mut *(handle as *mut Vqf)
}

fn vqf_free(handle: i64) {
    if handle == 0 {
        return;
    }
    unsafe { drop(Box::from_raw(handle as *mut Vqf)) };
}

#[cfg(target_os = "android")]
mod android_bindings {
    use super::*;
    use jni::objects::{JClass, JFloatArray};
    use jni::sys::{jdouble, jfloat, jfloatArray, jlong};
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_nativeNew(
        _env: JNIEnv,
        _class: JClass,
        sample_rate_hz: jdouble,
    ) -> jlong {
        vqf_alloc(sample_rate_hz)
    }

    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_nativeDrop(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) {
        vqf_free(handle);
    }

    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_nativeUpdateImu(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        gx: jfloat,
        gy: jfloat,
        gz: jfloat,
        ax: jfloat,
        ay: jfloat,
        az: jfloat,
    ) {
        if handle == 0 {
            return;
        }
        // Reject a non-finite raw sample at the boundary: a single NaN would
        // otherwise propagate through quat_normalize into gyr_quat and freeze
        // the fusion state permanently.
        if ![gx, gy, gz, ax, ay, az].iter().all(|v| v.is_finite()) {
            return;
        }
        let vqf = unsafe { vqf_mut(handle) };
        vqf.update(
            [gx as f64, gy as f64, gz as f64],
            [ax as f64, ay as f64, az as f64],
            None,
        );
    }

    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_nativeUpdateMarg(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        gx: jfloat,
        gy: jfloat,
        gz: jfloat,
        ax: jfloat,
        ay: jfloat,
        az: jfloat,
        mx: jfloat,
        my: jfloat,
        mz: jfloat,
    ) {
        if handle == 0 {
            return;
        }
        // Reject a non-finite raw sample at the boundary (see nativeUpdateImu).
        // A non-finite mag component is dropped to a plain 6-DOF update rather
        // than poisoning the mag path.
        if ![gx, gy, gz, ax, ay, az].iter().all(|v| v.is_finite()) {
            return;
        }
        let mag = if [mx, my, mz].iter().all(|v| v.is_finite()) {
            Some([mx as f64, my as f64, mz as f64])
        } else {
            None
        };
        let vqf = unsafe { vqf_mut(handle) };
        vqf.update(
            [gx as f64, gy as f64, gz as f64],
            [ax as f64, ay as f64, az as f64],
            mag,
        );
    }

    /// Fill `out` with the 6-DOF quaternion (gyro + accel) as [w, x, y, z].
    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_nativeQuat6d<
        'l,
    >(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        handle: jlong,
        out: JFloatArray<'l>,
    ) {
        if handle == 0 {
            return;
        }
        let vqf = unsafe { vqf_mut(handle) };
        let q = vqf.quat_6d();
        let buf = [q[0] as f32, q[1] as f32, q[2] as f32, q[3] as f32];
        let _ = env.set_float_array_region(&out, 0, &buf);
    }

    /// Fill `out` with the 9-DOF quaternion (gyro + accel + mag) as [w, x, y, z].
    /// Falls back to 6d quat if mag has not been fed yet.
    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_nativeQuat9d<
        'l,
    >(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        handle: jlong,
        out: JFloatArray<'l>,
    ) {
        if handle == 0 {
            return;
        }
        let vqf = unsafe { vqf_mut(handle) };
        let buf = catch_quat_9d(vqf);
        let _ = env.set_float_array_region(&out, 0, &buf);
    }

    /// Returns the 9-DOF quaternion, falling back to 6-DOF until a magnetometer
    /// sample has been fed. `quat_9d` asserts on `mag_seen`, and under the
    /// release `panic = "abort"` that assert aborts the whole process —
    /// `catch_unwind` cannot catch an abort — so guard with the flag instead of
    /// relying on unwinding.
    fn catch_quat_9d(vqf: &Vqf) -> [f32; 4] {
        let q = if vqf.mag_seen() {
            vqf.quat_9d()
        } else {
            vqf.quat_6d()
        };
        [q[0] as f32, q[1] as f32, q[2] as f32, q[3] as f32]
    }

    /// Fit a batch of flattened XYZ magnetometer samples (length a multiple of
    /// 3, µT) with the canonical sphere-fit wizard.
    ///
    /// Returns a Java `float[6]` = `[offX, offY, offZ, scaleX, scaleY, scaleZ]`
    /// on a wizard-accepted fit, or Java `null` when the array is missing,
    /// malformed, or the wizard's gates reject the sample cloud. A null return
    /// is the caller's signal to fall back to the on-device bounding-box fit.
    #[no_mangle]
    pub extern "system" fn Java_cl_matiaspalma_everythingimu_core_calibration_MagCalNative_nativeFit<
        'l,
    >(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        samples: JFloatArray<'l>,
    ) -> jfloatArray {
        let len = match env.get_array_length(&samples) {
            Ok(n) if n > 0 => n as usize,
            _ => return std::ptr::null_mut(),
        };
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(&samples, 0, &mut buf).is_err() {
            return std::ptr::null_mut();
        }
        let Some(result) = fit_mag_batch(&buf) else {
            return std::ptr::null_mut();
        };
        let out = match env.new_float_array(6) {
            Ok(arr) => arr,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_float_array_region(&out, 0, &result).is_err() {
            return std::ptr::null_mut();
        }
        out.into_raw()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_non_empty() {
        assert!(!fusion_version().is_empty());
    }

    #[test]
    fn lifecycle_roundtrip() {
        let h = vqf_alloc(400.0);
        assert_ne!(h, 0);
        unsafe {
            let vqf = vqf_mut(h);
            vqf.update([0.01, 0.0, 0.0], [0.0, 0.0, 9.81], None);
            let q = vqf.quat_6d();
            assert!(q[0].is_finite());
        }
        vqf_free(h);
    }

    /// Flatten a well-spread sphere of `steps × steps` samples centred at `c`
    /// into the `[x0, y0, z0, x1, ...]` layout the JNI entry point receives.
    fn flat_sphere(c: [f32; 3], r: f32, steps: usize) -> Vec<f32> {
        let mut out = Vec::new();
        for i in 0..steps {
            let theta = std::f32::consts::PI * (i as f32 + 0.5) / steps as f32;
            for j in 0..steps {
                let phi = 2.0 * std::f32::consts::PI * j as f32 / steps as f32;
                out.push(c[0] + r * theta.sin() * phi.cos());
                out.push(c[1] + r * theta.sin() * phi.sin());
                out.push(c[2] + r * theta.cos());
            }
        }
        out
    }

    #[test]
    fn fit_mag_batch_rejects_malformed_input() {
        assert!(fit_mag_batch(&[]).is_none());
        // Not a whole number of XYZ triples.
        assert!(fit_mag_batch(&[1.0, 2.0]).is_none());
        assert!(fit_mag_batch(&[1.0, 2.0, 3.0, 4.0]).is_none());
    }

    #[test]
    fn fit_mag_batch_full_sphere_returns_offset_and_unit_scale() {
        let centre = [12.0_f32, -30.0, 7.5];
        let flat = flat_sphere(centre, 48.0, 12);
        let r = fit_mag_batch(&flat).expect("well-covered sphere should be accepted");
        for (k, (got, want)) in r[..3].iter().zip(centre.iter()).enumerate() {
            assert!(
                (got - want).abs() < 0.5,
                "offset axis {k}: got {got} want {want}"
            );
        }
        // Wizard is offset-only, so scale is always unity.
        assert_eq!([r[3], r[4], r[5]], [1.0, 1.0, 1.0]);
    }

    #[test]
    fn fit_mag_batch_rejects_thin_band() {
        // Many samples locked near the equator: high count, low coverage, so the
        // wizard's coverage gate rejects the fit and the helper returns None.
        let centre = [0.0_f32; 3];
        let radius = 40.0_f32;
        let theta = std::f32::consts::FRAC_PI_2;
        let mut flat = Vec::new();
        for i in 0..200 {
            let phi = 2.0 * std::f32::consts::PI * (i as f32 / 200.0);
            flat.push(centre[0] + radius * theta.sin() * phi.cos());
            flat.push(centre[1] + radius * theta.sin() * phi.sin());
            flat.push(centre[2] + radius * theta.cos());
        }
        assert!(fit_mag_batch(&flat).is_none());
    }
}
