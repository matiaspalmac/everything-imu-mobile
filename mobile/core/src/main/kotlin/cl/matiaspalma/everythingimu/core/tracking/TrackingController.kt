package cl.matiaspalma.everythingimu.core.tracking

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import cl.matiaspalma.everythingimu.core.ble.BleDeviceManager
import cl.matiaspalma.everythingimu.core.calibration.CalibrationData
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
import cl.matiaspalma.everythingimu.core.net.EimuClient
import cl.matiaspalma.everythingimu.core.net.EimuProtocol
import cl.matiaspalma.everythingimu.core.net.SlimeVrClient
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide singleton owning sensor + fusion + network components.
 * UI reads StateFlows here regardless of whether the foreground service is bound.
 */
object TrackingController {

    private var sensors: SensorRepository? = null
    private var client: SlimeVrClient? = null
    // Completes once the client is constructed (after the async UUID/mac load in
    // ensureInit). connect() awaits this so a connect issued during cold start —
    // before the init coroutine finishes — no longer silently no-ops.
    private val clientReady = CompletableDeferred<SlimeVrClient>()
    private var eimuClient: EimuClient? = null
    private val eimuReady = CompletableDeferred<EimuClient>()

    /** "eimu" (desktop hub, default) or "slimevr" (legacy owoTrack-direct). */
    @Volatile var targetMode: String = "eimu"
        private set

    /** Hub role in eimu mode: "tracker" (default), "gamepads", or "both". */
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
    private var pumpJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

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
            if (handle >= GamepadSensorForwarder.HANDLE_BASE) {
                gamepadForwarder?.rumble(handle, intensity)
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
            calibration = appPrefs.loadCalibration()
            repo.applyCalibration(calibration.gyroBias, calibration.mag)
            val uuid = appPrefs.deviceUuidOrCreate()
            val mac = SlimeVrClient.macFromUuid(uuid)
            val c = SlimeVrClient(mac)
            client = c
            clientReady.complete(c)
            mirror(c.state, c.stats, c.lastError)
            val deviceName = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
            val e = EimuClient(EimuClient.uuidBytes(uuid), deviceName.ifBlank { "Android" })
            e.onRumble = { handle, intensity ->
                if (handle == PHONE_HANDLE) hapticBridge?.pulse(intensity)
                else onControllerRumble?.invoke(handle, intensity)
            }
            eimuClient = e
            eimuReady.complete(e)
        }
        scope.launch { appPrefs.targetMode.collect { targetMode = it } }
        scope.launch {
            appPrefs.hubRole.collect { role ->
                val prev = hubRole
                hubRole = role
                if (prev != role) applyHubRole()
            }
        }
        scope.launch { appPrefs.sendRateHz.collect { sendRateHz = it } }
        scope.launch {
            appPrefs.magEnabled.collect {
                magEnabled = it
                sensors?.magInputEnabled = it
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
                val state = client?.state?.value ?: return
                if (state == ConnectionState.Failed || state == ConnectionState.Reconnecting) {
                    scope.launch {
                        val h = savedHost()
                        val p = savedPort()
                        if (h.isNotBlank()) client?.connect(h, p)
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
                // _clientStats mirrors whichever client (eimu/slimevr) is active.
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
                if (pct in 0..100) {
                    val voltage = readBatteryVoltage(app)
                    if (targetMode == "eimu") {
                        if (roleSendsPhone()) {
                            eimuClient?.sendBattery(PHONE_HANDLE, pct / 100f, charging = false)
                        }
                    } else {
                        client?.sendBattery(voltage, pct / 100f)
                    }
                }
                delay(30_000)
            }
        }
    }

    private fun readBatteryVoltage(app: Context): Float {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return if (mv > 0) mv / 1000f else 3.7f
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
        if (targetMode == "eimu") {
            // Await the client if the init coroutine hasn't constructed it yet.
            val e = eimuClient ?: eimuReady.await()
            // The persisted port predates hub mode (SlimeVR default 6969);
            // substitute the eimu listener port unless the user typed a custom one.
            val effectivePort = if (port == 6969) EimuProtocol.DEFAULT_PORT else port
            withContext(Dispatchers.IO) { e.connect(host, effectivePort) }
            mirror(e.state, e.stats, e.lastError)
            prefs?.setServer(host, port)
            if (roleSendsPhone()) {
                announcePhone(e)
                startRawPump(e)
            } else {
                announcePhoneHapticsOnly(e)
            }
            if (roleSendsGamepads()) gamepadForwarder?.start()
        } else {
            val c = client ?: clientReady.await()
            withContext(Dispatchers.IO) { c.connect(host, port) }
            mirror(c.state, c.stats, c.lastError)
            prefs?.setServer(host, port)
            startQuaternionPump()
        }
    }

    fun disconnect() {
        client?.disconnect()
        eimuClient?.disconnect()
        gamepadForwarder?.stop()
        sensors?.rawSampleSink = null
        pumpJob?.cancel()
        pumpJob = null
    }

    /** Recenter: BUTTON to eimu hub, or owoTrack BUTTON_PUSHED (60) direct. */
    fun sendRecenter() {
        if (targetMode == "eimu") {
            if (roleSendsPhone()) eimuClient?.sendButton(PHONE_HANDLE, EimuProtocol.RESET_YAW)
        } else {
            client?.sendButtonPushed()
        }
    }

    fun eimuClientOrNull(): EimuClient? = eimuClient

    private var bleManager: BleDeviceManager? = null
    fun bleManager(): BleDeviceManager? = bleManager

    private var gamepadForwarder: GamepadSensorForwarder? = null
    fun gamepadForwarder(): GamepadSensorForwarder? = gamepadForwarder

    private val mirrorJobs = mutableListOf<Job>()

    /** Re-point the UI-facing state flows at whichever client is active. */
    private fun mirror(
        stateF: StateFlow<ConnectionState>,
        statsF: StateFlow<ClientStats>,
        errF: StateFlow<String?>,
    ) {
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs.clear()
        mirrorJobs += scope.launch { stateF.collectStateInto(_connection) }
        mirrorJobs += scope.launch { statsF.collectStateInto(_clientStats) }
        mirrorJobs += scope.launch { errF.collectStateInto(_lastError) }
    }

    private fun announcePhone(e: EimuClient) {
        scope.launch {
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
            announce((sensors?.measuredRateHz ?: 0.0).toInt())
            // The gyro-rate warmup usually hasn't finished at connect time, so
            // the first announce carries the 100 Hz fallback. Re-announce once
            // the measured rate lands so the desktop shows the real figure
            // (fusion dt is unaffected — it follows the sample timestamps).
            withTimeoutOrNull(15_000) {
                while ((sensors?.measuredRateHz ?: 0.0) <= 0.0) delay(500)
            } ?: return@launch
            announce((sensors?.measuredRateHz ?: 0.0).toInt())
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
        pumpJob?.cancel()
        pumpJob = null
        val repo = sensors ?: return
        var lastSendNanos = 0L
        repo.rawSampleSink = { gyro, accel, mag, tsNanos ->
            val now = System.nanoTime()
            val intervalNs = 1_000_000_000L / sendRateHz.coerceAtLeast(20)
            if (now - lastSendNanos >= intervalNs) {
                e.sendImu(
                    PHONE_HANDLE,
                    tsNanos / 1_000,
                    floatArrayOf(gyro.x, gyro.y, gyro.z),
                    accel,
                    mag,
                )
                lastSendNanos = now
            }
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
            scope.launch { WearableConfigSender.push(context, host, port) }
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
        return SlimeVrClient.macFromUuid(uuid)
    }

    internal fun onServiceStart() {
        wifiBinder?.bindToWifi()
        sensors?.start()
        _running.value = true
        if (targetMode == "eimu") {
            if (roleSendsPhone()) eimuClient?.let { startRawPump(it) }
        } else {
            startQuaternionPump()
        }
    }

    /**
     * Live hub-role switch while connected: tear down or bring up the phone
     * tracker and the gamepad forwarder without touching the socket.
     */
    private fun applyHubRole() {
        if (targetMode != "eimu") return
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
            if (_connection.value != ConnectionState.Disconnected) gamepadForwarder?.start()
        } else {
            gamepadForwarder?.stop()
            bleManager?.let { mgr ->
                for (entry in mgr.entries.value) {
                    if (entry.handle >= 0) mgr.disconnect(entry.address)
                }
            }
        }
    }

    internal fun onServiceStop() {
        sensors?.stop()
        sensors?.rawSampleSink = null
        wifiBinder?.release()
        _running.value = false
        pumpJob?.cancel()
        pumpJob = null
        mountOffset = Quaternion.IDENTITY
        _mountedQuaternion.value = Quaternion.IDENTITY
    }

    private fun startQuaternionPump() {
        if (pumpJob != null) return
        val flow = sensors?.quaternion ?: return
        pumpJob = scope.launch {
            var lastSendNanos = 0L
            var lastUiNanos = 0L
            flow.collect { q ->
                val mounted = applyMountOffset(q)
                val now = System.nanoTime()
                // UI quaternion at ~60 Hz: the flow emits at gyro rate, and
                // pushing every sample makes the fusion card recompose at sensor
                // rate. The network send below keeps its own (sendRateHz) cadence.
                if (now - lastUiNanos >= UI_QUAT_INTERVAL_NANOS) {
                    _mountedQuaternion.value = mounted
                    lastUiNanos = now
                }
                val intervalNs = 1_000_000_000L / sendRateHz.coerceAtLeast(20)
                if (now - lastSendNanos >= intervalNs) {
                    client?.sendRotation(floatArrayOf(mounted.w, mounted.x, mounted.y, mounted.z))
                    // owoTrack-compat: also stream linear acceleration so SlimeVR
                    // shows accel data (parity with owoTrack).
                    sensors?.latestLinearAccel()?.let { client?.sendAccel(it) }
                    lastSendNanos = now
                }
            }
        }
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
}

data class SensorAvailability(
    val gyro: Boolean = false,
    val accel: Boolean = false,
    val mag: Boolean = false,
)
