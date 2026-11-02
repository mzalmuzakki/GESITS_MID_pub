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
    /** Sign of the battery current on this bike (learned while riding). */
    val currentSign = CurrentSign()

    /** When the MID last sent a valid answer (see Liveness). */
    @Volatile var lastRxAt = 0L
    /** The current worker's number: an older worker still stuck in a Bluetooth call sees it changed and quits. */
    @Volatile var workerGen = 0
    @Volatile var workerAlive = false
    /** One-off commands from the UI (text, picture...), sent by the service thread. */
    val outbox = LinkedBlockingQueue<String>()
    val log = ArrayDeque<String>()
    /** Adaptive range learner; guard access with synchronized(range). */
    val range = RangeEstimator(windowPercent = 40f, priorWeightPercent = 5f, fadePercent = 10f)
    @Volatile var rangeLoaded = false

    /** Energy-based adaptive range (battery capacity x your consumption); guarded by synchronized(range) too. */
    val energy = EnergyRange()

    fun loadRange(s: Settings) = synchronized(range) {
        if (!rangeLoaded) { range.load(s.rangeState); energy.load(s.energyState); rangeLoaded = true }
        range.priorKmPerPercent = priorKmPerPercent(s)
    }
    /**
     * Starting value for km per 1 % until enough is ridden: what the energy model has already learned about
     * this bike (battery size / consumption) if it has, else the original G1 battery's 0.5. Keeps the range
     * steady when switching to the km-per-% method instead of dropping back to ~50 km.
     */
    fun priorKmPerPercent(s: Settings): Float =
        if (energy.capacityLearned && energy.consumptionLearned) energy.rangeKm(1f).coerceIn(0.1f, 5f) else s.kmPerPercent

    /**
     * Range now = battery % x km per 1 % measured from your own recent riding (odometer and battery % only,
     * so it doesn't depend on the battery's current reading). The energy model is only used while nothing
     * has been ridden yet but it has already learned battery size and consumption (e.g. from charging).
     */
    fun rangeKm(): Float? = Bike.telemetry.f("SOC")?.let { soc ->
        synchronized(range) {
            if (range.learnedPercent < 1f && energy.capacityLearned && energy.consumptionLearned) energy.rangeKm(soc) else range.rangeKm(soc)
        }
    }

    /** Last ~10 minutes of per-second samples for the Diagnosis charts; guard with synchronized(samples). */
    val samples = ArrayDeque<Sample>()
    fun addSample(x: Sample) = synchronized(samples) { samples.addLast(x); while (samples.size > 600) samples.removeFirst() }

    /** True while the Speedometer or Diagnosis screen is visible. No longer changes how often the MID is asked. */
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
    val ACK_KEYS = listOf("MAPS", "TIME", "NAV")

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
    var screenMode: String get() = p.getString("screen", "off")!!; set(v) = p.edit().putString("screen", v).apply()

    /**
     * v0.16: on a real G1 the MID froze (speed, turn signals) after ~5 min of pictures + text + polling.
     * Pictures (7000 chars each) are by far the heaviest load, so they are switched off once for everyone;
     * you can switch them on again in the MID tab.
     */
    fun migrateSafeDefaults() {
        if (!p.getBoolean("safe016", false))
            p.edit().putString("screen", "off").putInt("frameSec", maxOf(p.getInt("frameSec", 10), 10)).putBoolean("safe016", true).apply()
        // v0.17: the AT+TRIP range override is gone for good.
        if (p.contains("rangeOverride")) p.edit().remove("rangeOverride").apply()
    }
    /** Show the app's adaptive range in the MID text line (when you have no text of your own). */
    /** How the Classic link last connected (insecure / secure / ch1): tried first next time. */
    var classicMethod: String? get() = p.getString("classicMethod", null); set(v) = p.edit().putString("classicMethod", v).apply()
    var currentSign: String? get() = p.getString("ibatSign", null); set(v) = p.edit().putString("ibatSign", v).apply()
    var rangeLine: Boolean get() = p.getBoolean("rangeLine", true); set(v) = p.edit().putBoolean("rangeLine", v).apply()
    /** A calibration grid is shown until this time, then pictures switch off again. */
    var gridUntil: Long get() = p.getLong("gridUntil", 0L); set(v) = p.edit().putLong("gridUntil", v).apply()
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
    var frameSeconds: Int get() = maxOf(p.getInt("frameSec", 10), 10); set(v) = p.edit().putInt("frameSec", v).apply()
}

@SuppressLint("MissingPermission")
class MidService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "mid"
        private const val NOTIF_ID = 1
        const val EXTRA_TAP = "tap"

        /** [tap]: you tapped Connect (Android may then show one pairing request); false for auto-connect. */
        fun start(ctx: Context, tap: Boolean = false) {
            val i = Intent(ctx, MidService::class.java).setAction(ACTION_START).putExtra(EXTRA_TAP, tap)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, MidService::class.java).setAction(ACTION_STOP)) }
    }

    @Volatile private var worker: Thread? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Keeps the CPU awake while connected, so the link survives screen-off and app switching. */
    @Synchronized private fun holdWakeLock(on: Boolean) {
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
    @Volatile private var awaitKey: String? = null     // question sent, answer not yet received
    @Volatile private var awaitSince = 0L
    @Volatile private var quietUntil = 0L               // no commands before this (MID drawing a picture)

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
        if (intent?.getBooleanExtra(EXTRA_TAP, false) == true) { pairingAllowed = true; resumeRequested = true }
        if (!goForeground(fromApp = intent != null)) { stopSelf(); return START_NOT_STICKY }
        Demo.clear()
        if (worker?.isAlive != true || !Bike.running) {
            // A worker that is still stuck in a Bluetooth call after Disconnect sees the new number and quits.
            worker?.interrupt()
            val gen = ++Bike.workerGen
            Bike.running = true
            worker = Thread({ runLoop(gen) }, "mid-worker").also { it.start() }
        }
        return START_STICKY
    }

    /** False if Android doesn't allow the foreground service right now (e.g. restarted from the background). */
    private fun goForeground(fromApp: Boolean): Boolean = try { goForegroundOrThrow(fromApp); true } catch (e: Exception) {
        Bike.log("Could not start in the foreground: ${e.message}"); false
    }

    private fun goForegroundOrThrow(fromApp: Boolean) {
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
        if (midHung) return L.t("MID not answering - stop safely, switch the bike off and on")
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
            synchronized(parser) {
                parser.feed(bytes, raw = { line ->
                    // Show everything except the routine poll replies in the log/console.
                    val key = line.removePrefix("AT+").substringBefore('=').trim().uppercase()
                    if (key !in Mid.POLL_KEYS) Bike.log("<- $line")
                    // The MID answers writes with the command's name (as GRID Mobile relies on): note the confirmation.
                    val up = line.uppercase()
                    Bike.ACK_KEYS.firstOrNull { up.contains(it) }?.let { Bike.ackAt[it] = System.currentTimeMillis() }
                }) { k, v ->
                    if (k == awaitKey) awaitKey = null                       // the answer we were waiting for
                    if (Replies.clean(k, v) != null) Bike.lastRxAt = System.currentTimeMillis()   // a real answer: the MID is alive
                    if (!Bike.telemetry.offer(k, v) && Replies.clean(k, v) == null) Bike.log("ignored garbled reply: $k=$v")
                }
            }
        }
        val onClosed: () -> Unit = { Bike.connected = false; Bike.telemetry.clearLive(); setStatus("Connection lost - retrying") }
        val mayPair = pairingAllowed; pairingAllowed = false          // one pairing request per Connect, at most
        val l: MidLink = if (s.linkType == "ble") BleLink(this, device, onData, onClosed)
            else ClassicLink(device, onData, onClosed, mayPair, s.classicMethod)
        setStatus("Connecting (${l.name}) to ${s.deviceName ?: addr}...")
        return try {
            l.open()
            (l as? ClassicLink)?.usedMethod?.let { s.classicMethod = it }   // try the same way first next time
            setStatus("Link open (${l.name}) - waiting for the MID to answer"); l
        } catch (e: InterruptedException) {
            l.close(); throw e
        } catch (e: Exception) {
            if (e.message == ClassicLink.NOT_PAIRED) {
                if (!Bike.status.startsWith("Bike not paired")) Alerts.notify(this, L.t("Pair the bike again: phone Bluetooth settings → Gesits."))
                setStatus("Bike not paired with this phone - pair it again in Bluetooth settings, then tap Connect")
            } else setStatus("Connect failed: ${e.message}")
            null
        }
    }

    /**
     * Everything sent to the MID goes through here (see Traffic in Safety.kt): only allowed commands, one at a
     * time, at least 0.5 s apart (at most 2 a second, questions and writes together), after the answer to the
     * previous question (max 0.3 s), and never while the MID is still taking in and drawing a picture.
     */
    private fun send(cmd: String) {
        val l = link ?: return
        if (!Traffic.allowed(cmd)) { Bike.log("blocked command: " + cmd.take(24).trim()); return }
        val asked = awaitSince
        while (awaitKey != null && System.currentTimeMillis() - asked < Traffic.ANSWER_WAIT_MS) Thread.sleep(10)
        awaitKey = null
        val wait = Traffic.nextAllowedAt(lastSend, quietUntil) - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        l.write(cmd.toByteArray(Charsets.US_ASCII))
        lastSend = System.currentTimeMillis()
        if (cmd.endsWith("?\r\n")) { awaitKey = cmd.substring(3, cmd.length - 3); awaitSince = lastSend }
        quietUntil = maxOf(quietUntil, lastSend + Traffic.quietAfterMs(cmd))
        val k = cmd.substring(3).substringBefore('=').substringBefore('?').trim()
        if (k in Bike.ACK_KEYS && cmd.contains('=')) Bike.sentAt[k] = lastSend
    }

    /**
     * The picture to send, or null when nothing visible changed (the MID keeps showing the last one).
     * Unchanged pictures are still re-sent once a minute in case the MID was restarted.
     */
    private fun picturePayload(s: Settings, now: Long, force: Boolean = false): String? {
        val key = frameKey(s)
        if (!force && key == lastFrameKey && now - lastFrameSent < 60_000) return null
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
     * How often each value is asked (ms). Speed often, slow-changing values rarely; parked: x3. The total is
     * also capped by send() at 2 commands a second, whatever screen is open.
     */
    private fun pollPeriod(key: String, parked: Boolean): Long {
        val base = when (key) {
            "SPD" -> 1000L
            "IBAT" -> 2000L
            "VBAT" -> 5000L
            "ODO", "PARK" -> 5000L
            "SOC", "MODE" -> 5000L
            "ERR" -> 10_000L
            "TBAT", "TMOT", "TINV" -> 15_000L
            "SOH", "RANGE" -> 60_000L
            else -> 10_000L
        }
        return if (parked) base * 3 else base
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

    private fun runLoop(gen: Int) {
        val mine = { Bike.running && gen == Bike.workerGen }
        Bike.workerAlive = true
        val s = Settings(this)
        var navIdx = 0
        var lastFrame = 0L; var lastNav = 0L; var lastTime = 0L
        var lastMinute = -1; var lastLearn = 0L; var lastSave = 0L
        var backoff = 2000L
        var noAnswerSince = System.currentTimeMillis()          // for auto-stop: time without any answer from the MID
        Bike.loadRange(s)
        Bike.loadTrip(s)
        Bike.loadBattery(s)
        L.init(s.language)
        s.migrateSafeDefaults()
        Bike.currentSign.load(s.currentSign); Bike.telemetry.ibatSign = Bike.currentSign.sign
        // Battery size from rides recorded earlier, if the energy model has nothing yet.
        RideStore.all(this).filter { it.energyWh > 0f && it.socStart - it.socEnd >= 2f }.let { r ->
            if (r.isNotEmpty()) synchronized(Bike.range) {
                Bike.energy.seedCapacity(r.sumOf { it.energyWh.toDouble() }.toFloat(), r.sumOf { (it.socStart - it.socEnd).toDouble() }.toFloat())
            }
        }
        val live = Liveness()
        var hungSince = 0L; var lastProbe = 0L
        var lastNavText = ""; var pendingNav: String? = null
        var lastWidget = 0L; var lastNotif = 0L; var lastErrorLog = 0L
        var lastSpeedSeen: Float? = null; var lastGearSeen: String? = null
        var answeredBefore = false
        try {
            while (mine()) {
                try {
                    val tNow = System.currentTimeMillis()
                    if (s.autoStopMin > 0 && tNow - noAnswerSince > s.autoStopMin * 60_000L) {
                        setStatus("Bike off for ${s.autoStopMin} min - stopped to save battery")
                        Bike.running = false
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                        break
                    }
                    if (link?.isOpen != true) {
                        // No link (start, bike switched off, link dropped): start fresh.
                        if (midHung) { midHung = false; Bike.log("Link to the MID closed (bike switched off and on?) - starting fresh") }
                        if (Bike.connected || lastSave != 0L) { saveState(s); lastSave = 0L }
                        Bike.connected = false
                        Bike.telemetry.clearLive()
                        holdWakeLock(false)                      // let the phone sleep between retries
                        Bike.recorder.tick(tNow)?.let { rideFinished(it, s) }   // close the ride after the idle time
                        link?.close(); link = null
                        val l = openLink(s)
                        if (!mine()) { l?.close(); break }
                        link = l
                        if (l == null) {
                            Thread.sleep(backoff)
                            backoff = minOf(backoff * 2, 60_000L)   // 2, 4, 8 ... 60 s
                            continue
                        }
                        live.connected(System.currentTimeMillis()); Bike.lastRxAt = 0L; answeredBefore = false
                        lastTime = 0L; lastMinute = -1
                        lastFrameKey = ""; lastPolled.clear(); lastSave = System.currentTimeMillis()
                        lastNavText = ""; pendingNav = null; lastFrame = 0L
                        synchronized(parser) { parser.reset() }
                        send(Mid.VERSION_QUERY)            // the only question asked on connect
                        BikeWidget.update(this)
                    }
                    val now = System.currentTimeMillis()
                    if (Bike.lastRxAt > live.lastAnswerAt) live.answered(Bike.lastRxAt)
                    if (live.lastAnswerAt > 0L && !midHung) {
                        noAnswerSince = now
                        if (!answeredBefore) {
                            answeredBefore = true
                            backoff = 2000L                      // only a MID that answers resets the back-off
                            Bike.connected = true
                            holdWakeLock(true)
                            setStatus("Connected to ${s.deviceName ?: "the bike"}")
                        }
                    }
                    if (resumeRequested) {
                        resumeRequested = false
                        if (midHung) { midHung = false; hungSince = 0L; live.connected(now); Bike.lastRxAt = 0L; answeredBefore = false; Bike.log("Resumed by Connect") }
                    }

                    if (midHung) {
                        // The MID stopped answering while riding: send nothing. One question a minute shows whether it is
                        // back; switching the bike off and on drops the link and starts fresh; so does tapping Connect.
                        if (live.lastAnswerAt > hungSince) {
                            midHung = false; midHungWarned = false; Bike.connected = true
                            setStatus("The MID answers again - connected")
                        } else {
                            if (now - lastProbe >= 60_000) { lastProbe = now; send(Mid.VERSION_QUERY) }
                            Thread.sleep(1000)
                            continue
                        }
                    }
                    if (live.silent(now)) {
                        Bike.connected = false
                        Bike.telemetry.clearLive()                // no screen keeps showing old values as live
                        if (answeredBefore && live.verdict(lastSpeedSeen, lastGearSeen) == Liveness.Verdict.HUNG) {
                            midHung = true; hungSince = now; lastProbe = now
                            holdWakeLock(false)
                            if (!midHungWarned) {
                                Alerts.notify(this, L.t("The MID stopped answering. When it is safe, stop and switch the bike off and on. The app sends nothing until then."))
                                midHungWarned = true
                            }
                            setStatus("The MID stopped answering - app silent (stop safely, switch the bike off and on)")
                            refreshNotif()
                        } else {
                            setStatus(if (answeredBefore) "The bike stopped answering (switched off?) - retrying slowly" else "The MID doesn't answer - retrying slowly")
                            link?.close(); link = null
                            Thread.sleep(backoff); backoff = minOf(backoff * 2, 60_000L)
                        }
                        continue
                    }
                    val talk = live.canWrite(now)              // writes only while the MID answers
                    val tm = Bike.telemetry
                    val parked = tm.gear == "P" && (tm.f("SPD") ?: 0f) < 1f
                    if (s.screenMode.startsWith("grid") && now > s.gridUntil) { s.screenMode = "off"; Bike.log("Calibration grid finished - pictures off") }
                    val picturesOn = s.screenMode != "off"

                    while (true) {                             // console questions (anything else is blocked in send)
                        val cmd = Bike.outbox.poll() ?: break
                        send(cmd); Bike.log("-> " + cmd.trim())
                    }

                    // Ask the single most overdue value.
                    var nextPoll = Long.MAX_VALUE
                    var due: String? = null; var dueScore = 1.0
                    for (k in Mid.POLL_KEYS) {
                        val period = pollPeriod(k, parked)
                        val last = lastPolled[k] ?: 0L
                        val score = (now - last).toDouble() / period
                        if (score >= dueScore) { due = k; dueScore = score }
                        nextPoll = minOf(nextPoll, last + period)
                    }
                    if (due != null) { send(Mid.query(due)); lastPolled[due] = now; nextPoll = now + Traffic.MIN_GAP_MS }

                    // Clock: on connect, whenever the phone's minute changes, and every 30 s as a safety net.
                    if (s.syncTime && talk) {
                        val c = Calendar.getInstance()
                        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                        if (minute != lastMinute || now - lastTime >= 30_000) {
                            send(Mid.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
                            lastMinute = minute; lastTime = now
                        }
                    }
                    // Once per second: charts, ride recording, parking, alarms, trip and range learning.
                    if (now - lastLearn >= 1000) {
                        tm.f("SPD")?.let { lastSpeedSeen = it }
                        tm.gear?.let { lastGearSeen = it }
                        if (Bike.currentSign.update(tm.f("SPD"), tm.ibatRaw)) {
                            tm.ibatSign = Bike.currentSign.sign
                            Bike.log(if (tm.ibatSign < 0) "This battery reports current drawn as negative - corrected" else "Battery current sign: normal")
                        }
                        if (Bike.currentSign.decided && s.currentSign != Bike.currentSign.save()) s.currentSign = Bike.currentSign.save()
                        Bike.addSample(Sample(now, tm.f("SPD"), tm.powerW, null, tm.f("TBAT"), tm.f("TMOT"), tm.f("TINV")))
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
                                Bike.range.priorKmPerPercent = Bike.priorKmPerPercent(s)
                                Bike.range.update(odo, soc, now)
                            }
                            if (learned) Bike.log("Range learned: " + synchronized(Bike.range) { Bike.range.describe() })
                            if (learned || now - lastSave >= 60_000) { saveState(s); lastSave = now }
                        }
                        lastLearn = now
                    }

                    // Text line. The MID draws it over the picture without erasing old characters, so texts change
                    // rarely, a change is sent after a blank (no pictures) or on top of a fresh picture (pictures on),
                    // and a text that ended is cleared.
                    val nav = if (s.navOn && Bike.navFresh(now)) Bike.nav else null
                    val own = Mid.splitNav(s.navText)
                    val line: String? = when {
                        Bike.speedWarn.active -> "! " + L.t("SLOW DOWN") + " ${s.speedLimit}"
                        nav != null -> (navTurnWord(nav.turn) + " " + nav.street).trim().take(Mid.NAV_MAX)   // distance: in the picture
                        own.size > 1 -> null                                                     // your own long text rotates
                        own.size == 1 -> own[0]
                        s.rangeLine -> Bike.rangeKm()?.let { (if (L.indo) "JARAK " else "RANGE ") + Math.round(it) + " km" }
                            ?: if (s.navDate) Mid.dateText() else null
                        s.navDate -> Mid.dateText()
                        else -> null
                    }
                    if (talk) {
                        if (line == null && own.size > 1) {
                            if (now - lastNav >= 5000) {
                                navIdx %= own.size
                                val part = own[navIdx++]
                                if (picturesOn) pendingNav = part
                                else { send(Mid.nav("")); send(Mid.nav(part)); lastNavText = part; lastNav = now }
                            }
                        } else if ((line ?: "") != lastNavText) {
                            when {
                                line == null -> { send(Mid.nav("")); lastNavText = ""; lastNav = now; pendingNav = null }
                                picturesOn -> pendingNav = line                                     // goes with the next picture
                                else -> { send(Mid.nav("")); send(Mid.nav(line)); lastNavText = line; lastNav = now }
                            }
                        } else if (line != null && !picturesOn && now - lastNav >= 60_000) {
                            send(Mid.nav(line)); lastNav = now                                    // same text: safe to repeat
                        }
                    }

                    // Pictures: only if switched on, at most one every 10 s, never bigger than the MID can take.
                    val frameMs = maxOf(s.frameSeconds * 1000L, Traffic.PICTURE_MIN_INTERVAL_MS)
                    if (talk && picturesOn && now - lastFrameSent >= frameMs && (pendingNav != null || now - lastFrame >= frameMs)) {
                        lastFrame = now
                        val b64 = try { picturePayload(s, now, force = pendingNav != null) } catch (e: Exception) { Bike.log("Picture failed: ${e.message}"); null }
                        if (b64 != null && b64.length > Traffic.PICTURE_MAX_CHARS) {
                            Bike.lastFrameInfo = L.t("Picture too big for the MID - not sent")
                        } else if (b64 != null) {
                            val t0 = System.currentTimeMillis()
                            send(Mid.maps(b64)); lastFrameSent = now
                            // the picture wiped the text area: draw the text again on top of it
                            (pendingNav ?: lastNavText.takeIf { it.isNotEmpty() })?.let { send(Mid.nav(it)); lastNavText = it; lastNav = now }
                            pendingNav = null
                            Bike.lastFrameInfo = (if (L.indo) "Gambar terkirim: " else "Picture sent: ") + "${b64.length} " + (if (L.indo) "karakter dalam " else "chars in ") + "${System.currentTimeMillis() - t0} ms"
                        }
                    }

                    // Sleep until the next thing is due; a console question wakes us early.
                    val nextDue = minOf(nextPoll, lastLearn + 1000,
                        if (talk && picturesOn) maxOf(lastFrame + frameMs, lastFrameSent + frameMs) else Long.MAX_VALUE)
                    val wait = (nextDue - System.currentTimeMillis()).coerceIn(100L, 1000L)
                    Bike.outbox.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { cmd ->
                        send(cmd); Bike.log("-> " + cmd.trim())
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: java.io.IOException) {
                    // The Bluetooth link failed: drop it and reconnect with a growing pause.
                    setStatus("Connection error: ${e.message} - reconnecting")
                    Bike.connected = false; Bike.telemetry.clearLive()
                    link?.close(); link = null
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = minOf(backoff * 2, 60_000L)
                } catch (e: Exception) {
                    // A bug elsewhere (drawing, files...): keep the link, note it, carry on.
                    val n = System.currentTimeMillis()
                    if (n - lastErrorLog > 60_000) { Bike.log("Error: ${e.javaClass.simpleName}: ${e.message}"); lastErrorLog = n }
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
        } finally {
            if (gen == Bike.workerGen) {
                Bike.recorder.finish(System.currentTimeMillis())?.let { rideFinished(it, s) }
                saveState(s)
                holdWakeLock(false)
                link?.close(); link = null
                Bike.connected = false
                Bike.telemetry.clearLive()
                midHung = false
                if (!Bike.status.startsWith("Bike off")) setStatus("Stopped")
                Bike.workerAlive = false
            } else {
                link?.close(); link = null                 // an old worker: leave everything to the new one
            }
        }
    }

    private var lastGear: String? = null
    @Volatile private var connectedAt = 0L
    @Volatile private var midHungWarned = false
    @Volatile private var midHung = false
    @Volatile private var resumeRequested = false
    @Volatile private var pairingAllowed = false   // see ClassicLink.allowPairing
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
