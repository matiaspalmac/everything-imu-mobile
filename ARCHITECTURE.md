# Architecture

How the pieces fit together, from the phone's sensors to a tracker in SlimeVR.
This is a map for contributors; the code is the source of truth.

## Modules

Two Android apps share one library and one native core.

```
crates/            Rust workspace (host-testable, no Android needed)
  imu-math/          quaternions, coordinate frames, mag sphere-fit wizard
  imu-fusion/        VQF (f64), Madgwick, BasicVqf
  jni-android/       cdylib; JNI exports -> libjni_android.so

mobile/
  core/              shared Android library; all the logic lives here
  app-mobile/        phone Compose UI
  app-wear/          Wear OS Compose UI
```

`mobile/core` is where the work happens. Both apps are thin UIs over it.

Inside `core`:

- `sensors/` reads gyro/accel/mag from Android's `SensorManager`, applies the
  stored gyro bias and mag calibration, and estimates gyro bias at rest.
- `fusion/` wraps the native VQF (`VqfEngine` over `VqfNative`) and holds the
  quaternion type.
- `net/` speaks the eimu UDP protocol: `EimuClient`, `EimuProtocol`,
  `EimuDiscovery`, plus `WifiBinder` and `NetworkDiagnostics`.
- `ble/` hosts BLE devices: `BleDeviceManager` plus the Joy-Con 2 and HOPX
  device/protocol classes.
- `gamepad/` forwards system-paired gamepads via the InputDevice sensor API.
- `calibration/` runs the figure-8 mag session and gyro-bias estimator.
- `haptics/` drives the vibration motor from desktop rumble and from VRChat OSC.
- `service/` is the foreground `TrackingService` and the battery-opt helper.
- `tracking/TrackingController` is a process-wide singleton that wires all of
  the above together and exposes `StateFlow`s the UI reads whether or not the
  service is bound.
- `update/`, `prefs/`, `wearable/` cover the update check, DataStore prefs, and
  the phone-to-watch config sync.

## Native core and JNI

`crates/jni-android` compiles to `libjni_android.so` and exposes two JNI
surfaces:

- `VqfNative`: allocate/free a `Vqf`, feed 6-DOF (`nativeUpdateImu`) or 9-DOF
  (`nativeUpdateMarg`) samples, read back the 6-DOF or 9-DOF quaternion.
  Non-finite inputs are rejected at the boundary so a single NaN can't freeze
  fusion. The 9-DOF read falls back to 6-DOF until a magnetometer sample has
  been seen, because the release build is `panic = "abort"` and the underlying
  assert would abort the process.
- `MagCalNative.nativeFit`: takes a flat `[x0,y0,z0, x1,y1,z1, ...]` batch of
  raw magnetometer samples and returns the hard-iron fit from the sphere-fit
  wizard, or null when the wizard's gates (sample count, direction coverage,
  RMS residual) reject the cloud.

`imu-math` and `imu-fusion` are the same crates the desktop uses, kept in sync
by hand, so on-device fusion matches the desktop.

## Phone tracker data flow

The phone sends raw samples; the desktop owns the tracker fusion.

1. `SensorRepository` delivers a bias-corrected gyro/accel/(mag) sample.
2. `TrackingController` throttles to the configured send rate and calls
   `EimuClient.sendImu`, which writes a `MSG_IMU` datagram to the desktop.
3. The desktop runs its own VQF over those samples and hands the fused tracker
   to SlimeVR-Server, which owns the skeleton and mounting.
4. In parallel, `VqfEngine` fuses the same samples on the phone at about 60 Hz.
   This drives the on-screen orientation card only; it is not what the desktop
   tracks.

Recenter is a `MSG_BUTTON` to the desktop, and it also zeroes the local preview
so the card reacts immediately.

## eimu UDP protocol

`EimuProtocol` is byte-for-byte parity with the desktop's `device-remote` crate;
both sides carry mirrored fixture tests. Datagrams start with the ASCII envelope
`EIMU`, a version byte (currently 1), and a message byte. Default UDP port is
9320.

| Message | Direction | Purpose |
|---------|-----------|---------|
| `HELLO` / `HELLO_ACK` | both | handshake; the ack echoes a timestamp for RTT |
| `ANNOUNCE` / `REMOVE` | to desktop | register/drop a device (handle, kind, MAC, hasMag/Battery/Rumble, rate, name) |
| `IMU` / `IMU2` | to desktop | one sample; `IMU2` adds a wrapping seq for loss measurement |
| `BATTERY` | to desktop | battery fraction + charging |
| `BUTTON` | to desktop | recenter (yaw or full) |
| `RUMBLE` | from desktop | intensity for a handle |

Handles: 0 is the phone (or the watch). BLE-hosted controllers take handles from
1 up. InputDevice-forwarded gamepads take handles from 1000 up so the two ranges
never collide.

`EimuDiscovery` broadcasts a `HELLO` on the LAN so Auto-discover can find the
desktop without a typed IP.

## BLE hosting

`BleDeviceManager` scans for two things: the Switch 2 manufacturer-data prefix,
and the Nordic UART service used by HOPX "Triki" pucks.

- Joy-Con 2 and Pro Controller 2 connect over GATT. `Jc2Protocol` parses input
  report `0x05` into gyro, accel, magnetometer, battery, and buttons, with an
  axis remap per side. The magnetometer is gated on magnitude and forwarded, so
  these announce as 9-DOF. Sub-command `0x15` soft-bricks pairing, so the raw
  sub-command builder is private and only fixed, known-safe commands are exposed.
- HOPX pucks stream over the Nordic UART service. `HopxProtocol` reframes the
  14-byte records (gyro-first, 52 Hz) and forwards gyro + accel.

Each connected device is announced as its own handle and its samples are pushed
through the same `EimuClient.sendImu` path as the phone. An involuntary drop
runs `ReconnectPolicy`: up to 5 attempts with 1/2/4/8/16 s backoff. A user
disconnect or app shutdown suppresses the reconnect.

Bluetooth Classic HID controllers (Joy-Con 1, DualSense over BT, Wii Remote)
can't be hosted by an unrooted Android app. Those reach the desktop directly, or
are forwarded through the InputDevice path below when Android exposes their
motion sensors.

## Gamepad forwarding

On Android 12+ (`API 31`), `GamepadSensorForwarder` reads the accelerometer and
gyroscope that Android exposes on a system-paired gamepad through the InputDevice
sensor API. `GamepadKinds` maps the USB vendor/product ID to a protocol kind
(DualSense, DualShock 4, Joy-Con 1, Pro Controller, or a generic gamepad) and it
forwards battery and rumble alongside motion.

## Haptics

`HapticBridge` covers both directions of rumble:

- Desktop rumble arrives as `MSG_RUMBLE`. Handle 0 pulses the phone motor;
  higher handles route to the matching controller.
- It also listens on UDP 9001 for VRChat's outgoing OSC, maps
  `/avatar/parameters/<name>` values to `VibrationEffect`, and runs a silence
  watchdog so a stuck value can't leave the motor running.

## Service and Wi-Fi

`TrackingService` is a `specialUse` foreground service holding wake and Wi-Fi
locks for the session. `WifiBinder` binds the UDP socket to the Wi-Fi network so
the stream leaves the Wi-Fi NIC even when a watch or phone would otherwise route
through a default proxy. A connectivity callback re-connects after a Wi-Fi
change. `BatteryOptHelper` links to per-vendor battery-optimization instructions.

## Wear OS

The watch runs `core` in tracker-only mode: no BLE hosting, no gamepad
forwarding. It announces itself as a watch and streams its own IMU.

It gets the desktop address one of two ways. The phone pushes it over the
Wearable Data Layer (`WearableConfigSender.push` on the phone,
`ConfigListenerService` on the watch). If that path is unavailable, for example
on an AOSP or de-Googled watch with no Play Services, `HostPickerScreen` lets the
user dial in four IP octets and a port with the rotary crown.

## Build pipeline

`mobile/core/build.gradle.kts` registers a `buildJniAndroid` task that runs
`cargo-ndk` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, writing
`libjni_android.so` into `core/src/main/jniLibs/`. Debug builds treat this as
best-effort and fall back to any prebuilt libraries. A `verifyJniLibs` task fails
a release build if a target `.so` is missing, because `jniLibs` are gitignored
and a silent cargo failure would otherwise sign an APK with an empty native
folder.
</content>
