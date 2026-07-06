package cl.matiaspalma.everythingimu.core.tracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import cl.matiaspalma.everythingimu.core.ble.BleDeviceManager
import cl.matiaspalma.everythingimu.core.calibration.CalibrationData
import cl.matiaspalma.everythingimu.core.gamepad.GamepadKinds
import cl.matiaspalma.everythingimu.core.gamepad.GamepadSensorForwarder
import cl.matiaspalma.everythingimu.core.calibration.MagCalibration
import cl.matiaspalma.everythingimu.core.calibration.MagCalibrationSession
import cl.matiaspalma.everythingimu.core.haptics.HapticBridge
import cl.matiaspalma.everythingimu.core.fusion.Quaternion
import cl.matiaspalma.everythingimu.core.fusion.inverse
import cl.matiaspalma.everythingimu.core.fusion.times
import cl.matiaspalma.everythingimu.core.fusion.normalized
import cl.matiaspalma.everythingimu.core.fusion.VqfEngine
import cl.matiaspalma.everythingimu.core.net.ClientStats
import cl.matiaspalma.everythingimu.core.net.ConnectionState
import cl.matiaspalma.everythingimu.core.net.DeviceIdentity
import cl.matiaspalma.everythingimu.core.net.EimuClient
import cl.matiaspalma.everythingimu.core.net.EimuDiscovery
import cl.matiaspalma.everythingimu.core.net.EimuProtocol
import cl.matiaspalma.everythingimu.core.net.WifiBinder
import cl.matiaspalma.everythingimu.core.prefs.AppPrefs
import cl.matiaspalma.everythingimu.core.sensors.SensorRates
import cl.matiaspalma.everythingimu.core.sensors.SensorRepository
import cl.matiaspalma.everythingimu.core.sensors.SensorSnapshot
import cl.matiaspalma.everythingimu.core.sensors.Vec3
import cl.matiaspalma.everythingimu.core.service.TrackingService
import cl.matiaspalma.everythingimu.core.wearable.WearableConfigSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide singleton owning sensor + fusion + network components.
 * UI reads StateFlows here regardless of whether the foreground service is bound.
 */
object TrackingController {

    private var sensors: SensorRepository? = null
    private var eimuClient: EimuClient? = null
    // Completes once the client is constructed (after the async UUID load in
    // ensureInit). connect() awaits this so a connect issued during cold start —
    // before the init coroutine finishes — no longer silently no-ops.
    private val eimuReady = CompletableDeferred<EimuClient>()

    /** Hub role: "tracker" (default), "gamepads", or "both". */
    @Volatile var hubRole: String = "tracker"
        private set

    /** True when core runs inside the Wear OS app — watch is tracker-only. */
    @Volatile private var isWatch: Boolean = false

    private fun roleSendsPhone(): Boolean = isWatch || hubRole != "gamepads"
    private fun roleSendsGamepads(): Boolean = !isWatch && hubRole != "tracker"

    /** Hooked by the BLE controller manager to route desktop rumble to handles 1+. */
    @Volatile var onControllerRumble: ((Int, Float) -> Unit)? = null
    private var prefs: AppPrefs? = null
    private var wifiBinder: WifiBinder? = null
    @Volatile private var pumpJob: Job? = null
    private val pumpLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    // Serializes connect() so overlapping call sites (cold-start auto-connect,
    // the manual button, the network-callback reconnect, Wear) cannot interleave
    // and leak duplicate announce/pump coroutines. connectedHost/Port track the
    // live endpoint so a repeat connect to the same host:port is a no-op.
    private val connectMutex = Mutex()
    @Volatile private var announceJob: Job? = null
    @Volatile private var connectedHost: String? = null
    @Volatile private var connectedPort: Int = 0
    private val mirrorLock = Any()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _clientStats = MutableStateFlow(ClientStats())
    val clientStats: StateFlow<ClientStats> = _clientStats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _tps = MutableStateFlow(0f)
    val tps: StateFlow<Float> = _tps.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    @Volatile var sendRateHz: Int = 100
        private set
    @Volatile var magEnabled: Boolean = true
        private set
    @Volatile var shakeEnabled: Boolean = true
        private set

    private var batteryJob: Job? = null
    private var tpsJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var appCtx: Context? = null
    private var lastShakeNanos: Long = 0L

    val snapshot: StateFlow<SensorSnapshot>
        get() = sensors?.snapshot ?: emptySnapshot

    val rates: StateFlow<SensorRates>
        get() = sensors?.rates ?: emptyRates

    val availability: SensorAvailability
        get() = sensors?.availability ?: SensorAvailability()

    val quaternion: StateFlow<Quaternion>
        get() = sensors?.quaternion ?: emptyQuat

    private val _mountedQuaternion = MutableStateFlow(Quaternion.IDENTITY)
    val mountedQuaternion: StateFlow<Quaternion> = _mountedQuaternion.asStateFlow()

    val fusionAvailable: Boolean get() = VqfEngine.isAvailable()

    /** Measured gyro delivery rate (Hz); 0 until the warmup estimate lands. */
    fun fusionRateHz(): Double = sensors?.measuredRateHz ?: 0.0

    /** Whether the device exposes an OS rotation vector to switch fusion to. */
    val osRotationAvailable: Boolean get() = sensors?.rotationVectorAvailable ?: false

    val magCalibrationSession: MagCalibrationSession = MagCalibrationSession()

    private var hapticBridge: HapticBridge? = null

    fun hapticBridge(): HapticBridge? = hapticBridge

    @Volatile private var calibration: CalibrationData = CalibrationData.EMPTY
    @Volatile private var mountOffset: Quaternion = Quaternion.IDENTITY

    fun calibration(): CalibrationData = calibration

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appCtx = app
        isWatch = app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)
        val appPrefs = AppPrefs(app)
        prefs = appPrefs
        // Bind to Wi-Fi only while a tracking session is live (onServiceStart),
        // not for the whole app lifetime — binding routes the entire process'
        // traffic through Wi-Fi, which is needless when idle.
        wifiBinder = WifiBinder(app)
        hapticBridge = HapticBridge(app)
        // Watches are tracker-only: no BLE controller hosting, no gamepad
        // forwarding — skip both managers entirely.
        if (!isWatch) {
            bleManager = BleDeviceManager(app) { eimuClient }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                gamepadForwarder = GamepadSensorForwarder(app) { eimuClient }
            }
        }
        onControllerRumble = { handle, intensity ->
            if (handle >= GamepadKinds.HANDLE_BASE) {
                // The forwarder only exists on API 31+ (checked at init), but
                // lint can't trace nullability across fields — re-gate here.
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    gamepadForwarder?.rumble(handle, intensity)
                }
            } else {
                bleManager?.rumble(handle, intensity)
            }
        }
        val repo = SensorRepository(app)
        sensors = repo
        repo.onGyroBiasUpdated = { bias ->
            calibration = calibration.copy(gyroBias = bias)
            scope.launch { appPrefs.setGyroBias(bias) }
        }
        scope.launch {
            // Build the client inside a try so a DataStore/prefs failure can
            // never leave eimuReady uncompleted — connect() awaits it, and a
            // never-completed deferred would hang the app forever. Fall back to
            // a default identity on error.
            val e = try {
                calibration = appPrefs.loadCalibration()
                repo.applyCalibration(calibration.gyroBias, calibration.mag)
                val uuid = appPrefs.deviceUuidOrCreate()
                val deviceName = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
                EimuClient(EimuClient.uuidBytes(uuid), deviceName.ifBlank { "Android" })
            } catch (t: Throwable) {
                android.util.Log.e("TrackingController", "init failed; using default identity", t)
                EimuClient(EimuClient.uuidBytes(DEFAULT_UUID), "Android")
            }
            e.onRumble = { handle, intensity ->
                if (handle == PHONE_HANDLE) hapticBridge?.pulse(intensity)
                else onControllerRumble?.invoke(handle, intensity)
            }
            // Bind the client's UDP socket to Wi-Fi per-socket (not the whole
            // process) so the stream leaves the Wi-Fi NIC while incidental
            // traffic (update check) keeps the default route.
            e.onSocketCreated = { s -> wifiBinder?.bindSocket(s) }
            eimuClient = e
            eimuReady.complete(e)
            mirror(e.state, e.stats, e.lastError)
        }
        scope.launch {
            appPrefs.hubRole.collect { role ->
                val prev = hubRole
                hubRole = role
                if (prev != role) applyHubRole()
            }
        }
        scope.launch {
            var first = true
            var rateJob: Job? = null
            appPrefs.sendRateHz.collect {
                sendRateHz = it
                // Skip the initial persisted emission; on a real user change
                // re-announce (debounced) so a dragged rate slider re-emits the
                // new rateHz once, not on every intermediate step.
                if (first) {
                    first = false
                } else {
                    rateJob?.cancel()
                    rateJob = scope.launch {
                        delay(RATE_REANNOUNCE_DEBOUNCE_MS)
                        reannounceIfLive()
                    }
                }
            }
        }
        scope.launch {
            var prevMag: Boolean? = null
            appPrefs.magEnabled.collect {
                magEnabled = it
                sensors?.magInputEnabled = it
                // Re-announce so the desktop rebuilds VQF for the new 6/9DOF
                // mode (hasMag rides the announce, not the per-sample frame).
                // Skip the initial emission — connect() already announces.
                if (prevMag != null && prevMag != it) reannounceIfLive()
                prevMag = it
            }
        }
        scope.launch { appPrefs.shakeRecenter.collect { shakeEnabled = it } }
        scope.launch {
            appPrefs.useOsRotation.collect { sensors?.useOsRotation = it }
        }
        registerNetworkCallback(app)
        startTpsCounter()
        startBatteryReporter(app)
        installShakeDetector()
    }

    private fun registerNetworkCallback(app: Context) {
        if (netCallback != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val state = _connection.value
                if (state == ConnectionState.Failed || state == ConnectionState.Reconnecting) {
                    scope.launch {
                        val h = savedHost()
                        val p = savedPort()
                        if (h.isNotBlank()) connect(h, p)
                    }
                }
            }
        }
        netCallback = cb
        try { cm.registerNetworkCallback(req, cb) } catch (_: Throwable) {}
    }

    private fun startTpsCounter() {
        if (tpsJob != null) return
        tpsJob = scope.launch {
            var prev = 0L
            while (true) {
                delay(1000)
                val cur = _clientStats.value.packetsSent
                _tps.value = (cur - prev).coerceAtLeast(0L).toFloat()
                prev = cur
            }
        }
    }

    private fun startBatteryReporter(app: Context) {
        if (batteryJob != null) return
        batteryJob = scope.launch {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            while (true) {
                val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                _batteryLevel.value = pct
                if (pct in 0..100 && roleSendsPhone()) {
                    eimuClient?.sendBattery(PHONE_HANDLE, pct / 100f, charging = false)
                }
                delay(30_000)
            }
        }
    }

    private fun installShakeDetector() {
        val repo = sensors ?: return
        repo.shakeListener = { mag ->
            if (shakeEnabled && mag > SHAKE_THRESHOLD_MS2) {
                val now = System.nanoTime()
                if (now - lastShakeNanos > SHAKE_COOLDOWN_NS) {
                    lastShakeNanos = now
                    sendRecenter()
                }
            }
        }
    }

    @Volatile private var sensorsHeldForCalibration = false

    fun beginMagCalibration(context: Context) {
        ensureInit(context)
        val repo = sensors ?: return
        // Calibration needs live sensor samples. If the user isn't streaming,
        // spin the sensors up temporarily so the figure-8 works without having
        // to connect first; released again when calibration ends.
        if (!_running.value) {
            repo.start()
            sensorsHeldForCalibration = true
        }
        magCalibrationSession.start()
        repo.magSampleSink = { sample -> magCalibrationSession.feed(sample) }
    }

    fun cancelMagCalibration() {
        sensors?.magSampleSink = null
        magCalibrationSession.discard()
        releaseCalibrationSensors()
    }

    fun applyMagCalibration(): MagCalibration? {
        val result = magCalibrationSession.accept() ?: return null
        sensors?.magSampleSink = null
        sensors?.applyCalibration(calibration.gyroBias, result)
        calibration = calibration.copy(mag = result)
        scope.launch { prefs?.setMagCalibration(result) }
        releaseCalibrationSensors()
        return result
    }

    private fun releaseCalibrationSensors() {
        if (sensorsHeldForCalibration && !_running.value) {
            sensors?.stop()
        }
        sensorsHeldForCalibration = false
    }

    fun resetCalibration() {
        val identity = CalibrationData.EMPTY
        calibration = identity
        sensors?.applyCalibration(identity.gyroBias, identity.mag)
        scope.launch { prefs?.clearCalibration() }
    }

    fun currentGyroBias(): Vec3 = sensors?.gyroBiasValue() ?: calibration.gyroBias
    fun gyroBiasCalibrated(): Boolean = sensors?.gyroBiasCalibrated() == true

    fun start(context: Context) {
        ensureInit(context)
        TrackingService.start(context)
    }

    fun stop(context: Context) {
        TrackingService.stop(context)
    }

    suspend fun connect(host: String, port: Int) {
        // Await the client if the init coroutine hasn't constructed it yet.
        val e = eimuClient ?: eimuReady.await()
        // The persisted port may predate hub mode (SlimeVR default 6969);
        // substitute the eimu listener port unless the user typed a custom one.
        val effectivePort = if (port == 6969) EimuProtocol.DEFAULT_PORT else port
        // Serialize: two overlapping connects would each re-run mirror/pump and
        // launch a fresh untracked announce warmup loop.
        connectMutex.withLock {
            // Already streaming to this exact endpoint — nothing to do.
            if (e.state.value == ConnectionState.Connected &&
                connectedHost == host && connectedPort == effectivePort
            ) {
                return
            }
            withContext(Dispatchers.IO) { e.connect(host, effectivePort) }
            mirror(e.state, e.stats, e.lastError)
            // EimuClient.connect() swallows all failures into state=Failed and
            // returns normally; do not announce a phantom tracker, install the
            // raw sink, or persist the address unless the socket is actually up.
            val st = e.state.value
            if (st != ConnectionState.Connected && st != ConnectionState.Connecting) {
                return
            }
            connectedHost = host
            connectedPort = effectivePort
            prefs?.setServer(host, port)
            if (roleSendsPhone()) {
                announcePhone(e)
                startRawPump(e)
            } else {
                announcePhoneHapticsOnly(e)
            }
            if (roleSendsGamepads()) startGamepadForwarder()
        }
    }

    /** Broadcast HELLO on the LAN; returns the desktop's IP or null. */
    suspend fun discoverDesktop(): String? {
        val uuid = prefs?.deviceUuidOrCreate().orEmpty().ifBlank { DEFAULT_UUID }
        val name = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .trim().ifBlank { "Android" }
        // Ensure the Wi-Fi network is requested even when discovery runs before
        // a tracking session starts, and bind the probe socket to it so the
        // broadcast leaves Wi-Fi (Wear's default proxy can't reach the LAN).
        wifiBinder?.bindToWifi()
        return EimuDiscovery.discover(
            EimuClient.uuidBytes(uuid),
            name,
            bindSocket = { s -> wifiBinder?.bindSocket(s) },
        )
    }

    fun disconnect() {
        eimuClient?.disconnect()
        stopGamepadForwarder()
        stopRawPump()
        connectedHost = null
        connectedPort = 0
    }

    /**
     * Re-announce the phone tracker when a live-affecting setting (mag mode,
     * send rate) changes mid-session so the desktop rebuilds its fusion for the
     * new descriptor. No-op unless connected and currently acting as a tracker.
     */
    private fun reannounceIfLive() {
        val e = eimuClient ?: return
        val connected = _connection.value == ConnectionState.Connected ||
            _connection.value == ConnectionState.Connecting
        if (connected && roleSendsPhone()) {
            // The desktop ignores a re-announce of a live handle, so drop it
            // first to force a fresh descriptor (same pattern as applyHubRole).
            e.removeDevice(PHONE_HANDLE)
            announcePhone(e)
        }
    }

    /** Recenter: BUTTON message to the eimu hub. */
    fun sendRecenter() {
        if (roleSendsPhone()) eimuClient?.sendButton(PHONE_HANDLE, EimuProtocol.RESET_YAW)
        // Local feedback: the desktop resets its own fusion + SlimeVR, but
        // the on-phone orientation card runs a separate VQF — zero it too
        // so the user sees the recenter take effect immediately.
        autoMount()
    }

    fun eimuClientOrNull(): EimuClient? = eimuClient

    private var bleManager: BleDeviceManager? = null
    fun bleManager(): BleDeviceManager? = bleManager

    private var gamepadForwarder: GamepadSensorForwarder? = null
    fun gamepadForwarder(): GamepadSensorForwarder? = gamepadForwarder

    // The forwarder only exists on API 31+ (gated at init), but lint can't
    // trace nullability across fields — every call funnels through these.
    private fun startGamepadForwarder() {
        if (android.os.Build.VERSION.SDK_INT >= 31) gamepadForwarder?.start()
    }

    private fun stopGamepadForwarder() {
        if (android.os.Build.VERSION.SDK_INT >= 31) gamepadForwarder?.stop()
    }

    private val mirrorJobs = mutableListOf<Job>()

    /** Re-point the UI-facing state flows at whichever client is active. */
    private fun mirror(
        stateF: StateFlow<ConnectionState>,
        statsF: StateFlow<ClientStats>,
        errF: StateFlow<String?>,
    ) {
        synchronized(mirrorLock) {
            mirrorJobs.forEach { it.cancel() }
            mirrorJobs.clear()
            mirrorJobs += scope.launch { stateF.collectStateInto(_connection) }
            mirrorJobs += scope.launch { statsF.collectStateInto(_clientStats) }
            mirrorJobs += scope.launch { errF.collectStateInto(_lastError) }
        }
    }

    private fun announcePhone(e: EimuClient) {
        // Cancel any prior warmup loop so repeat announces (connect, hub-role
        // switch, mag/rate re-announce) don't stack untracked coroutines.
        announceJob?.cancel()
        announceJob = scope.launch {
            val mac = deviceMac()
            suspend fun announce(rate: Int) {
                e.announceDevice(
                    EimuClient.AnnouncedDevice(
                        handle = PHONE_HANDLE,
                        kind = if (isWatch) EimuProtocol.KIND_WATCH else EimuProtocol.KIND_PHONE,
                        mac = mac,
                        hasMag = magEnabled && availability.mag,
                        hasBattery = true,
                        hasRumble = hapticBridge?.isVibratorAvailable() == true,
                        rateHz = if (rate in 20..1000) rate else 100,
                        name = android.os.Build.MODEL ?: "Android",
                    ),
                )
            }
            // What the desktop receives is the sensor rate capped by the send
            // throttle — announce that, not the raw gyro rate.
            fun effectiveRate() =
                minOf((sensors?.measuredRateHz ?: 0.0).toInt(), sendRateHz)
            announce(effectiveRate())
            // The gyro-rate warmup usually hasn't finished at connect time, so
            // the first announce carries the 100 Hz fallback. Re-announce once
            // the measured rate lands so the desktop shows the real figure
            // (fusion dt is unaffected — it follows the sample timestamps).
            withTimeoutOrNull(15_000) {
                while ((sensors?.measuredRateHz ?: 0.0) <= 0.0) delay(500)
            } ?: return@launch
            announce(effectiveRate())
        }
    }

    /**
     * Gamepads-only role: the phone is not a tracker, but desktop haptics
     * rules can still target it. `rateHz = 0` marks a haptics-only endpoint —
     * the desktop registers it for rumble routing without ever creating a
     * SlimeVR tracker.
     */
    private fun announcePhoneHapticsOnly(e: EimuClient) {
        scope.launch {
            e.announceDevice(
                EimuClient.AnnouncedDevice(
                    handle = PHONE_HANDLE,
                    kind = if (isWatch) EimuProtocol.KIND_WATCH else EimuProtocol.KIND_PHONE,
                    mac = deviceMac(),
                    hasMag = false,
                    hasBattery = false,
                    hasRumble = hapticBridge?.isVibratorAvailable() == true,
                    rateHz = 0,
                    name = android.os.Build.MODEL ?: "Android",
                ),
            )
        }
    }

    /**
     * eimu hub mode: forward raw bias-corrected samples at sendRateHz; the
     * desktop runs fusion. Local VQF keeps running for the UI orientation card.
     */
    private fun startRawPump(e: EimuClient) {
        val repo = sensors ?: return
        // Confine the pump lifecycle to one lock: connect(), onServiceStart(),
        // and applyHubRole() can all (re)start it from different dispatchers, and
        // disconnect()/onServiceStop() null the sink concurrently.
        synchronized(pumpLock) {
            pumpJob?.cancel()
            pumpJob = null
            var nextSendNanos = 0L
            repo.rawSampleSink = { gyro, accel, mag, tsNanos ->
                val now = System.nanoTime()
                val intervalNs = 1_000_000_000L / sendRateHz.coerceAtLeast(20)
                if (now >= nextSendNanos) {
                    e.sendImu(
                        PHONE_HANDLE,
                        tsNanos / 1_000,
                        floatArrayOf(gyro.x, gyro.y, gyro.z),
                        accel,
                        mag,
                    )
                    // Advance the deadline in exact intervals instead of resetting
                    // it to `now`: sends can only happen on sensor-event boundaries
                    // (~2.5 ms at 395 Hz), and re-anchoring rounds every period up
                    // to a whole number of events — a 200 Hz target decimated to
                    // ~150 Hz. Fall back to re-anchoring only after a long gap.
                    nextSendNanos =
                        if (now - nextSendNanos < intervalNs) nextSendNanos + intervalNs
                        else now + intervalNs
                }
            }
            // UI-only mirror: the desktop owns fusion, but the local VQF keeps
            // running — surface it at ~60 Hz so the orientation card stays live.
            pumpJob = scope.launch {
                var lastUiNanos = 0L
                repo.quaternion.collect { q ->
                    val now = System.nanoTime()
                    if (now - lastUiNanos >= UI_QUAT_INTERVAL_NANOS) {
                        _mountedQuaternion.value = applyMountOffset(q)
                        lastUiNanos = now
                    }
                }
            }
        }
    }

    private fun stopRawPump() {
        synchronized(pumpLock) {
            sensors?.rawSampleSink = null
            pumpJob?.cancel()
            pumpJob = null
        }
    }

    suspend fun persistServer(host: String, port: Int) {
        prefs?.setServer(host, port)
    }

    /**
     * Phone-side persist that also pushes the address to a paired watch over the
     * Wearable Data Layer. The wear app must NOT call this — it would re-emit the
     * DataItem its own listener just consumed and loop. Wear uses [persistServer].
     */
    suspend fun persistAndSyncServer(context: Context, host: String, port: Int) {
        prefs?.setServer(host, port)
        if (host.isNotBlank()) {
            scope.launch {
                // push() returns Result so a failed putDataItem (Play Services
                // missing/updating) is detectable — surface it instead of
                // silently leaving the watch on the stale address.
                WearableConfigSender.push(context, host, port).onFailure {
                    android.util.Log.w(
                        "TrackingController",
                        "Watch config push failed; watch keeps its previous address",
                        it,
                    )
                }
            }
        }
    }

    /** Wear waits on this for a phone-pushed host before falling back to the picker. */
    fun serverHostFlow(): kotlinx.coroutines.flow.Flow<String>? = prefs?.serverHost

    suspend fun autoConnectEnabled(): Boolean = prefs?.autoConnect?.first() ?: true

    suspend fun savedHost(): String = prefs?.serverHost?.first() ?: ""
    suspend fun savedPort(): Int = (prefs?.serverPort?.first() ?: 6969).let { if (it == 0) 6969 else it }
    suspend fun deviceUuid(): String = prefs?.deviceUuidOrCreate().orEmpty()

    suspend fun deviceMac(): ByteArray {
        val uuid = prefs?.deviceUuidOrCreate().orEmpty().ifBlank { DEFAULT_UUID }
        return DeviceIdentity.macFromUuid(uuid)
    }

    internal fun onServiceStart() {
        wifiBinder?.bindToWifi()
        sensors?.start()
        _running.value = true
        if (roleSendsPhone()) eimuClient?.let { startRawPump(it) }
    }

    /**
     * Live hub-role switch while connected: tear down or bring up the phone
     * tracker and the gamepad forwarder without touching the socket.
     */
    private fun applyHubRole() {
        val e = eimuClient ?: return
        val connected =
            _connection.value == ConnectionState.Connected || _connection.value == ConnectionState.Connecting
        if (roleSendsPhone()) {
            if (connected) {
                // The desktop ignores re-announces of a live handle, so drop
                // the previous (possibly haptics-only) registration first to
                // force a fresh one with full tracker metadata.
                e.removeDevice(PHONE_HANDLE)
                announcePhone(e)
                if (_running.value) startRawPump(e)
            }
        } else {
            sensors?.rawSampleSink = null
            e.removeDevice(PHONE_HANDLE)
            // Keep the phone reachable for desktop haptics rules even when it
            // is not a tracker: re-register as a rate-0 haptics-only endpoint.
            if (connected) announcePhoneHapticsOnly(e)
        }
        if (roleSendsGamepads()) {
            if (_connection.value != ConnectionState.Disconnected) startGamepadForwarder()
        } else {
            stopGamepadForwarder()
            bleManager?.let { mgr ->
                for (entry in mgr.entries.value) {
                    if (entry.handle >= 0) mgr.disconnect(entry.address)
                }
            }
        }
    }

    internal fun onServiceStop() {
        // The notification "Stop" and onDestroy both route here. Tear down the
        // socket + gamepad forwarder + raw pump too (via disconnect()); leaving
        // the client live kept the HELLO/announce loops running so the desktop
        // still saw a phantom live tracker after a stop.
        disconnect()
        // Stop everything the user perceives as tracking: BLE controllers keep a
        // live link (and battery drain) after a stop otherwise. This is a
        // user-intended disconnect, so it must not trigger auto-reconnect.
        stopBleControllers()
        sensors?.stop()
        wifiBinder?.release()
        _running.value = false
        mountOffset = Quaternion.IDENTITY
        _mountedQuaternion.value = Quaternion.IDENTITY
    }

    /** Disconnect every connected BLE controller (intentional — no reconnect). */
    private fun stopBleControllers() {
        bleManager?.let { mgr ->
            for (entry in mgr.entries.value) {
                if (entry.handle >= 0) mgr.disconnect(entry.address)
            }
        }
    }

    /**
     * Full teardown counterpart to [ensureInit]: releases the sensor stack,
     * network client, BLE + gamepad managers, and haptics, and unregisters the
     * connectivity callback so no owned resource outlives the process by
     * accident. Intended for process/app shutdown only — a normal stop-tracking
     * cycle goes through [onServiceStop], because the UI reads these flows even
     * while the foreground service is unbound.
     */
    @Synchronized
    fun shutdown() {
        disconnect()
        unregisterNetworkCallback()
        synchronized(mirrorLock) {
            mirrorJobs.forEach { it.cancel() }
            mirrorJobs.clear()
        }
        announceJob?.cancel()
        announceJob = null
        batteryJob?.cancel()
        batteryJob = null
        tpsJob?.cancel()
        tpsJob = null
        sensors?.release()
        eimuClient?.shutdown()
        hapticBridge?.shutdown()
        bleManager?.shutdown()
        if (android.os.Build.VERSION.SDK_INT >= 31) gamepadForwarder?.shutdown()
        wifiBinder?.release()
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        val cm = appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try { cm?.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
    }

    fun autoMount(): Boolean {
        val current = sensors?.quaternion?.value ?: return false
        mountOffset = current.inverse()
        return true
    }

    private fun applyMountOffset(raw: Quaternion): Quaternion = (mountOffset * raw).normalized()

    private suspend fun <T> StateFlow<T>.collectStateInto(target: MutableStateFlow<T>) {
        collect { target.value = it }
    }

    private val emptySnapshot = MutableStateFlow(SensorSnapshot()).asStateFlow()
    private val emptyRates = MutableStateFlow(SensorRates()).asStateFlow()
    private val emptyQuat = MutableStateFlow(Quaternion.IDENTITY).asStateFlow()

    private const val PHONE_HANDLE = 0
    private const val UI_QUAT_INTERVAL_NANOS = 16_000_000L
    private const val DEFAULT_UUID = "00000000-0000-0000-0000-000000000000"
    private const val SHAKE_THRESHOLD_MS2 = 25f
    private const val SHAKE_COOLDOWN_NS = 1_500_000_000L
    // Debounce a dragged send-rate slider so mid-session re-announce fires once
    // after the value settles rather than on every intermediate step.
    private const val RATE_REANNOUNCE_DEBOUNCE_MS = 1_000L
}

data class SensorAvailability(
    val gyro: Boolean = false,
    val accel: Boolean = false,
    val mag: Boolean = false,
)
