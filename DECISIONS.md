# Design decisions pending — everything-imu-mobile

These items from the 2026-07-05 audit are **product/behaviour decisions**, not
defects. The audit's explicitly-safe minimums (e.g. a warning log, a source
check) have been implemented in code where one was stated; everything below is
the remaining human call. Each entry: the question, the options, a
recommendation with rationale, and a rough effort estimate.

Status of every item here: **DECISION-NEEDED**.

---

## CR2 — JC2 magnetometer: 6DOF (phone) vs 9DOF (desktop)

**Question.** A Joy-Con 2 tracked through the phone streams pure gyro+accel
(6DOF) and free-drifts in yaw; the same controller on desktop reads the
hardware magnetometer (report 0x05 offsets 0x19/0x1B/0x1D, flash mag-bias) and
gets absolute yaw. Should the phone parse mag too?

**Options.** (a) Keep JC2 6DOF-only on mobile (simplest, some yaw drift).
(b) Port the mag parse + flash mag-bias read and advertise `hasMag=true`.

**Recommendation:** (b) for parity, but only once the desktop parser is
hardware-re-validated on the phone's BLE stack. A 6DOF JC2 is a valid tracker,
but the yaw drift is a real UX regression versus desktop for a body-worn mount.
**Effort:** medium (port parse + flash read over GATT, magnitude validation,
mag-cal wiring). 

## CR10 — `buildFeatureSelectCmd` exposes an arbitrary sub-command byte

**Question.** The Kotlin `Jc2Protocol.buildFeatureSelectCmd(subcmd, mask)` is
`public`, placing `subcmd` at the wire byte that `0x15` bricks pairing. Not
reachable today (callers hardcode 0x02/0x04, `write` is private).

**Options.** (a) Make it `internal`/`private`. (b) Replace with two zero-arg
builders that hardcode 0x02 and 0x04.

**Recommendation:** (b) — remove the brick byte from the API surface entirely;
it is the most future-proof and matches the desktop `fn` being private.
**Effort:** trivial.

## CR11 — Phone/Watch raw Android-frame samples reuse the Joy-Con remap

**Question.** Remote phone/watch samples are packed in the raw Android sensor
frame and the desktop applies `jsl_to_vqf_body` uniformly; the phone's
"identity" pose is an undocumented upright-portrait assumption with no parity
test. A landscape/watch mount or an OEM with a different basis ships a wrong
default until the user resets.

**Options.** (a) Document the assumption + add an Android↔JSL frame-parity
fixture test. (b) Tag remote samples with a source-frame id and branch the
desktop remap per DeviceKind.

**Recommendation:** (a) now (cheap, catches regressions), (b) later if a second
source frame (watch, OEM) actually diverges. **Effort:** (a) low, (b) medium
(wire-format touch on both repos — coordinate the protocol change).

## CR19 — Madgwick default `beta = 1.0`

**Question.** `Madgwick::new` sets `beta = 1.0` vs the canonical ~0.033–0.1.
Identical on desktop; Madgwick is test-only (VQF is the live path).

**Options.** (a) Keep 1.0 (document why). (b) Default ~0.1. (c) Per-device tune.

**Recommendation:** (b) default 0.1 in **both** repos if Madgwick is ever wired
to a live path; while it stays test-only, (a) with a one-line doc note is
acceptable. Do not change unilaterally — it alters any test baseline that pins
the value. **Effort:** trivial (plus re-baselining `default_beta_is_one`).

## DP1 — Discovery trusts the first HELLO_ACK source with no identity check

**Question.** `discover()` returns the first HelloAck sender; a rogue LAN host
racing the desktop becomes the stream target.

**Options.** (a) Embed a random nonce in HELLO, require the ACK to echo it.
(b) User-confirm the discovered host. (c) Document the trusted-LAN assumption.

**Recommendation:** (a) nonce echo — cheap, closes the race, no UX cost; pair
with (c). **Effort:** low, but it is a wire-format addition — coordinate with
the desktop HELLO/HELLO_ACK parser.

## DP2 — eimu UDP receive loop does not validate datagram source

**Question.** `recvLoop` parses any datagram; a forged HELLO_ACK masks a real
disconnect, a forged Rumble drives the motor.

**Safe minimum (implement regardless):** `connect()` the socket to the endpoint
so the kernel drops off-endpoint datagrams, or compare `pkt.address`/`port`
before parsing. **Recommendation:** compare source against `endpoint` in
recvLoop (cheap, no wire change). **Effort:** trivial. *(Implemented as the safe
minimum — see the audit commit; the broader auth model remains a decision.)*

## DP3 — UDP 9001 OSC ingest is unauthenticated and drives the motor

**Question.** `DatagramSocket(9001)` binds the wildcard; any LAN host can drive
the vibration motor (battery/annoyance DoS). Mirrors VRChat's OSC model.

**Options.** (a) Bind loopback/Wi-Fi only. (b) Restrict to the configured
desktop peer IP. (c) Document the accepted exposure.

**Recommendation:** (b) restrict to the connected desktop peer (the phone
already knows it), fall back to (c) if a third-party OSC source is a use case.
**Effort:** low. *(Combined with the N45 rate-cap fix this closes the battery
DoS.)*

## DP4 — Wear config channel authenticity rests on the same-signature guarantee

**Question.** `ConfigListenerService` is `exported="true"` (required) and
persists the pushed host:port without authenticating the node. Safe only because
Play Services delivers Data Layer items between same-signature installs.

**Options.** (a) Accept + document the same-signature trust boundary (standard
Wear usage). (b) Carry + verify a shared token before applying a new host:port.

**Recommendation:** (a) — this is the platform's standard trust model; document
it. **Effort:** trivial (doc) / medium (token).

## DP5 — Stopping the service leaves socket/gamepad/BLE streaming

**Question.** `onServiceStop()` stops phone sensors but not `eimuClient`,
gamepad forwarder, or BLE. In `both`/`gamepads` roles, controllers keep
forwarding after the user pressed Stop.

**Options.** (a) Stop halts all forwarding (add the teardowns). (b) Document
that gamepad/BLE forwarding is intentionally independent of the phone sensor
service.

**Recommendation:** (a) — "Stop" should stop everything the user perceives as
tracking; least-surprise. **Effort:** low. *(Related non-blocking N6 already
adds `eimuClient.disconnect()` on service stop; the remaining gamepad/BLE
teardown is the decision.)*

## DP6 — No auto-reconnect after a transient BLE drop

**Question.** `autoConnect=false` + full teardown on `onDisconnected`; a
body-worn tracker goes silent for the session on an RF blip until manual
reconnect.

**Options.** (a) Add a bounded auto-reconnect (backoff on involuntary
disconnect, distinct from user disconnect) through a full fresh connect cycle.
(b) Keep manual-only.

**Recommendation:** (a) with a small retry budget (e.g. 5 attempts, exponential
backoff) gated on involuntary disconnects. **Effort:** medium (reconnect state
machine, must not fight the B3 gatt-close teardown).

## DP7 — `bindProcessToNetwork` forces ALL sockets onto Wi-Fi

**Question.** The request omits `NET_CAPABILITY_INTERNET`; while bound,
incidental traffic (UpdateChecker's GitHub GET) is forced onto a possibly
LAN-only Wi-Fi and can fail even with working LTE.

**Options.** (a) Scope the bind to the UDP socket(s) via `Network.bindSocket`.
(b) Document the accepted impact.

**Recommendation:** (a) — per-socket binding is the correct scope, especially on
Wear. **Effort:** medium (thread the Network through the socket factory).

## DP8 — Two divergent magnetometer calibration models

**Question.** The Rust `mag_cal_wizard` (offset-only sphere fit, strong gates,
finite-center guarantee) has no JNI binding; the Kotlin `MagCalibrator`
(bounding-box + soft-iron, weaker gates) is the on-device wire path. Users get
materially different corrections per platform, and the safer algorithm is the
one not wired to mobile.

**Options.** (a) Expose the Rust wizard via JNI and drop the Kotlin path.
(b) Bring the Kotlin gates/model in line with the Rust wizard.

**Recommendation:** (a) — one canonical, well-gated model shared with desktop is
the right long-term shape; document what a stored calibration means. **Effort:**
medium–high (JNI surface + migration of persisted calibrations). *(N26–N30
harden the Kotlin path in the meantime.)*

## DP9 — `foregroundServiceType = SPECIAL_USE`

**Question.** Wire-up is correct and non-crashing, but `SPECIAL_USE` is the FGS
type Google Play scrutinizes most; a reviewer could argue `connectedDevice`
(BLE hosting) fits better.

**Options.** (a) Register the `SPECIAL_USE` justification in Play Console.
(b) Evaluate/switch to `connectedDevice`.

**Recommendation:** (a) if the phone-sensor (non-BLE) role must be covered
`SPECIAL_USE` is defensible; (b) is cleaner only if every tracked source is a
connected device. Document the choice for the Play listing. **Effort:** low
(manifest + Play Console text).

## DP10 — `BasicVqf` is dead code on the JNI/wire path

**Question.** `vqf_alloc` always builds full `Vqf`; `BasicVqf` is only reached
from a unit test, so 6-axis mag-less phones all run full VQF and `BasicVqf`
drifts out of parity.

**Options.** (a) Delete `BasicVqf`. (b) Wire it (JNI constructor + engine
selection) for the 6-axis mag-less case and keep it in parity.

**Recommendation:** (a) delete — full VQF already handles the 6-axis case
(motion-bias just disabled by `has_mag`), so `BasicVqf` earns no runtime keep.
Removing it also erases a parity-drift surface. **Effort:** low. *(CR17 kept it
in parity for now; deletion is the cleaner end state.)*

## DP11 — R8/minify disabled; `consumer-rules.pro` empty

**Question.** Release builds are unshrunk (intentional, readable). The empty
`consumer-rules.pro` gives false confidence that JNI/reflection keep-rules
exist, so a future `isMinifyEnabled=true` would strip `VqfNative` externals and
break native fusion silently.

**Options.** (a) Document minify-off and remove/annotate the empty file.
(b) Before enabling R8, add keep-rules for `native <methods>`, `VqfNative`, and
datastore/Parcelable surfaces.

**Recommendation:** (a) now (annotate the file with a "add keep-rules before
enabling R8" comment), (b) as part of any future decision to enable shrinking.
**Effort:** trivial now.

## DP12 / N57 — Wear IPv4-only picker overwrites a pushed hostname/IPv6

**Question.** `initialOctets(host)` falls back to `192.168.1.100` for any
non-dotted-quad host; tapping "Edit IP" → Save clobbers a valid pushed
hostname/IPv6.

**Options.** (a) Suppress the picker for non-IPv4 hosts. (b) Preserve the
original string when the wheels were not edited. (c) Add hostname/IPv6 entry to
the watch UI.

**Recommendation:** (b) preserve the pushed address unless the user actually
edits the wheels — smallest change, no lost data. **Effort:** low.
