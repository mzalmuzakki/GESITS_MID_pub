package io.github.mzalmuzakki.gesitsmid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/** Shared state between the service and the screen. */
object Bike {
    val telemetry = Telemetry()
    @Volatile var status = "Not connected"
    @Volatile var connected = false
    @Volatile var lastFrame: Bitmap? = null
    @Volatile var lastFrameInfo = ""
    @Volatile var running = false
    /** Range test (MID tab): when it started, 0 = not running. Phase 1 quiet, phase 2 polling only. */
    @Volatile var rangeTestAt = 0L
    const val RANGE_TEST_VALUE = 123f
    const val RANGE_TEST_QUIET_MS = 20_000L
    const val RANGE_TEST_MS = 40_000L
    fun rangeTestElapsed(now: Long = System.currentTimeMillis()): Long? =
        rangeTestAt.takeIf { it > 0L }?.let { now - it }?.takeIf { it < RANGE_TEST_MS }

    /** When the MID last sent anything (watchdog: a link that went silent is reconnected). */
    @Volatile var lastRxAt = 0L
    /** One-off commands from the UI (text, picture...), sent by the service thread. */
    val outbox = LinkedBlockingQueue<String>()
    val log = ArrayDeque<String>()
    /** Adaptive range learner; guard access with synchronized(range). */
    val range = RangeEstimator()
    @Volatile var rangeLoaded = false

    /** Energy-based adaptive range (battery capacity x your consumption); guarded by synchronized(range) too. */
    val energy = EnergyRange()

    fun loadRange(s: Settings) = synchronized(range) {
        if (!rangeLoaded) { range.load(s.rangeState); energy.load(s.energyState); rangeLoaded = true }
        range.priorKmPerPercent = s.kmPerPercent
    }
    /** Range now: from energy (normal case); the older km-per-% learner only if no power data has ever been seen. */
    fun rangeKm(): Float? = Bike.telemetry.f("SOC")?.let { soc ->
        synchronized(range) { if (energy.hasData || range.learnedPercent <= 0f) energy.rangeKm(soc) else range.rangeKm(soc) }
    }

    /** Last ~10 minutes of per-second samples for the Diagnosis charts; guard with synchronized(samples). */
    val samples = ArrayDeque<Sample>()
    fun addSample(x: Sample) = synchronized(samples) { samples.addLast(x); while (samples.size > 600) samples.removeFirst() }

    /** True while the Speedometer or Diagnosis screen is visible: poll live values faster. */
    @Volatile var uiFast = false

    /** Battery cycles / charging (guard with synchronized(battery)). */
    val battery = BatteryTracker()
    @Volatile var batteryLoaded = false
    fun loadBattery(s: Settings) = synchronized(battery) { if (!batteryLoaded) { battery.load(s.batteryState); batteryLoaded = true } }

    /** Speed warning state (service thread). */
    val speedWarn = SpeedWarning()

    /** Latest Google Maps instruction (from MapsNotifListener) and when it arrived. */
    @Volatile var nav: NavParser.Instruction? = null
    @Volatile var navAt = 0L
    fun navFresh(now: Long = System.currentTimeMillis()) = nav != null && now - navAt < 90_000

    /** When each write was last sent, and when the MID last confirmed it (MID replies with the key). */
    val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val ackAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val ACK_KEYS = listOf("MAPS", "TIME", "NAV", "TRIP", "ARROW")

    /** Automatic ride recording and alarms (used only by the service thread). */
    val recorder = RideRecorder()
    val alarms = AlarmMonitor()

    /** Trip & efficiency; guard access with synchronized(trip). */
    val trip = TripTracker()
    @Volatile var tripLoaded = false
    fun loadTrip(s: Settings) = synchronized(trip) { if (!tripLoaded) { trip.load(s.tripState); tripLoaded = true } }

    fun log(msg: String) = synchronized(log) {
        log.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg)
        while (log.size > 200) log.removeFirst()
    }
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var linkType: String get() = p.getString("link", "classic")!!; set(v) = p.edit().putString("link", v).apply()
    var address: String? get() = p.getString("address", null); set(v) = p.edit().putString("address", v).apply()
    var deviceName: String? get() = p.getString("deviceName", null); set(v) = p.edit().putString("deviceName", v).apply()
    /** "dashboard", "picture", "grid" (calibration) or "off" */
    var screenMode: String get() = p.getString("screen", "dashboard")!!; set(v) = p.edit().putString("screen", v).apply()
    var sendRange: Boolean get() = p.getBoolean("range", true); set(v) = p.edit().putBoolean("range", v).apply()
    var syncTime: Boolean get() = p.getBoolean("time", true); set(v) = p.edit().putBoolean("time", v).apply()
    /** Show today's date on the MID text line when no own text is set (like GRID Mobile). */
    var navDate: Boolean get() = p.getBoolean("navDate", true); set(v) = p.edit().putBoolean("navDate", v).apply()
    var navText: String get() = p.getString("nav", "")!!; set(v) = p.edit().putString("nav", v).apply()
    var kmPerPercent: Float get() = p.getFloat("kmpp", 0.5f); set(v) = p.edit().putFloat("kmpp", v).apply()
    var tripState: String? get() = p.getString("tripState", null); set(v) = p.edit().putString("tripState", v).apply()
    /** "auto" (BIG while riding, MAIN/TRIP when stopped), "main", "trip", "big", "rotate" */
    var dashStyle: String get() = p.getString("dashStyle", "auto")!!; set(v) = p.edit().putString("dashStyle", v).apply()
    // Alarms, savings comparison, weekly target, parking location
    var alarmsOn: Boolean get() = p.getBoolean("alarms", true); set(v) = p.edit().putBoolean("alarms", v).apply()
    var socLow: Float get() = p.getFloat("socLow", 20f); set(v) = p.edit().putFloat("socLow", v).apply()
    var kmPerLitre: Float get() = p.getFloat("kmL", 40f); set(v) = p.edit().putFloat("kmL", v).apply()
    var rpPerLitre: Float get() = p.getFloat("rpL", 10_000f); set(v) = p.edit().putFloat("rpL", v).apply()
    var rpPerKwh: Float get() = p.getFloat("rpKwh", 1444.7f); set(v) = p.edit().putFloat("rpKwh", v).apply()
    var weeklyTargetKm: Float get() = p.getFloat("weekTarget", 100f); set(v) = p.edit().putFloat("weekTarget", v).apply()
    var rememberParking: Boolean get() = p.getBoolean("parking", false); set(v) = p.edit().putBoolean("parking", v).apply()
    var parkLat: Float get() = p.getFloat("parkLat", 0f); set(v) = p.edit().putFloat("parkLat", v).apply()
    var parkLon: Float get() = p.getFloat("parkLon", 0f); set(v) = p.edit().putFloat("parkLon", v).apply()
    var parkTime: Long get() = p.getLong("parkTime", 0L); set(v) = p.edit().putLong("parkTime", v).apply()
    /** "auto" (phone language), "en" or "id". */
    var language: String get() = p.getString("lang", "auto")!!; set(v) = p.edit().putString("lang", v).apply()
    /** Show Google Maps turn-by-turn directions on the MID (needs notification access). */
    var navOn: Boolean get() = p.getBoolean("navOn", true); set(v) = p.edit().putBoolean("navOn", v).apply()
    /** Speed warning on the MID above this speed (0 = off). */
    var speedLimit: Int get() = p.getInt("speedLimit", 0); set(v) = p.edit().putInt("speedLimit", v).apply()
    /** Notify when charging reaches this %. */
    var chargeTarget: Int get() = p.getInt("chargeTarget", 80); set(v) = p.edit().putInt("chargeTarget", v).apply()
    /** Daily battery-health log: "yyyymmdd:soh;..." (last 120 days). */
    var sohLog: String get() = p.getString("sohLog", "")!!; set(v) = p.edit().putString("sohLog", v).apply()
    var batteryState: String? get() = p.getString("batteryState", null); set(v) = p.edit().putString("batteryState", v).apply()
    /** Connect to the saved bike as soon as the app is opened. */
    var autoConnect: Boolean get() = p.getBoolean("autoConnect", true); set(v) = p.edit().putBoolean("autoConnect", v).apply()
    /** Stop the service after the bike has been off this many minutes (0 = never). */
    var autoStopMin: Int get() = p.getInt("autoStop", 60); set(v) = p.edit().putInt("autoStop", v).apply()
    var rangeState: String? get() = p.getString("rangeState", null); set(v) = p.edit().putString("rangeState", v).apply()
    var energyState: String? get() = p.getString("energyState", null); set(v) = p.edit().putString("energyState", v).apply()
    var margins: ScreenRenderer.Margins
        get() = ScreenRenderer.Margins(p.getInt("mL", 0), p.getInt("mT", 0), p.getInt("mR", 0), p.getInt("mB", 0))
        set(v) = p.edit().putInt("mL", v.left).putInt("mT", v.top).putInt("mR", v.right).putInt("mB", v.bottom).apply()
    var frameSeconds: Int get() = p.getInt("frameSec", 3); set(v) = p.edit().putInt("frameSec", v).apply()
}

@SuppressLint("MissingPermission")
class MidService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "mid"
        private const val NOTIF_ID = 1
        private const val TRIP_EVERY_MS = 250L

        fun start(ctx: Context) {
            val i = Intent(ctx, MidService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, MidService::class.java).setAction(ACTION_STOP)) }
    }

    @Volatile private var worker: Thread? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Keeps the CPU awake while connected, so the link survives screen-off and app switching. */
    private fun holdWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "gesitsmid:link").also { it.acquire() }
        } else if (!on) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }
    @Volatile private var link: MidLink? = null
    private val parser = LineParser()
    private var frameNo = 0
    private var lastSend = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Bike.running = false
            worker?.interrupt()
            link?.close()
            holdWakeLock(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) Bike.log("Service restarted by Android (the app had been stopped in the background)")
        goForeground(fromApp = intent != null)
        holdWakeLock(true)
        if (worker?.isAlive != true) {
            Bike.running = true
            worker = Thread({ runLoop() }, "mid-worker").also { it.start() }
        }
        return START_STICKY
    }

    private fun goForeground(fromApp: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("Bike connection"), NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif(notifText())
        // Location type only when parking location is on, permitted, and started from the app (Android rule).
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 29 && fromApp && Settings(this).rememberParking && Parking.hasPermission(this))
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 29) {
            try { startForeground(NOTIF_ID, n, type) }
            catch (e: Exception) { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        } else startForeground(NOTIF_ID, n)
    }

    private fun setStatus(s: String) { Bike.status = s; Bike.log(s); refreshNotif() }

    /** What the notification says: the real state, never a stale "connected". */
    private fun notifText(): String {
        if (!Bike.connected) return L.t(if (Bike.running) "Not connected - retrying" else "Stopped")
        val t = Bike.telemetry
        return L.t("Connected") + (t.f("SOC")?.let { "  ·  ${it.toInt()}%" } ?: "") +
            (Bike.rangeKm()?.let { "  ·  ${it.toInt()} km" } ?: "")
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MidService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Gesits MID")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, L.t("Disconnect"), stop).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    @Volatile private var notifShown = ""
    /** Updates the notification only when its text changes (cheap; called on state changes and every few seconds). */
    private fun refreshNotif() {
        if (!Bike.running) return
        val text = notifText()
        if (text == notifShown) return
        notifShown = text
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text)) } catch (_: Exception) {}
    }

    private fun openLink(s: Settings): MidLink? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { setStatus("Bluetooth is off"); return null }
        val addr = s.address ?: run { setStatus("Choose the bike first"); return null }
        val device = adapter.getRemoteDevice(addr)
        val onData: (ByteArray) -> Unit = { bytes ->
            Bike.lastRxAt = System.currentTimeMillis()
            synchronized(parser) {
                parser.feed(bytes, raw = { line ->
                    // Show everything except the routine poll replies in the log/console.
                    val key = line.removePrefix("AT+").substringBefore('=').trim().uppercase()
                    if (key !in Mid.POLL_KEYS) Bike.log("<- $line")
                    // The MID answers writes with the command's name (as GRID Mobile relies on): note the confirmation.
                    val up = line.uppercase()
                    Bike.ACK_KEYS.firstOrNull { up.contains(it) }?.let { Bike.ackAt[it] = System.currentTimeMillis() }
                }) { k, v -> Bike.telemetry.put(k, v) }
            }
        }
        val onClosed: () -> Unit = { Bike.connected = false; setStatus("Connection lost - retrying") }
        val l: MidLink = if (s.linkType == "ble") BleLink(this, device, onData, onClosed) else ClassicLink(device, onData, onClosed)
        setStatus("Connecting (${l.name}) to ${s.deviceName ?: addr}...")
        return try {
            l.open(); Bike.connected = true; setStatus("Connected (${l.name}) to ${s.deviceName ?: addr}"); l
        } catch (e: Exception) {
            setStatus("Connect failed: ${e.message}"); null
        }
    }

    /** All writes go through here: one at a time, >= 30 ms apart (as GESITS Dashboard does). */
    private fun send(cmd: String) {
        val l = link ?: return
        val wait = lastSend + 30 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        l.write(cmd.toByteArray(Charsets.US_ASCII))
        lastSend = System.currentTimeMillis()
        if (cmd.startsWith("AT+")) {
            val k = cmd.substring(3).substringBefore('=').substringBefore('?').trim()
            if (k in Bike.ACK_KEYS && cmd.contains('=')) Bike.sentAt[k] = lastSend
        }
        // Range: whatever we send may make the MID redraw with its own figure, so ours follows right after
        // (at most every 120 ms; pictures get their own follow-up once the MID has drawn them).
        if (cmd.startsWith("AT+TRIP")) lastTripSent = lastSend
        else if (rangeOn && !cmd.startsWith("AT+MAPS") && lastSend - lastTripSent >= 120) Bike.rangeKm()?.let { send(Mid.trip(it)) }
    }

    /**
     * The picture to send, or null when nothing visible changed (the MID keeps showing the last one).
     * Unchanged pictures are still re-sent once a minute in case the MID was restarted.
     */
    private fun picturePayload(s: Settings, now: Long): String? {
        val key = frameKey(s)
        if (key == lastFrameKey && now - lastFrameSent < 60_000) return null
        val b64 = renderPayload(s) ?: run {
            if (s.screenMode == "dashboard") Bike.lastFrameInfo = L.t("Dashboard picture too large")
            return null
        }
        lastFrameKey = key
        return b64
    }

    /** Everything that is drawn, rounded the way it is shown. Same key = same picture. */
    private fun frameKey(s: Settings): String {
        if (s.screenMode != "dashboard") {
            val pic = if (s.screenMode == "picture") java.io.File(filesDir, "picture.b64").lastModified() else 0L
            return s.screenMode + pic + s.margins
        }
        val t = Bike.telemetry
        val p = t.powerW?.let { Math.round(it / 100f) }   // 0.1 kW
        val trip = synchronized(Bike.trip) { "${Math.round(Bike.trip.km * 10)}/${Math.round(Bike.trip.energyWh)}" }
        val nav = if (s.navOn && Bike.navFresh()) Bike.nav else null
        return listOf(pageFor(s, frameNo + 1), nav, SimpleDateFormat("HH:mm", Locale.US).format(Date()), t.raw["SOC"], t.mode, t.gear,
            p, t.raw["TBAT"], t.raw["TMOT"], t.raw["TINV"], t.errorMask, Bike.rangeKm()?.let { Math.round(it) }, trip, s.margins).joinToString("|")
    }

    private fun pageFor(s: Settings, n: Int): ScreenRenderer.Page {
        val moving = (Bike.telemetry.f("SPD") ?: 0f) > 3f
        if (s.navOn && Bike.navFresh()) return ScreenRenderer.Page.NAV   // directions win while navigating
        return when (s.dashStyle) {
            "main" -> ScreenRenderer.Page.MAIN
            "trip" -> ScreenRenderer.Page.TRIP
            "big" -> ScreenRenderer.Page.BIG
            "rotate" -> ScreenRenderer.Page.values()[n % 3]
            else -> if (moving) ScreenRenderer.Page.BIG
                    else if ((n / 2) % 2 == 0) ScreenRenderer.Page.MAIN else ScreenRenderer.Page.TRIP
        }
    }

    private fun renderPayload(s: Settings): String? = when (s.screenMode) {
        "dashboard" -> {
            frameNo++
            val page = pageFor(s, frameNo)
            val bmp = synchronized(Bike.trip) { ScreenRenderer.render(Bike.telemetry, Bike.rangeKm(), s.margins, page, Bike.trip) }
            Bike.lastFrame = bmp
            ScreenRenderer.toMapsBase64(bmp)
        }
        "grid" -> ScreenRenderer.calibrationGrid().let { Bike.lastFrame = it; ScreenRenderer.toMapsBase64(it) }
        "gridLarge" -> ScreenRenderer.largeGrid().let { Bike.lastFrame = it; ScreenRenderer.toMapsBase64(it) }
        "picture" -> try { openFileInput("picture.b64").bufferedReader().readText() } catch (_: Exception) { null }
        else -> null
    }

    /**
     * Poll periods (ms) per key. Power/speed often, slow-changing values rarely. Parked: x3.
     * While the Speedometer or Diagnosis screen is open ([Bike.uiFast]) the live values go faster.
     */
    private fun pollPeriod(key: String, parked: Boolean, fast: Boolean): Long {
        val base = when (key) {
            "SPD" -> if (fast) 300L else 1000L
            "IBAT" -> if (fast) 500L else 1000L
            "VBAT" -> if (fast) 1000L else 2000L
            "ODO", "PARK" -> 3000L
            "SOC", "MODE" -> 5000L
            "ERR" -> 10_000L
            "TBAT", "TMOT", "TINV" -> 15_000L
            "SOH" -> 60_000L
            "TRT" -> if (fast) 500L else 30_000L
            else -> 10_000L
        }
        return if (parked && !fast) base * 3 else base
    }

    private val lastPolled = HashMap<String, Long>()
    private var lastFrameKey = ""
    private var lastFrameSent = 0L

    private fun saveState(s: Settings) {
        synchronized(Bike.range) { s.rangeState = Bike.range.save(); s.energyState = Bike.energy.save() }
        s.tripState = synchronized(Bike.trip) { Bike.trip.save() }
        s.batteryState = synchronized(Bike.battery) { Bike.battery.save() }
        BikeWidget.update(this)
    }

    private fun runLoop() {
        val s = Settings(this)
        var navIdx = 0
        var lastFrame = 0L; var lastNav = 0L; var lastTime = 0L
        var lastMinute = -1; var lastLearn = 0L; var lastSave = 0L
        var backoff = 2000L; var offSince = 0L
        Bike.loadRange(s)
        Bike.loadTrip(s)
        Bike.loadBattery(s)
        L.init(s.language)
        // Battery size from rides recorded earlier, if the energy model has nothing yet.
        RideStore.all(this).filter { it.energyWh > 0f && it.socStart - it.socEnd >= 2f }.let { r ->
            if (r.isNotEmpty()) synchronized(Bike.range) {
                Bike.energy.seedCapacity(r.sumOf { it.energyWh.toDouble() }.toFloat(), r.sumOf { (it.socStart - it.socEnd).toDouble() }.toFloat())
            }
        }
        var tripAfterFrame = 0L; var testSent = false
        var lastWidget = 0L; var lastNotif = 0L; var lastMapsCheck = 0L; var lastSpeedMsg = 0L; var navPart = 0
        while (Bike.running) {
            try {
                if (link?.isOpen != true) {
                    val t = System.currentTimeMillis()
                    if (Bike.connected || lastSave != 0L) { saveState(s); lastSave = 0L }
                    holdWakeLock(false)                      // bike off: let the phone sleep between retries
                    // Not connected (e.g. ignition off): close the ride after the idle time.
                    Bike.recorder.tick(t)?.let { rideFinished(it, s) }
                    link?.close()
                    link = openLink(s)
                    if (link == null) {
                        if (offSince == 0L) offSince = t
                        if (s.autoStopMin > 0 && t - offSince > s.autoStopMin * 60_000L) {
                            setStatus("Bike off for ${s.autoStopMin} min - stopped to save battery")
                            Bike.running = false
                            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                            break
                        }
                        Thread.sleep(backoff)
                        backoff = minOf(backoff * 2, 60_000L)   // 2, 4, 8 ... 60 s
                        continue
                    }
                    backoff = 2000L; offSince = 0L
                    holdWakeLock(true)
                    lastFrame = System.currentTimeMillis() - s.frameSeconds * 1000L + 1500   // first frame after some data
                    lastTime = 0L; lastMinute = -1   // set the MID clock right after connecting
                    lastFrameKey = ""; lastPolled.clear(); lastSave = System.currentTimeMillis()
                    Bike.telemetry.raw.clear()         // never pair old values with new ones (range learning)
                    Bike.lastRxAt = 0L; connectedAt = System.currentTimeMillis()
                    send(Mid.VERSION_QUERY)            // MID firmware version (shown in the app)
                    send(Mid.ARROW_ON)                 // as GRID Mobile does on connect
                    Mid.CYCLE_KEYS.forEach { send(Mid.query(it)) }   // does this MID report a cycle count?
                    BikeWidget.update(this)
                }
                val now = System.currentTimeMillis()
                // Watchdog: we ask for values every 1-3 s, so a link that stays silent is dead even if
                // Android still calls it open (e.g. the phone suspended Bluetooth in the background).
                val testAge = Bike.rangeTestElapsed(now)
                val testing = testAge != null
                rangeOn = !testing && s.sendRange
                val quiet = testAge != null && testAge < Bike.RANGE_TEST_QUIET_MS
                if (testing && !testSent) { send(Mid.trip(Bike.RANGE_TEST_VALUE)); testSent = true; Bike.log("Range test: sent 123 km") }
                if (!testing) testSent = false
                val heard = Bike.lastRxAt
                if (!testing && now - connectedAt > 20_000 && (if (heard > 0L) now - heard > 15_000 else now - connectedAt > 60_000)) {
                    setStatus(if (heard > 0L) "No answer from the MID for 15 s - reconnecting" else "MID never answered - reconnecting")
                    Bike.connected = false
                    link?.close(); link = null
                    continue
                }
                val tm = Bike.telemetry
                val parked = tm.gear == "P" && (tm.f("SPD") ?: 0f) < 1f
                val fast = Bike.uiFast

                while (true) {
                    val cmd = Bike.outbox.poll() ?: break
                    send(cmd)
                    if (!cmd.startsWith("AT+MAPS")) Bike.log("-> " + cmd.trim())
                }

                // Poll the single most overdue key (one command in flight at a time).
                var nextPoll = Long.MAX_VALUE
                var due: String? = null; var dueScore = 1.0
                for (k in Mid.POLL_KEYS) {
                    val period = pollPeriod(k, parked, fast)
                    val last = lastPolled[k] ?: 0L
                    val score = (now - last).toDouble() / period
                    if (score >= dueScore) { due = k; dueScore = score }
                    nextPoll = minOf(nextPoll, last + period)
                }
                if (due != null && !quiet) { send(Mid.query(due)); lastPolled[due] = now; nextPoll = now + 200 }

                // Clock: on connect, whenever the phone's minute changes, and every 30 s as a safety net.
                if (s.syncTime && !testing) {
                    val c = Calendar.getInstance()
                    val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                    if (minute != lastMinute || now - lastTime >= 30_000) {
                        send(Mid.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
                        lastMinute = minute; lastTime = now
                    }
                }
                // Once per second: charts, ride recording, parking, alarms, trip and range learning.
                if (now - lastLearn >= 1000) {
                    Bike.addSample(Sample(now, tm.f("SPD"), tm.powerW, tm.f("TRT"), tm.f("TBAT"), tm.f("TMOT"), tm.f("TINV")))
                    Bike.recorder.sample(now, tm.f("SPD"), tm.f("ODO"), tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"))
                        ?.let { rideFinished(it, s) }
                    val gearNow = tm.gear
                    if (gearNow == "P" && lastGear == "D") Parking.capture(this, s)    // stand down / parked
                    if (gearNow != null) lastGear = gearNow
                    val evs = synchronized(Bike.battery) {
                        Bike.battery.update(tm.f("SOC"), tm.f("IBAT"), tm.f("SPD"), now, s.chargeTarget.toFloat())
                    }
                    evs.forEach { e ->
                        when (e) {
                            is BatteryTracker.Event.ChargeStarted -> Bike.log(L.t("Charging started"))
                            is BatteryTracker.Event.TargetReached -> Alerts.notify(this, L.t("Battery reached") + " ${e.soc.toInt()}%")
                            is BatteryTracker.Event.ChargeEnded -> Bike.log(L.t("Charging stopped"))
                        }
                    }
                    tm.f("SOH")?.let { soh ->                                   // battery health: one entry per day
                        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        if (lastSohDay != day) {
                            val log = s.sohLog.split(";").filter { it.isNotBlank() && !it.startsWith(day) } + "$day:${soh.toInt()}"
                            s.sohLog = log.takeLast(120).joinToString(";"); lastSohDay = day
                        }
                    }
                    Bike.speedWarn.limit = s.speedLimit
                    Bike.speedWarn.update(tm.f("SPD"), now)
                    if (now - lastWidget >= 60_000) { BikeWidget.update(this); lastWidget = now }
                    if (now - lastNotif >= 10_000) { refreshNotif(); lastNotif = now }
                    if (s.alarmsOn) {
                        Bike.alarms.socLow = s.socLow
                        Bike.alarms.evaluate(tm.f("SOC"), tm.f("TBAT"), tm.f("TMOT"), tm.f("SOH"), tm.errors)
                            .forEach { Alerts.notify(this, it) }
                    }
                    synchronized(Bike.trip) { Bike.trip.update(tm.f("ODO"), tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), now) }
                    val chargingNow = synchronized(Bike.battery) { Bike.battery.charging }
                    synchronized(Bike.range) { Bike.energy.update(tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), tm.f("ODO"), chargingNow, now) }
                    val odo = tm.f("ODO"); val soc = tm.f("SOC")
                    if (odo != null && soc != null) {
                        val learned = synchronized(Bike.range) {
                            Bike.range.priorKmPerPercent = s.kmPerPercent
                            Bike.range.update(odo, soc, now)
                        }
                        if (learned) Bike.log("Range learned: " + synchronized(Bike.range) { Bike.range.describe() })
                        if (learned || now - lastSave >= 60_000) { saveState(s); lastSave = now }
                    }
                    lastLearn = now
                }
                // Range: the MID keeps putting its own figure back (on its own timer and whenever it redraws
                // after a picture), so ours is re-sent every 0.25 s and again once the picture has been drawn.
                val rangeReady = rangeOn
                if (rangeReady && (now - lastTripSent >= TRIP_EVERY_MS || (tripAfterFrame > 0L && now >= tripAfterFrame))) {
                    Bike.rangeKm()?.let { send(Mid.trip(it)) }
                    tripAfterFrame = 0L
                }
                val nav = if (s.navOn && Bike.navFresh(now)) Bike.nav else null
                if (testing) {
                    // range test: nothing else is written to the MID
                } else if (Bike.speedWarn.active) {
                    if (now - lastSpeedMsg >= 2000) {                       // speed warning first
                        send(Mid.nav("! ${tm.f("SPD")?.toInt() ?: 0} > ${s.speedLimit} km/h"))
                        lastSpeedMsg = now; lastNav = 0L
                    }
                } else if (nav != null) {
                    if (now - lastNav >= 3000) {                            // directions: turn+distance, then street
                        val first = (navTurnWord(nav.turn) + " " + (nav.distance ?: "")).trim()
                        val parts = listOf(first) + Mid.splitNav(nav.street).take(1)
                        navPart %= parts.size
                        send(Mid.nav(parts[navPart++])); lastNav = now
                    }
                } else {
                    val parts = Mid.splitNav(s.navText).ifEmpty { if (s.navDate) listOf(Mid.dateText()) else emptyList() }
                    if (parts.isNotEmpty() && now - lastNav >= 5000 && (parts.size > 1 || now - lastNav >= 60_000)) {
                        navIdx %= parts.size
                        send(Mid.nav(parts[navIdx++]))     // one fixed line: refreshed once a minute only
                        lastNav = now
                    }
                }
                val mapsSent = Bike.sentAt["MAPS"] ?: 0L
                val mapsAck = Bike.ackAt["MAPS"] ?: 0L
                if (mapsAck > 0L && mapsSent > mapsAck && now - mapsSent > 6000 && mapsSent != lastMapsCheck) {
                    lastMapsCheck = mapsSent
                    lastFrameKey = ""                      // MID did not confirm the last picture: send it again
                    Bike.log("MID did not confirm the picture - resending")
                }
                if (!testing && s.screenMode != "off" && now - lastFrame >= s.frameSeconds * 1000L) {
                    val t0 = System.currentTimeMillis()
                    val b64 = picturePayload(s, now)
                    if (b64 != null) {
                        send(Mid.maps(b64))
                        if (rangeReady) { Bike.rangeKm()?.let { send(Mid.trip(it)) }; tripAfterFrame = System.currentTimeMillis() + 400 }
                        Bike.lastFrameInfo = (if (L.indo) "Gambar terkirim: " else "Picture sent: ") + "${b64.length} " + (if (L.indo) "karakter dalam " else "chars in ") + "${System.currentTimeMillis() - t0} ms"
                        lastFrameSent = now
                    }
                    lastFrame = System.currentTimeMillis()
                }

                // Sleep until the next thing is due; a command from the UI wakes us early.
                val nextDue = minOf(nextPoll, lastLearn + 1000, if (rangeReady) minOf(lastTripSent + TRIP_EVERY_MS, if (tripAfterFrame > 0L) tripAfterFrame else Long.MAX_VALUE) else Long.MAX_VALUE,
                    lastFrame + s.frameSeconds * 1000L)
                val wait = (nextDue - System.currentTimeMillis()).coerceIn(30L, 1000L)
                Bike.outbox.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { cmd ->
                    send(cmd)
                    if (!cmd.startsWith("AT+MAPS")) Bike.log("-> " + cmd.trim())
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                setStatus("Error: ${e.message} - reconnecting")
                Bike.connected = false
                link?.close(); link = null
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
        Bike.recorder.finish(System.currentTimeMillis())?.let { rideFinished(it, s) }
        saveState(s)
        holdWakeLock(false)
        link?.close(); link = null
        Bike.connected = false
        if (!Bike.status.startsWith("Bike off")) setStatus("Stopped")
    }

    private var lastGear: String? = null
    @Volatile private var connectedAt = 0L
    @Volatile private var rangeOn = false       // send our range to the MID (setting on, no range test running)
    private var lastTripSent = 0L
    private var lastSohDay: String? = null

    private fun navTurnWord(t: NavParser.Turn) = L.t(when (t) {
        NavParser.Turn.LEFT -> "LEFT"; NavParser.Turn.RIGHT -> "RIGHT"
        NavParser.Turn.SLIGHT_LEFT -> "KEEP LEFT"; NavParser.Turn.SLIGHT_RIGHT -> "KEEP RIGHT"
        NavParser.Turn.UTURN -> "U-TURN"; NavParser.Turn.ROUNDABOUT -> "ROUNDABOUT"
        NavParser.Turn.STRAIGHT -> "STRAIGHT"; NavParser.Turn.ARRIVE -> "ARRIVED"; NavParser.Turn.NONE -> ""
    })

    private fun rideFinished(r: Ride, s: Settings) {
        RideStore.add(this, r)
        Bike.log(String.format(java.util.Locale.US, "Ride saved: %.1f km, %.0f Wh", r.km, r.energyWh))
        Parking.capture(this, s)
    }

    override fun onDestroy() {
        holdWakeLock(false)
        Bike.running = false
        worker?.interrupt()
        link?.close()
        super.onDestroy()
    }
}
