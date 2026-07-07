<div align="center">
  <h1>everything-imu-mobile</h1>
  <p><strong>Turn an Android phone and a Wear OS watch into full-body SlimeVR trackers</strong></p>

  <p>
    <a href="https://github.com/matiaspalmac/everything-imu-mobile/actions/workflows/ci.yml"><img src="https://github.com/matiaspalmac/everything-imu-mobile/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
    <img src="https://img.shields.io/badge/platform-Android%20%7C%20Wear%20OS-3DDC84.svg" alt="Platform">
    <a href="https://github.com/matiaspalmac/everything-imu-mobile/releases/latest"><img src="https://img.shields.io/github/v/release/matiaspalmac/everything-imu-mobile?include_prereleases&label=release" alt="Latest release"></a>
  </p>
</div>

No extra hardware. The device's own IMU is streamed to the eimu desktop app
over Wi-Fi, and haptics are sent back to the device's vibration motor.

Companion to the desktop [**everything-imu**](https://github.com/matiaspalmac/everything-imu)
bridge, which turns game controllers into trackers. This repo is the mobile half.
It pairs with the desktop hub, which owns the SlimeVR handoff.

---

## Features

**Phone tracker**
- Streams the phone's raw, gyro-bias-corrected IMU (gyroscope, accelerometer,
  and optional magnetometer) to the eimu desktop app over UDP. The desktop runs
  the tracker fusion and registers the phone as a tracker, so mounting and
  calibration sit in one place alongside your other devices.
- On-device orientation preview. A native Rust VQF core (loaded from
  `libjni_android.so`) fuses the same sensors on the phone to drive the live
  orientation card. Where the device exposes an OS fused rotation vector, you
  can switch the preview to that instead.
- **Auto-discover**: one tap finds the desktop on your LAN and connects, with
  no IP typing. Manual host entry is there if you need it.
- Live link health in the header: connection state, endpoint, and **round-trip
  time**. The desktop measures real packet loss from sequence numbers.
- Configurable send rate (30 to 400 Hz) with a live *streaming / sensor*
  readout, a magnetometer toggle, and shake-to-recenter. Changes apply live,
  no restart.
- Magnetometer **figure-8 calibration** backed by a native Rust sphere-fit
  wizard over JNI, gated on sample count, direction coverage, and residual
  error. An on-device bounding-box fit drives the live coverage gauge and is
  the fallback when the native library is unavailable. Gyro bias is estimated
  automatically while the device rests.
- Battery level reported to the desktop, and notification quick actions for
  **Recenter** and **Stop**.
- Foreground service with wake and Wi-Fi locks, plus an OEM battery-optimization
  helper with per-vendor guidance so aggressive power managers don't kill
  tracking.
- Bounded auto-reconnect after a Wi-Fi change.
- In-app update check against this repository's GitHub releases, a **Help & FAQ**,
  and one-tap **network diagnostics** (Wi-Fi, IP, and server reach).
- EN / ES localization.

**Controller forwarding**
- Hosts BLE controllers paired to the phone as **additional trackers**:
  Joy-Con 2, Switch 2 Pro Controller, and HOPX "Triki" BLE IMU pucks. Scan, tap
  connect, and they show up in the desktop device list.
- **Joy-Con 2 and Pro Controller 2 are now 9-DOF.** Their magnetometer is
  parsed and forwarded, so the desktop can fuse a magnetically referenced
  heading instead of a 6-DOF yaw that walks off over time.
- **Bounded auto-reconnect** (up to 5 attempts, 1 to 16 s exponential backoff)
  re-links a controller after an involuntary BLE drop, keeping the desktop
  handle alive across the blip. A user disconnect never triggers it.
- Forwards **regular paired gamepads** too (Android 12+): DualSense,
  DualShock 4, Joy-Con 1, Pro Controller, and any pad whose motion sensors the
  system exposes through Android's InputDevice sensor API, with battery and
  rumble. Pair in system Bluetooth settings and they stream automatically while
  connected to the desktop.
- Hub roles: phone + gamepads, phone only, or gamepads only.

**Haptics**
- Desktop-initiated rumble flows to the phone's vibration motor and to
  forwarded controllers. Every pulse shows in the haptics log.
- VRChat OSC to device vibration bridge (UDP `9001`), with an auto-off watchdog
  so a stuck value can't leave the motor running.

**Wear OS companion**
- Runs standalone. The watch streams its own IMU as an independent tracker.
- Auto-syncs the desktop address from the phone over the Wearable Data Layer.
  You can also set the IP directly on the watch with a wheel picker: four octet
  wheels and a port wheel, driven by the rotary crown. It is pure Compose with
  no keyboard and no Play Services, so it works on AOSP / de-Googled watches
  with no companion.

---

## Install

Download the latest APKs from [**Releases**](https://github.com/matiaspalmac/everything-imu-mobile/releases/latest):

- `everything-imu-phone-<tag>.apk` is the phone tracker.
- `everything-imu-wear-<tag>.apk` is the Wear OS companion (optional).

Release APKs are signed. If you previously installed an unsigned build, uninstall
it first. Android refuses an in-place upgrade when the signing key changes.

### Quick start
1. Install the phone APK, open it, grant sensor + notification permissions.
2. With the eimu desktop app running on your PC, tap **Auto-discover**. The app
   finds it and connects, or type the PC's IP manually.
3. Mount the phone on your body, calibrate, and assign the tracker in SlimeVR.

The phone and PC must be on the same Wi-Fi network. If it won't connect, run
**network diagnostics** from Settings to check Wi-Fi, IP, and server reach.

---

## Build from source

### Native fusion core (Rust)
The `crates/imu-math`, `crates/imu-fusion`, and `crates/jni-android` workspace
builds and tests on the host with no Android toolchain:
```sh
cargo build      # host build
cargo test
```

### Android apps
Requires JDK 17, the Android SDK, an Android NDK, and `cargo-ndk` for the native
core. Gradle drives everything from `mobile/`:
```sh
cargo install cargo-ndk
cd mobile
./gradlew :app-mobile:assembleDebug :app-wear:assembleDebug
```
The build cross-compiles `crates/jni-android` with `cargo-ndk` for
`arm64-v8a`, `armeabi-v7a`, and `x86_64`, dropping `libjni_android.so` into
`mobile/core/src/main/jniLibs/` automatically. APKs land in
`mobile/app-*/build/outputs/apk/`.

If `cargo` and `cargo-ndk` are not on `PATH`, a debug build falls back to any
prebuilt `jniLibs`. A release build instead fails, so it can never ship without
the native fusion library. `minSdk` is 21 on the phone and 25 on Wear OS;
`compileSdk` is 35.

---

## Release signing

To produce signed release APKs you need a keystore.

```sh
keytool -genkey -v -keystore release.keystore -alias eimu \
  -keyalg RSA -keysize 2048 -validity 10000
```

**Local builds**: copy `mobile/keystore.properties.example` to
`mobile/keystore.properties` and fill in your store/key passwords. Both files are
gitignored.

**CI builds**: set these repository secrets. The release workflow signs with
them automatically, and without them it builds unsigned.

| Secret | Value |
|--------|-------|
| `ANDROID_RELEASE_KEYSTORE_B64` | `base64 -w0 release.keystore` |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | store password |
| `ANDROID_RELEASE_KEY_ALIAS` | key alias (`eimu`) |
| `ANDROID_RELEASE_KEY_PASSWORD` | key password |

Tag a commit `v*` to build, sign, and attach phone + wear APKs to a draft
release.

---

## Repository layout

```
crates/
  imu-math/      shared math primitives + magnetometer sphere-fit wizard
  imu-fusion/    VQF / Madgwick orientation fusion (native core)
  jni-android/   JNI cdylib bridging the core to the Android apps
mobile/
  app-mobile/    phone app
  app-wear/      Wear OS app
  core/          shared Android library (consumes the native .so)
```

Both apps share `mobile/core`, which owns the sensor stack, the eimu UDP
protocol, BLE and gamepad forwarding, calibration, haptics, and the foreground
service. `mobile/app-mobile` and `mobile/app-wear` are thin Compose UIs on top.

The `imu-math` and `imu-fusion` crates are shared with the desktop project and
kept in sync by hand. They change rarely. See
[**ARCHITECTURE.md**](ARCHITECTURE.md) for the end-to-end data flow.

## License

[MIT](LICENSE). Contributions are accepted under the same license.
</content>
