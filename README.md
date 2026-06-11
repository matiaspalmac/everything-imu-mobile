# everything-imu-mobile

Turn an Android phone — and a Wear OS watch — into full-body SlimeVR trackers.
No extra hardware: the device's own IMU is streamed to SlimeVR-Server over
Wi-Fi, and VRChat haptics are sent back to the device's vibration motor.

Companion to the desktop [**everything-imu**](https://github.com/matiaspalmac/everything-imu)
bridge, which turns game controllers into trackers. This repo is the mobile half.

---

## Features

**eimu hub mode (default)**
- Streams the phone's raw IMU to the eimu desktop app over UDP, which runs
  sensor fusion and registers the phone as a tracker — one place for all your
  devices, mounting, and calibration.
- Forwards BLE controllers paired to the phone as **additional trackers**:
  Joy-Con 2, Switch 2 Pro Controller, and HOPX BLE pucks. Scan, tap connect,
  and they appear in the desktop device list.
- Desktop-initiated haptics: rumble flows back to the phone's vibration motor
  and to forwarded controllers.
- Bluetooth Classic devices (Joy-Con 1, DualSense, Wii Remote) can't be hosted
  by Android — pair those directly to the desktop bridge instead.
- Prefer the old direct path? Switch **Send tracking to → SlimeVR server** in
  Settings.

**Phone tracker (direct mode)**
- Streams orientation to SlimeVR-Server over UDP — same protocol the desktop
  bridge speaks, so the server treats it like any other tracker.
- Native VQF sensor fusion (Rust core via JNI) with a pure-Kotlin Madgwick
  fallback when the native library is unavailable.
- Magnetometer figure-8 calibration wizard + automatic gyro-bias estimation.
- Configurable send rate, magnetometer toggle, and shake-to-recenter — applied
  live, no restart.
- Battery level reported to the server; notification quick actions for
  **Recenter** and **Stop**.
- Foreground service with wake + Wi-Fi locks, plus an OEM battery-optimization
  helper so aggressive power managers don't kill tracking.
- Auto-reconnect on Wi-Fi changes.
- EN / ES localization.

**Wear OS companion**
- Runs standalone — the watch streams its own IMU as an independent tracker.
- Auto-syncs the server address from the phone over the Wearable Data Layer,
  or set the IP directly on the watch with a rotary/swipe wheel picker (works on
  AOSP / de-Googled watches with no Play Services — no keyboard, no companion).

**Haptics**
- VRChat OSC → device vibration bridge (UDP `9001`).

---

## Install

Download the latest APKs from [**Releases**](https://github.com/matiaspalmac/everything-imu-mobile/releases/latest):

- `everything-imu-phone-<tag>.apk` — the phone tracker.
- `everything-imu-wear-<tag>.apk` — the Wear OS companion (optional).

Release APKs are signed. If you previously installed an unsigned build, uninstall
it first — Android refuses an in-place upgrade when the signing key changes.

### Quick start
1. Install the phone APK, open it, grant sensor + notification permissions.
2. Enter your PC's IP (where SlimeVR-Server runs) and start tracking.
3. Mount the phone on your body; calibrate; assign the tracker in SlimeVR.

---

## Build from source

### Native fusion core (Rust)
```sh
cargo build      # host build
cargo test
```

### Android apps
Requires JDK 17, the Android SDK, an Android NDK, and `cargo-ndk` for the native
core:
```sh
cargo install cargo-ndk
cd mobile
./gradlew :app-mobile:assembleDebug :app-wear:assembleDebug
```
APKs land in `mobile/app-*/build/outputs/apk/`. The Gradle build cross-compiles
the Rust core into `mobile/core/src/main/jniLibs/` automatically.

---

## Release signing

To produce signed release APKs you need a keystore.

```sh
keytool -genkey -v -keystore release.keystore -alias eimu \
  -keyalg RSA -keysize 2048 -validity 10000
```

**Local builds** — copy `mobile/keystore.properties.example` to
`mobile/keystore.properties` and fill in your store/key passwords. Both files are
gitignored.

**CI builds** — set these repository secrets (the release workflow signs with
them automatically; without them it builds unsigned):

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
  imu-math/      shared math primitives
  imu-fusion/    VQF / Madgwick orientation fusion (native core)
  jni-android/   JNI cdylib bridging the core to the Android apps
mobile/
  app-mobile/    phone app
  app-wear/      Wear OS app
  core/          shared Android library (consumes the native .so)
```

The `imu-math` / `imu-fusion` crates are shared with the desktop project and
kept in sync manually — they change rarely.

## License

MIT.
