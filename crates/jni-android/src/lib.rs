//! JNI bindings exposing the `imu-fusion` workspace crate to the Android `mobile/core` module.
//!
//! Exported class:
//!   cl.matiaspalma.everythingimu.core.fusion.VqfNative

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use imu_fusion::Vqf;

pub fn fusion_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
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
    use jni::sys::{jdouble, jfloat, jlong};
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
}
