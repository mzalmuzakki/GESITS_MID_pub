package io.github.mzalmuzakki.gesitsmid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
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
    /**
     * When the MID was judged hung (0 = not). Kept across link drops, reconnects and Disconnect/Connect for
     * as long as the app runs: only a real answer from the MID clears it.
     */
    @Volatile var midHungSince = 0L
    @Volatile var hangWarned = false
    /**
     * After a hang (or frozen values) the MID only gets questions - no clock, text or pictures - until you
     * confirm on Home that its display works again. Kept on disk like the hang.
     */
    @Volatile var writesBlocked = false
    /** The hang was loaded from disk (from an earlier session), not seen just now. */
    @Volatile var hangFromEarlier = false
    /** The MID answers but its values look frozen (speed shown, odometer not moving). */
    @Volatile var dataFrozen = false
    /**
     * The odometer when the values were judged frozen. Kept across reconnects: the flag clears only when the
     * odometer has really moved past it (or you confirm on Home), never just because a new link started.
     */
    @Volatile var frozenOdo = Float.NaN
    fun freeze(odo: Float) { frozenOdo = odo; dataFrozen = true }
    fun unfreeze() { dataFrozen = false; frozenOdo = Float.NaN }
    /** Values may be shown as live: connected and not frozen. */
    fun valuesLive() = connected && !dataFrozen
    @Volatile var workersAlive = 0
    /** A different bike was chosen: the next worker starts the answer checks afresh. */
    @Volatile var pendingReset = false

    /** The hang is also kept on disk (its own file, untouched by "Delete all my data"), so an app restart can't forget it. */
    private fun safety(ctx: Context) = ctx.getSharedPreferences("safety", Context.MODE_PRIVATE)
    /** [t]: monotonic time (Mono). On disk only "hung" and "writes blocked" are kept. */
    fun markHung(ctx: Context, t: Long) {
        midHungSince = t; hangFromEarlier = false; blockWrites(ctx)
        safety(ctx).edit().putBoolean("hung", true).apply()
    }
    /** The MID answered again: no longer hung - but writes stay blocked until confirmed on Home. */
    fun clearHung(ctx: Context) {
        midHungSince = 0L; hangWarned = false; hangFromEarlier = false
        safety(ctx).edit().remove("hung").apply()
    }
    fun blockWrites(ctx: Context) { writesBlocked = true; safety(ctx).edit().putBoolean("writesBlocked", true).apply() }
    /** "The MID's display works again" (Home button): text, clock and pictures may be sent again. */
    fun allowWrites(ctx: Context) { writesBlocked = false; safety(ctx).edit().remove("writesBlocked").apply() }
    fun loadSafety(ctx: Context) {
        val p = safety(ctx)
        if (midHungSince == 0L && p.getBoolean("hung", false)) { midHungSince = Mono.now(); hangFromEarlier = true }
        if (p.getBoolean("writesBlocked", false)) writesBlocked = true
    }
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
    fun navFresh(now: Long = System.currentTimeMillis()) = nav != null && now - navAt < 90_000   // wall clock (set by the listener)

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

    /** The log is also kept in a file (app storage only, deleted by "Delete all my data"), so closing the app doesn't lose it. */
    @Volatile private var logFile: java.io.File? = null
    fun initLog(ctx: Context) {
        if (logFile != null) return
        val f = java.io.File(ctx.filesDir, LOG_FILE)
        synchronized(log) {
            if (log.isEmpty() && f.exists()) try { f.readLines().takeLast(200).forEach { log.addLast(it.substringAfter(' ')) } } catch (_: Exception) {}
            logFile = f
        }
    }
    const val LOG_FILE = "log.txt"
    const val LOG_OLD = "log.old.txt"
    fun log(msg: String) = synchronized(log) {
        val now = java.util.Date()
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(now) + " " + msg
        log.addLast(line)
        while (log.size > 200) log.removeFirst()
        logFile?.let { f ->
            try {
                if (f.length() > 300_000L) f.renameTo(java.io.File(f.parentFile, LOG_OLD))   // keeps ~2 x 300 kB at most
                f.appendText(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(now) + " " + line + "\n")
            } catch (_: Exception) {}
        }
    }
    /** The log with dates, from the file (for "Copy log"); the in-memory log if there is no file. */
    fun logText(ctx: Context, lines: Int = 600): String {
        val f = java.io.File(ctx.filesDir, LOG_FILE)
        return try { if (f.exists()) f.readLines().takeLast(lines).joinToString("\n") else null } catch (_: Exception) { null }
            ?: synchronized(log) { log.joinToString("\n") }
    }
    fun deleteLog(ctx: Context) = synchronized(log) {
        log.clear(); java.io.File(ctx.filesDir, LOG_FILE).delete(); java.io.File(ctx.filesDir, LOG_OLD).delete()
        synchronized(trace) { trace.clear() }
    }

    /**
     * The last Bluetooth traffic (questions, writes, raw answers) with millisecond times, kept in memory only and
     * written to the log when the MID goes silent, the link drops or the values freeze: to find what came before.
     */
    private val trace = ArrayDeque<String>()
    fun trace(s: String) = synchronized(trace) {
        trace.addLast(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + " " + s)
        while (trace.size > 40) trace.removeFirst()
    }
    fun dumpTrace(why: String) {
        val lines = synchronized(trace) { trace.toList().also { trace.clear() } }
        if (lines.isEmpty()) return
        log("Bluetooth traffic before \"$why\" (last ${lines.size}):")
        lines.forEach { log("   $it") }
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
        // v0.22: pictures show only the range (smallest picture, changes rarely) - once for everyone, can be changed back.
        if (!p.getBoolean("safe022", false)) p.edit().putString("dashStyle", "range").putBoolean("safe022", true).apply()
        // v0.17: the AT+TRIP range override is gone for good.
        if (p.contains("rangeOverride")) p.edit().remove("rangeOverride").apply()
    }
    /** Show the app's adaptive range in the MID text line (when you have no text of your own). */
    /** How the Classic link last connected (insecure / secure / ch1): tried first next time. */
    var classicMethod: String? get() = p.getString("classicMethod", null); set(v) = p.edit().putString("classicMethod", v).apply()
    var currentSign: String? get() = p.getString("ibatSign", null); set(v) = p.edit().putString("ibatSign", v).apply()
    var rangeLine: Boolean get() = p.getBoolean("rangeLine", true); set(v) = p.edit().putBoolean("rangeLine", v).apply()
    /** Test mode: lightest traffic (speed, odometer, battery %, errors), nothing written to the MID. */
    var gentle: Boolean get() = p.getBoolean("gentle", false); set(v) = p.edit().putBoolean("gentle", v).apply()
    /** A calibration grid is shown until this time, then pictures switch off again. */
    var gridUntil: Long get() = p.getLong("gridUntil", 0L); set(v) = p.edit().putLong("gridUntil", v).apply()
    var syncTime: Boolean get() = p.getBoolean("time", true); set(v) = p.edit().putBoolean("time", v).apply()
    /** Show today's date on the MID text line when no own text is set (like GRID Mobile). */
    var navDate: Boolean get() = p.getBoolean("navDate", true); set(v) = p.edit().putBoolean("navDate", v).apply()
    var navText: String get() = p.getString("nav", "")!!; set(v) = p.edit().putString("nav", v).apply()
    var kmPerPercent: Float get() = p.getFloat("kmpp", 0.5f); set(v) = p.edit().putFloat("kmpp", v).apply()
    var tripState: String? get() = p.getString("tripState", null); set(v) = p.edit().putString("tripState", v).apply()
    /** "auto" (BIG while riding, MAIN/TRIP when stopped), "main", "trip", "big", "rotate" */
    var dashStyle: String get() = p.getString("dashStyle", "range")!!; set(v) = p.edit().putString("dashStyle", v).apply()
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
            connectingLink?.close()               // abort a Bluetooth connect still in progress
            link?.close()
            holdWakeLock(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Bike.initLog(this)
        if (intent == null) Bike.log("Service restarted by Android (the app had been stopped in the background)")
        if (intent?.getBooleanExtra(EXTRA_TAP, false) == true) pairingAllowed = true
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
        if (Bike.midHungSince > 0L) return L.t("MID not answering - stop safely, switch the bike off and on")
        if (Bike.dataFrozen) return L.t("MID values look frozen - stop safely, switch the bike off and on")
        if (!Bike.connected) return L.t(if (Bike.running) "Not connected - retrying" else "Stopped")
        val t = Bike.telemetry
        if (Bike.writesBlocked) return L.t("Connected - questions only: open the app and confirm the MID works") +
            (t.f("SOC")?.let { "  ·  ${it.toInt()}%" } ?: "")
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
                    Bike.trace("<- $line")
                    // Show everything except the routine poll replies in the log/console.
                    val key = line.removePrefix("AT+").substringBefore('=').trim().uppercase()
                    if (key !in Mid.POLL_KEYS) Bike.log("<- $line")
                    // GRID Mobile expects the MID to answer writes with the command's name; a G1 doesn't, but note it if one does.
                    val up = line.uppercase()
                    Bike.ACK_KEYS.firstOrNull { up.contains(it) }?.let { Bike.ackAt[it] = Mono.now() }
                }) { k, v ->
                    if (k == awaitKey) awaitKey = null                       // the answer we were waiting for
                    if (Replies.clean(k, v) != null) Bike.lastRxAt = Mono.now()   // a real answer: the MID is alive
                    if (!Bike.telemetry.offer(k, v) && Replies.clean(k, v) == null) Bike.log("ignored garbled reply: $k=$v")
                }
            }
        }
        val onClosed: () -> Unit = { Bike.connected = false; Bike.telemetry.clearLive(); setStatus("Connection lost - retrying") }
        val mayPair = pairingAllowed; pairingAllowed = false          // one pairing request per Connect, at most
        val l: MidLink = if (s.linkType == "ble") BleLink(this, device, onData, onClosed)
            else {
                // The MID tends to forget the pairing when it resets itself (the "Broken pipe" drops); Android then
                // removes it too and only a new pairing gets back in. So once per drop - if the MID answered in the
                // last 30 min - the app may ask Android for the pairing itself: you only tap "Pair" (when stopped).
                val recent = lastAnsweredAt > 0L && Mono.now() - lastAnsweredAt < 30 * 60_000L
                val repair = !mayPair && recent && !repairAsked && device.bondState != BluetoothDevice.BOND_BONDED
                if (repair) {
                    repairAsked = true
                    Bike.log("Pairing lost after a drop - asking Android to pair again (tap Pair when stopped)")
                    Alerts.notify(this, L.t("The bike's Bluetooth pairing was lost. When stopped, tap \"Pair\" on the pairing request to reconnect."))
                }
                ClassicLink(device, onData, onClosed, mayPair || repair, s.classicMethod, pairedRecently = recent)
            }
        connectingLink = l
        setStatus("Connecting (${l.name}) to ${s.deviceName ?: addr}...")
        return try {
            l.open()
            (l as? ClassicLink)?.usedMethod?.let { s.classicMethod = it }   // try the same way first next time
            setStatus("Link open (${l.name}) - waiting for the MID to answer"); l
        } catch (e: InterruptedException) {
            l.close(); throw e
        } catch (e: Exception) {
            l.close()
            (l as? ClassicLink)?.bondInfo?.takeIf { it.isNotEmpty() }?.let { Bike.log("Pairing: $it") }
            if (e.message == ClassicLink.NOT_PAIRED) {
                // one alarm until a connection works again, not one per retry
                if (!notPairedWarned) { notPairedWarned = true; Alerts.notify(this, L.t("Pair the bike again: phone Bluetooth settings → Gesits.")) }
                setStatus("Bike not paired with this phone - pair it again in Bluetooth settings, then tap Connect")
            } else setStatus("Connect failed: ${e.message}")
            null
        } finally { if (connectingLink === l) connectingLink = null }
    }

    /**
     * Everything sent to the MID goes through here (see Traffic in Safety.kt): only allowed commands, one at a
     * time, at least 0.5 s apart (at most 2 a second, questions and writes together), after the answer to the
     * previous question (max 0.3 s), and never while the MID is still taking in and drawing a picture.
     */
    /** True if [cmd] was sent; false if it was blocked or held back (callers must not assume a write went out). */
    private fun send(cmd: String): Boolean {
        val l = link ?: return false
        if (!Traffic.allowed(cmd)) { Bike.log("blocked command: " + cmd.take(24).trim()); return false }
        val question = Traffic.isQuestion(cmd)
        if (!question && !writeAllowed(Mono.now())) return false
        val asked = awaitSince
        while (awaitKey != null && Mono.now() - asked < Traffic.ANSWER_WAIT_MS) Thread.sleep(10)
        if (asked > 0L && asked != lastCounted) {            // the previous question: answered or not?
            lastCounted = asked
            answeredInRow = if (awaitKey == null) answeredInRow + 1 else 0
        }
        awaitKey = null
        val wait = Traffic.nextAllowedAt(lastSend, quietUntil) - Mono.now()
        if (wait > 0) Thread.sleep(wait)
        // Writes (clock, text, picture) only while the MID is answering - checked again right before the write.
        if (!question) {
            if (Bike.lastRxAt > live.lastAnswerAt) live.answered(Bike.lastRxAt)
            if (!writeAllowed(Mono.now())) return false
        }
        val t0 = Mono.now()
        Bike.trace("-> " + (if (cmd.startsWith("AT+MAPS")) "AT+MAPS (${cmd.length} chars)" else cmd.trim()))
        l.write(cmd.toByteArray(Charsets.US_ASCII))
        lastSend = Mono.now()
        if (lastSend - t0 > 1000) live.busy(lastSend + 2000)   // a long write (picture over BLE): not the MID's silence
        if (cmd.endsWith("?\r\n")) { awaitKey = cmd.substring(3, cmd.length - 3); awaitSince = lastSend }
        quietUntil = maxOf(quietUntil, lastSend + Traffic.quietAfterMs(cmd))
        val k = cmd.substring(3).substringBefore('=').substringBefore('?').trim()
        if (k in Bike.ACK_KEYS && cmd.contains('=')) Bike.sentAt[k] = lastSend
        return true
    }

    /** What the MID's text line shows now, as far as the app knows ("" = blank). */
    @Volatile private var navShown = ""

    /**
     * The text line: a blank first (the MID doesn't erase), then the text. [navShown] follows what really went
     * out: blank if only the blank did, so a held-back text is tried again and never assumed shown.
     */
    private fun showText(text: String): Boolean {
        if (!send(Mid.nav(""))) return false
        navShown = ""
        if (!send(Mid.nav(text))) return false
        navShown = text
        return true
    }

    /**
     * The picture to send, or null when nothing visible changed (the MID keeps showing the last one).
     * Unchanged pictures are still re-sent once a minute in case the MID was restarted.
     */
    private fun picturePayload(s: Settings, now: Long, force: Boolean = false): String? {
        val key = frameKey(s)
        if (!force && key == lastFrameKey && now - lastFrameSent < 300_000) return null   // unchanged: again after 5 min (MID restarted?)
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
        val page = pageFor(s, frameNo + 1)
        if (page == ScreenRenderer.Page.RANGE) return listOf(page, Bike.rangeKm()?.let { Math.round(it) }, s.margins).joinToString("|")
        return listOf(page, nav, SimpleDateFormat("HH:mm", Locale.US).format(Date()), t.raw["SOC"], t.mode, t.gear,
            p, t.raw["TBAT"], t.raw["TMOT"], t.raw["TINV"], t.errorMask, Bike.rangeKm()?.let { Math.round(it) }, trip, s.margins).joinToString("|")
    }

    private fun pageFor(s: Settings, n: Int): ScreenRenderer.Page {
        val moving = (Bike.telemetry.f("SPD") ?: 0f) > 3f
        if (s.navOn && Bike.navFresh()) return ScreenRenderer.Page.NAV   // directions win while navigating
        return when (s.dashStyle) {
            "main" -> ScreenRenderer.Page.MAIN
            "trip" -> ScreenRenderer.Page.TRIP
            "big" -> ScreenRenderer.Page.BIG
            "range" -> ScreenRenderer.Page.RANGE
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
     * How often each value is asked (ms): about 1.6 questions a second while riding, 0.9 parked - below the cap
     * of 2 commands a second in send(), so there is room for the occasional write. Speed every 2 s, slow values
     * rarely, x3 when parked (except speed).
     */
    /** Test mode: the lightest traffic - speed, odometer, battery % and errors only, no writes at all. */
    private val GENTLE_KEYS = mapOf("SPD" to 3000L, "ODO" to 9000L, "SOC" to 30_000L, "ERR" to 30_000L)
    private fun pollPeriod(key: String, parked: Boolean, gentle: Boolean = false): Long {
        if (gentle) GENTLE_KEYS[key]?.let { return it }
        val base = when (key) {
            "SPD" -> return 2000L                 // always (the phone speedometer), parked too
            "IBAT" -> 5000L
            "ODO", "PARK" -> 5000L
            "VBAT", "SOC", "ERR" -> 10_000L
            "MODE" -> 15_000L
            "TBAT", "TMOT", "TINV" -> 30_000L
            "SOH", "RANGE" -> 120_000L
            else -> 30_000L
        }
        return if (parked) base * 3 else base
    }

    private val lastPolled = HashMap<String, Long>()
    private var lastFrameKey = ""
    private var lastFrameSent = 0L

    private fun saveState(s: Settings) = try {
        synchronized(Bike.range) { s.rangeState = Bike.range.save(); s.energyState = Bike.energy.save() }
        s.tripState = synchronized(Bike.trip) { Bike.trip.save() }
        s.batteryState = synchronized(Bike.battery) { Bike.battery.save() }
        BikeWidget.update(this)
    } catch (e: Exception) { Bike.log("Saving failed: ${e.message}") }

    private val live = Liveness()
    @Volatile private var connectingLink: MidLink? = null
    @Volatile private var gentleNow = false
    /** Questions answered in a row (0 after one went unanswered): no writes into a MID that just went quiet. */
    @Volatile private var answeredInRow = 0
    private var lastCounted = 0L
    private fun writeAllowed(now: Long) = live.canWrite(now) && answeredInRow >= 2 &&
        Bike.midHungSince == 0L && !Bike.writesBlocked && !Bike.dataFrozen && !gentleNow

    private fun warnHung(why: String) {
        if (!Bike.hangWarned) { Alerts.notify(this, L.t("The MID stopped answering. When it is safe, stop and switch the bike off and on. Until it answers again the app only asks one question every 15 s.")); Bike.hangWarned = true }
        Bike.log(why)
        setStatus("The MID stopped answering - app silent (stop safely, switch the bike off and on)")
        refreshNotif()
    }

    private fun runLoop(gen: Int) {
        val mine = { Bike.running && gen == Bike.workerGen }
        synchronized(Bike) { Bike.workersAlive++ }
        Bike.workerAlive = true
        val s = Settings(this)
        var navIdx = 0
        var lastNav = 0L; var lastMinute = -1; var lastLearn = 0L; var lastSave = 0L; var lastFrame = 0L
        var backoff = 2000L
        var noAnswerSince = Mono.now()                 // for auto-stop: time without any answer from the MID
        var lastProbe = 0L
        var lastOdoSeen = Float.NaN; var odoChangedAt = 0L; var fastSince = 0L
        var lastWidget = 0L; var lastNotif = 0L; var lastErrorLog = 0L
        var lastMovingAt = 0L           // when the latest speed answer arrived, if it was above 3 km/h
        var answeredHere = false        // the MID answered on the current link
        var hadLink = false             // a link was open (so a closed one means it dropped)
        var suspectHang = false         // the link dropped while riding: a hang if the next link stays silent 15 s
        var silentLinks = 0             // links in a row on which the MID never answered
        var ownLink: MidLink? = null
        try {
            Bike.loadRange(s)
            Bike.loadTrip(s)
            Bike.loadBattery(s)
            L.init(s.language)
            s.migrateSafeDefaults()
            Bike.loadSafety(this)
            if (Bike.pendingReset) {                  // a different bike: fresh answer checks and current sign
                Bike.telemetry.resetAll(); Bike.currentSign.load("1;false"); s.currentSign = null; Bike.pendingReset = false
                Bike.unfreeze()                         // the old bike's frozen odometer means nothing for this one
            }
            Bike.currentSign.load(s.currentSign); Bike.telemetry.ibatSign = Bike.currentSign.sign
            // Battery size from rides recorded earlier, if the energy model has nothing yet.
            try {
                RideStore.all(this).filter { it.energyWh > 0f && it.socStart - it.socEnd >= 2f }.let { r ->
                    if (r.isNotEmpty()) synchronized(Bike.range) {
                        Bike.energy.seedCapacity(r.sumOf { it.energyWh.toDouble() }.toFloat(), r.sumOf { (it.socStart - it.socEnd).toDouble() }.toFloat())
                    }
                }
            } catch (e: Exception) { Bike.log("Reading rides failed: ${e.message}") }

            while (mine()) {
                try {
                    val tNow = Mono.now()
                    if (s.autoStopMin > 0 && tNow - noAnswerSince > s.autoStopMin * 60_000L) {
                        setStatus(if (Bike.midHungSince > 0L) "No answer from the MID for ${s.autoStopMin} min - stopped" else "Bike off for ${s.autoStopMin} min - stopped to save battery")
                        Bike.running = false
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                        break
                    }
                    if (link?.isOpen != true) {
                        Bike.connected = false
                        Bike.telemetry.clearLive()
                        holdWakeLock(false)                      // let the phone sleep between retries
                        if (hadLink) {
                            // The link dropped (bike switched off, out of range, or the MID's Bluetooth gave up):
                            // pause - growing each time until the MID answers again - before trying again.
                            hadLink = false
                            Bike.dumpTrace("Connection lost")
                            if (!answeredHere) silentLinks++
                            if (Bike.midHungSince == 0L) {
                                when {
                                    // A write got stuck: the MID stopped taking data while the link was up.
                                    link?.stalled == true -> { Bike.markHung(this, tNow); Bike.outbox.clear(); warnHung("The MID stopped taking data - treated as a hang") }
                                    // Several links in a row without one answer: the bike is on (links open) but the MID is silent.
                                    silentLinks >= 3 -> { Bike.markHung(this, tNow); Bike.outbox.clear(); warnHung("The MID didn't answer on 3 links in a row - treated as a hang") }
                                    // Dropped while riding: not a hang yet - if the next link opens (bike on) and the MID
                                    // stays silent 15 s, it is. If the bike was switched off, no link opens: no alarm.
                                    answeredHere && live.movingWhenItStopped(lastMovingAt) -> suspectHang = true
                                }
                            }
                            BikeWidget.update(this)
                            if (Bike.midHungSince == 0L) setStatus("Connection lost - retrying")
                            Thread.sleep(backoff); backoff = minOf(backoff * 2, 60_000L)
                            if (!mine()) break
                        }
                        if (lastSave != 0L) { saveState(s); lastSave = 0L }
                        Bike.recorder.tick(System.currentTimeMillis())?.let { rideFinished(it, s) }   // close the ride after the idle time
                        link?.close(); link = null
                        val l = openLink(s)
                        if (!mine()) { l?.close(); break }
                        link = l; ownLink = l
                        if (l == null) {
                            Thread.sleep(backoff)
                            backoff = minOf(backoff * 2, 60_000L)   // 2, 4, 8 ... 60 s
                            continue
                        }
                        val linkAt = Mono.now()
                        hadLink = true; answeredHere = false; Bike.outbox.clear()
                        live.connected(linkAt); Bike.lastRxAt = 0L; lastMovingAt = 0L; answeredInRow = 0
                        lastMinute = -1; lastFrameKey = ""; lastPolled.clear(); lastSave = linkAt
                        navShown = ""; lastFrame = 0L
                        lastOdoSeen = Float.NaN; odoChangedAt = linkAt; fastSince = 0L
                        synchronized(parser) { parser.reset() }
                        holdWakeLock(true)                       // keep the timers running while waiting for an answer
                        send(Mid.VERSION_QUERY); lastProbe = linkAt
                        BikeWidget.update(this)
                    }
                    val now = Mono.now()
                    if (Bike.lastRxAt > live.lastAnswerAt) live.answered(Bike.lastRxAt)
                    if (live.lastAnswerAt > 0L) lastAnsweredAt = live.lastAnswerAt
                    if (live.lastAnswerAt > 0L && !answeredHere) {
                        // First answer on this link.
                        notPairedWarned = false; repairAsked = false
                        answeredHere = true; suspectHang = false; silentLinks = 0
                        backoff = 2000L                          // only a MID that answers resets the pause
                        if (Bike.midHungSince > 0L) {
                            Bike.clearHung(this)               // writes stay blocked until confirmed on Home
                            setStatus("The MID answers again - questions only until you confirm its display works")
                        } else setStatus("Connected to ${s.deviceName ?: "the bike"}")
                        Bike.connected = true
                        holdWakeLock(true)
                    }
                    if (answeredHere && Bike.midHungSince > 0L && live.lastAnswerAt > Bike.midHungSince) {
                        // The MID came back by itself on the same link. It might still be half-frozen: questions only
                        // (no clock, text or pictures) until you confirm on Home that its display works.
                        Bike.clearHung(this)
                        Bike.connected = true; holdWakeLock(true)
                        setStatus("The MID answers again - questions only until you confirm its display works")
                    }
                    if (answeredHere && !live.silent(now)) noAnswerSince = now

                    // Quiet modes: never answered on this link, or judged hung. Only the odd question goes out.
                    if (!answeredHere || Bike.midHungSince > 0L) {
                        fastSince = 0L; odoChangedAt = now      // the frozen-values check starts afresh afterwards
                        val every = if (Bike.midHungSince > 0L) 15_000L else 3_000L
                        if (now - lastProbe >= every) { lastProbe = now; send(Mid.VERSION_QUERY) }
                        val limit = if (suspectHang) Liveness.MOVING_HANG_MS else Liveness.STOPPED_HANG_MS
                        if (Bike.midHungSince == 0L && live.silenceMs(now) >= limit) {
                            // The link is up (so the bike is on) but the MID doesn't answer: it has hung - e.g. it froze
                            // before the app connected, or while riding just before the link dropped.
                            Bike.markHung(this, now); Bike.outbox.clear(); lastProbe = now; holdWakeLock(false)
                            warnHung(if (suspectHang) "Link lost while riding and the MID stays silent - treated as a hang" else "The MID doesn't answer on an open link - treated as a hang")
                            suspectHang = false
                        } else if (Bike.midHungSince == 0L && !Bike.status.startsWith("Link open")) {
                            setStatus("Link open - waiting for the MID to answer")
                        }
                        Thread.sleep(500)
                        continue
                    }
                    if (live.silent(now)) {
                        // It answered on this link, then went silent: stop asking (one question every 3 s).
                        fastSince = 0L; odoChangedAt = now
                        if (Bike.connected) { Bike.connected = false; Bike.telemetry.clearLive(); BikeWidget.update(this); Bike.dumpTrace("No answer") }
                        when (live.verdict(now, lastMovingAt)) {
                            Liveness.Verdict.HUNG -> {
                                Bike.markHung(this, now); lastProbe = now; Bike.outbox.clear(); holdWakeLock(false)
                                warnHung("The MID went silent with the link up - treated as a hang")
                            }
                            Liveness.Verdict.WAIT -> {
                                if (!Bike.status.startsWith("No answer")) setStatus("No answer from the bike (switched off?) - waiting")
                                if (now - lastProbe >= 3000L) { lastProbe = now; send(Mid.VERSION_QUERY) }
                            }
                        }
                        refreshNotif()
                        Thread.sleep(500)
                        continue
                    }
                    if (!Bike.connected) { Bike.connected = true; setStatus("Connected to ${s.deviceName ?: "the bike"}") }
                    val talk = writeAllowed(now)               // writes only while the MID answers (checked again in send)
                    val tm = Bike.telemetry
                    // questions-only and frozen modes ask at the parked rate
                    val slow = (tm.gear == "P" && (tm.f("SPD") ?: 0f) < 1f) || Bike.writesBlocked || Bike.dataFrozen
                    val gentle = s.gentle
                    if (gentle != gentleNow) { gentleNow = gentle; Bike.log(if (gentle) "Test mode: lightest traffic, no writes" else "Test mode off") }
                    if (s.screenMode.startsWith("grid") && System.currentTimeMillis() > s.gridUntil) { s.screenMode = "off"; Bike.log("Calibration grid finished - pictures off") }
                    val picturesOn = s.screenMode != "off"

                    Bike.outbox.poll()?.let { cmd -> if (send(cmd)) Bike.log("-> " + cmd.trim()) }   // one console question per pass

                    // Ask the single most overdue value.
                    var nextPoll = Long.MAX_VALUE
                    var due: String? = null; var dueScore = 1.0
                    for (k in Mid.POLL_KEYS) {
                        if (gentle && k !in GENTLE_KEYS) continue
                        val period = pollPeriod(k, slow, gentle)
                        val last = lastPolled[k] ?: 0L
                        val score = (now - last).toDouble() / period
                        if (score >= dueScore) { due = k; dueScore = score }
                        nextPoll = minOf(nextPoll, last + period)
                    }
                    if (due != null) { send(Mid.query(due)); lastPolled[due] = now; nextPoll = now + Traffic.MIN_GAP_MS }

                    // Clock: on connect and whenever the phone's minute changes (no extra repeats).
                    if (s.syncTime && talk) {
                        val c = Calendar.getInstance()
                        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                        if (minute != lastMinute && send(Mid.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))) lastMinute = minute
                    }
                    // Who was moving when: the latest speed answer, by the time it arrived (not by this loop's time).
                    tm.f("SPD")?.let { v -> lastMovingAt = if (v > 3f) now - (tm.ageMs("SPD", now) ?: 0L) else 0L }
                    // Once per second: charts, ride recording, parking, alarms, trip and range learning.
                    if (now - lastLearn >= 1000) {
                        val wall = System.currentTimeMillis()      // dates of rides, trips and charts
                        tm.expire(now) { k -> if (k in Mid.POLL_KEYS) 3 * pollPeriod(k, slow, gentle) + 5000L else Long.MAX_VALUE }
                        val spdNow = if (tm.fresh("SPD", 5000L, now)) tm.f("SPD") else null
                        // MID answering but frozen? Speed of 15 km/h or more for 90 s with the odometer not moving.
                        // Only a fresh odometer answer counts (within two of its poll periods): garbled, rejected answers are no proof
                        // that it stopped moving.
                        val odoNow = if (tm.fresh("ODO", 2 * pollPeriod("ODO", slow, gentle) + 3000L, now)) tm.f("ODO") else null
                        if (odoNow == null) { fastSince = 0L; odoChangedAt = now }
                        else if (odoNow != lastOdoSeen) { lastOdoSeen = odoNow; odoChangedAt = now }
                        // Frozen ends only when the odometer has moved past the value it was frozen at (a new link
                        // alone proves nothing). Writes stay off until you confirm on Home.
                        if (Bike.dataFrozen && odoNow != null && !Bike.frozenOdo.isNaN() && odoNow > Bike.frozenOdo + 0.15f) {   // moved 0.2 km or more
                            Bike.unfreeze(); Bike.log("Odometer moving again (writes stay off until you confirm on Home)"); refreshNotif()
                        }
                        if (odoNow != null && (spdNow ?: 0f) >= 15f) { if (fastSince == 0L) fastSince = now } else fastSince = 0L
                        if (!Bike.dataFrozen && odoNow != null && fastSince > 0L && now - fastSince > 90_000L && now - odoChangedAt > 90_000L) {
                            Bike.freeze(odoNow); Bike.blockWrites(this); tm.clearLive(); refreshNotif(); BikeWidget.update(this); Bike.dumpTrace("values frozen")
                            Alerts.notify(this, L.t("The MID's values look frozen (speed shown but the odometer doesn't move). When it is safe, stop and switch the bike off and on."))
                            Bike.log("MID values look frozen - writes stopped")
                        }
                        // the current sign learns from each new reading once (not from the same one again and again)
                        if (Bike.currentSign.update(spdNow, if (tm.fresh("IBAT", 1000L, now)) tm.ibatRaw else null)) {
                            tm.ibatSign = Bike.currentSign.sign
                            Bike.log(if (tm.ibatSign < 0) "This battery reports current drawn as negative - corrected" else "Battery current sign: normal")
                        }
                        if (Bike.currentSign.decided && s.currentSign != Bike.currentSign.save()) s.currentSign = Bike.currentSign.save()
                        Bike.addSample(Sample(wall, tm.f("SPD"), tm.powerW, null, tm.f("TBAT"), tm.f("TMOT"), tm.f("TINV")))
                        Bike.recorder.sample(wall, tm.f("SPD"), tm.f("ODO"), tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"))
                            ?.let { rideFinished(it, s) }
                        val gearNow = tm.gear
                        if (gearNow == "P" && lastGear == "D") Parking.capture(this, s)    // stand down / parked
                        if (gearNow != null) lastGear = gearNow
                        val evs = synchronized(Bike.battery) {
                            // charging is told from the current's sign, so only once that sign is known for this battery
                            Bike.battery.update(tm.f("SOC"), if (Bike.currentSign.decided) tm.f("IBAT") else null, tm.f("SPD"), wall, s.chargeTarget.toFloat())
                        }
                        evs.forEach { e ->
                            when (e) {
                                is BatteryTracker.Event.ChargeStarted -> Bike.log(L.t("Charging started"))
                                is BatteryTracker.Event.TargetReached -> if (Bike.currentSign.decided) Alerts.notify(this, L.t("Battery reached") + " ${e.soc.toInt()}%")
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
                        Bike.speedWarn.update(spdNow, now)
                        if (now - lastWidget >= 60_000) { BikeWidget.update(this); lastWidget = now }
                        if (now - lastNotif >= 10_000) { refreshNotif(); lastNotif = now }
                        if (s.alarmsOn) {
                            Bike.alarms.socLow = s.socLow
                            Bike.alarms.evaluate(tm.f("SOC"), tm.f("TBAT"), tm.f("TMOT"), tm.f("SOH"), tm.errors, now)
                                .forEach { Alerts.notify(this, it) }
                        }
                        synchronized(Bike.trip) { Bike.trip.update(tm.f("ODO"), tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), wall) }
                        val chargingNow = synchronized(Bike.battery) { Bike.battery.charging }
                        synchronized(Bike.range) { Bike.energy.update(tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), tm.f("ODO"), chargingNow, wall) }
                        val odo = tm.f("ODO"); val soc = tm.f("SOC")
                        if (odo != null && soc != null) {
                            val learned = synchronized(Bike.range) {
                                Bike.range.priorKmPerPercent = Bike.priorKmPerPercent(s)
                                Bike.range.update(odo, soc, wall, chargingNow)
                            }
                            if (learned) Bike.log("Range learned: " + synchronized(Bike.range) { Bike.range.describe() })
                            if (learned || now - lastSave >= 60_000) { saveState(s); lastSave = now }
                        }
                        lastLearn = now
                    }

                    // Text line. The MID draws it over the picture without erasing old characters, so texts change
                    // rarely, each change goes out after a blank, and a text that ended is cleared.
                    val nav = if (s.navOn && Bike.navFresh()) Bike.nav else null
                    val own = Mid.splitNav(s.navText)
                    val line: String? = when {
                        Bike.speedWarn.active -> "! " + L.t("SLOW DOWN") + " ${s.speedLimit}"
                        // Pictures on: range and directions are in the picture; the text line (drawn over it without
                        // erasing) stays unused, so nothing overlaps. Only the speed warning uses it.
                        picturesOn -> null
                        nav != null -> (navTurnWord(nav.turn) + " " + nav.street).trim().take(Mid.NAV_MAX)   // distance: in the picture
                        own.size > 1 -> null                                                     // your own long text rotates
                        own.size == 1 -> own[0]
                        s.rangeLine -> Bike.rangeKm()?.let { (if (L.indo) "JARAK " else "RANGE ") + Math.round(it) + " km" }
                            ?: if (s.navDate) Mid.dateText() else null
                        s.navDate -> Mid.dateText()
                        else -> null
                    }
                    if (talk && picturesOn) {
                        if (line != null && line != navShown) showText(line)
                        else if (line == null && navShown.isNotEmpty()) { navShown = ""; lastFrameKey = "" }   // the next picture wipes it
                    } else if (talk) {
                        if (line == null && own.size > 1) {
                            if (now - lastNav >= 15_000 || navShown.isEmpty()) {                 // own long text: a part every 15 s
                                navIdx %= own.size
                                if (showText(own[navIdx])) { lastNav = now; navIdx++ }
                            }
                        } else if ((line ?: "") != navShown) {
                            if (line == null) send(Mid.nav("")).also { if (it) navShown = "" } else showText(line)
                        }
                    }

                    // Pictures: only if switched on and the link suits them, when what they show changes (or once a
                    // minute), at most one every 10 s, never bigger than the MID can take; a text change never forces one.
                    // After each picture the current text is drawn again (the picture wiped that area).
                    val frameMs = maxOf(s.frameSeconds * 1000L, Traffic.PICTURE_MIN_INTERVAL_MS)
                    if (talk && picturesOn && now - lastFrameSent >= frameMs && now - lastFrame >= frameMs &&
                        answeredInRow >= 3 && now - live.lastAnswerAt < 1000L) {   // only to a MID that is answering right now
                        lastFrame = now
                        if (link?.picturesOk == false) {
                            Bike.lastFrameInfo = L.t("This Bluetooth LE link is too slow for pictures - not sent")
                        } else {
                            val b64 = try { picturePayload(s, now) } catch (e: Exception) { Bike.log("Picture failed: ${e.message}"); null }
                            if (b64 != null && b64.length > Traffic.PICTURE_MAX_CHARS) {
                                Bike.lastFrameInfo = L.t("Picture too big for the MID - not sent")
                            } else if (b64 != null) {
                                val t0 = Mono.now()
                                if (send(Mid.maps(b64))) {
                                    lastFrameSent = Mono.now()                      // the interval counts from the end of the write
                                    val shown = navShown
                                    if (shown.isNotEmpty() && !send(Mid.nav(shown))) navShown = "\u0000"   // redraw failed: again next pass
                                    Bike.lastFrameInfo = (if (L.indo) "Gambar terkirim: " else "Picture sent: ") + "${b64.length} " + (if (L.indo) "karakter dalam " else "chars in ") + "${Mono.now() - t0} ms"
                                } else lastFrameKey = ""                                        // not sent: try again later
                            } else if (s.screenMode == "picture" && !java.io.File(filesDir, "picture.b64").exists()) {
                                Bike.lastFrameInfo = L.t("No picture chosen")
                            }
                        }
                    }

                    // Sleep until the next thing is due.
                    val nextDue = minOf(nextPoll, lastLearn + 1000,
                        if (talk && picturesOn) maxOf(lastFrame + frameMs, lastFrameSent + frameMs) else Long.MAX_VALUE)
                    Thread.sleep((nextDue - Mono.now()).coerceIn(100L, 1000L))
                } catch (e: InterruptedException) {
                    break
                } catch (e: java.io.IOException) {
                    if (!mine()) break                         // an old worker: leave the state to the new one
                    // The Bluetooth link failed: close it (the drop is handled at the top, which also reads whether a
                    // write got stuck - so the link object is kept until then).
                    Bike.log("Connection error: ${e.message}")
                    Bike.connected = false; Bike.telemetry.clearLive()
                    link?.close()
                } catch (e: Exception) {
                    // A bug elsewhere (drawing...): keep the link, note it, carry on.
                    val n = Mono.now()
                    if (n - lastErrorLog > 60_000) { Bike.log("Error: ${e.javaClass.simpleName}: ${e.message}"); lastErrorLog = n }
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
        } finally {
            if (gen == Bike.workerGen) {
                try { Bike.recorder.finish(System.currentTimeMillis())?.let { rideFinished(it, s) } } catch (_: Exception) {}
                saveState(s)
                holdWakeLock(false)
                link?.close(); link = null
                Bike.connected = false
                Bike.telemetry.clearLive()
                if (!Bike.status.startsWith("Bike off") && !Bike.status.startsWith("No answer from the MID for")) setStatus("Stopped")
                val stillRunning = Bike.running
                Bike.running = false                     // also after an unexpected error: never "running" without a worker
                try { BikeWidget.update(this) } catch (_: Exception) {}
                if (stillRunning) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }   // ended by an error: no service left behind
            } else {
                // An old worker: close only its own link, never the new worker's.
                ownLink?.close()
                if (link === ownLink) link = null
            }
            synchronized(Bike) { Bike.workersAlive--; Bike.workerAlive = Bike.workersAlive > 0 }
        }
    }

    private var lastGear: String? = null
    @Volatile private var connectedAt = 0L
    @Volatile private var pairingAllowed = false   // see ClassicLink.allowPairing
    @Volatile private var notPairedWarned = false
    /** A pairing request was already made after this drop (one per drop at most). */
    @Volatile private var repairAsked = false
    /** When the MID last answered on a link of this service (Mono): a paired way that worked is retried for 30 min after. */
    @Volatile private var lastAnsweredAt = 0L
    private var lastSohDay: String? = null

    private fun navTurnWord(t: NavParser.Turn) = L.t(when (t) {
        NavParser.Turn.LEFT -> "LEFT"; NavParser.Turn.RIGHT -> "RIGHT"
        NavParser.Turn.SLIGHT_LEFT -> "KEEP LEFT"; NavParser.Turn.SLIGHT_RIGHT -> "KEEP RIGHT"
        NavParser.Turn.UTURN -> "U-TURN"; NavParser.Turn.ROUNDABOUT -> "ROUNDABOUT"
        NavParser.Turn.STRAIGHT -> "STRAIGHT"; NavParser.Turn.ARRIVE -> "ARRIVED"; NavParser.Turn.NONE -> ""
    })

    private fun rideFinished(r: Ride, s: Settings) = try {
        RideStore.add(this, r)
        Bike.log(String.format(java.util.Locale.US, "Ride saved: %.1f km, %.0f Wh", r.km, r.energyWh))
        Parking.capture(this, s)
    } catch (e: Exception) { Bike.log("Saving the ride failed: ${e.message}") }

    override fun onDestroy() {
        holdWakeLock(false)
        Bike.running = false
        worker?.interrupt()
        connectingLink?.close()
        link?.close()
        super.onDestroy()
    }
}
