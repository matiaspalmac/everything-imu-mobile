//! VQF oracle replay test.
//!
//! Asserts max abs error per quaternion component < 1e-9 vs reference output.

use imu_fusion::Vqf;
use serde_json::Value;

#[test]
fn vqf_oracle_replay_matches_reference() {
    let json = std::fs::read_to_string("fixtures/vqf_oracle.json").expect("fixture missing");
    let v: Value = serde_json::from_str(&json).unwrap();

    let fs = v["fs"].as_f64().unwrap();
    let n = v["n"].as_u64().unwrap() as usize;
    let ts = 1.0 / fs;

    let read_vec3 = |key: &str| -> Vec<[f64; 3]> {
        v[key]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| {
                let r = row.as_array().unwrap();
                [
                    r[0].as_f64().unwrap(),
                    r[1].as_f64().unwrap(),
                    r[2].as_f64().unwrap(),
                ]
            })
            .collect()
    };

    let gyr = read_vec3("gyr");
    let acc = read_vec3("acc");
    let mag = read_vec3("mag");

    let expected_q6: Vec<[f64; 4]> = v["expected"]["quat6d"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| {
            let r = row.as_array().unwrap();
            [
                r[0].as_f64().unwrap(),
                r[1].as_f64().unwrap(),
                r[2].as_f64().unwrap(),
                r[3].as_f64().unwrap(),
            ]
        })
        .collect();

    let mut vqf = Vqf::new(ts);
    let mut max_err = 0.0_f64;
    for i in 0..n {
        vqf.update(gyr[i], acc[i], Some(mag[i]));
        let got = vqf.quat_6d();
        let sign = (got[0] * expected_q6[i][0]
            + got[1] * expected_q6[i][1]
            + got[2] * expected_q6[i][2]
            + got[3] * expected_q6[i][3])
            .signum();
        for (c, g) in got.iter().enumerate() {
            let err = (sign * g - expected_q6[i][c]).abs();
            if err > max_err {
                max_err = err;
            }
        }
    }
    assert!(
        max_err < 1e-9,
        "max VQF quat6d error {max_err} exceeds 1e-9 oracle tolerance"
    );
}

/// N35: the 6-DOF oracle above never exercises the mag / heading path, so a
/// transposed atan2 or a broken delta-wrap in `update_mag` / `quat_9d` would
/// regress undetected. Replay the same fixture (which carries mag) and assert
/// the 9-DOF output is a finite unit quaternion, that the mag correction is
/// heading-only (applying it must not change the inclination — the earth-frame
/// Z-component of the body up-axis is identical for quat_6d and quat_9d, since
/// a yaw about earth-Z preserves Z), and that it matches a golden final
/// quaternion captured from the corrected code so a numeric heading regression
/// is caught even when it stays a pure yaw.
#[test]
fn vqf_oracle_quat9d_heading_path() {
    fn rotate(q: [f64; 4], v: [f64; 3]) -> [f64; 3] {
        let [w, x, y, z] = q;
        let t = [
            2.0 * (y * v[2] - z * v[1]),
            2.0 * (z * v[0] - x * v[2]),
            2.0 * (x * v[1] - y * v[0]),
        ];
        [
            v[0] + w * t[0] + (y * t[2] - z * t[1]),
            v[1] + w * t[1] + (z * t[0] - x * t[2]),
            v[2] + w * t[2] + (x * t[1] - y * t[0]),
        ]
    }

    let json = std::fs::read_to_string("fixtures/vqf_oracle.json").expect("fixture missing");
    let v: Value = serde_json::from_str(&json).unwrap();
    let fs = v["fs"].as_f64().unwrap();
    let n = v["n"].as_u64().unwrap() as usize;
    let ts = 1.0 / fs;
    let read_vec3 = |key: &str| -> Vec<[f64; 3]> {
        v[key]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| {
                let r = row.as_array().unwrap();
                [
                    r[0].as_f64().unwrap(),
                    r[1].as_f64().unwrap(),
                    r[2].as_f64().unwrap(),
                ]
            })
            .collect()
    };
    let gyr = read_vec3("gyr");
    let acc = read_vec3("acc");
    let mag = read_vec3("mag");

    let mut vqf = Vqf::new(ts);
    for i in 0..n {
        vqf.update(gyr[i], acc[i], Some(mag[i]));
    }
    let q6 = vqf.quat_6d();
    let q9 = vqf.quat_9d();

    // 1. finite unit quaternion
    let norm = (q9[0] * q9[0] + q9[1] * q9[1] + q9[2] * q9[2] + q9[3] * q9[3]).sqrt();
    assert!(
        q9.iter().all(|c| c.is_finite()),
        "quat_9d has non-finite component: {q9:?}"
    );
    assert!((norm - 1.0).abs() < 1e-9, "quat_9d not unit: norm {norm}");

    // 2. heading-only correction: inclination (earth-Z of the body up-axis) preserved
    let up = [0.0, 0.0, 1.0];
    let up6 = rotate(q6, up);
    let up9 = rotate(q9, up);
    assert!(
        (up6[2] - up9[2]).abs() < 1e-9,
        "quat_9d changed inclination (up.z {} vs {}) — delta is not a pure earth-Z yaw",
        up6[2],
        up9[2]
    );

    // 3. golden final quaternion (captured from the corrected update_mag path).
    // Regenerate only if the fusion math legitimately changes.
    let golden = [0.877_522_997_012_223_2, 0.0, 0.0, 0.479_534_555_287_401_24];
    let sign = (0..4).map(|c| q9[c] * golden[c]).sum::<f64>().signum();
    for c in 0..4 {
        assert!(
            (sign * q9[c] - golden[c]).abs() < 1e-9,
            "quat_9d[{c}] {} drifted from golden {}",
            sign * q9[c],
            golden[c]
        );
    }
}
