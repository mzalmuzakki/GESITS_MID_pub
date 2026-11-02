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
    /**
     * When the MID was judged hung (0 = not). Kept across link drops, reconnects and Disconnect/Connect for
     * as long as the app runs: only a real answer from the MID clears it.
     */
    @Volatile var midHungSince = 0L
    @Volatile var hangWarned = false
    /** The MID came back by itself after a hang: questions only until it has been switched off and on. */
    @Volatile var writesBlocked = false
    /** A different bike was chosen: the next worker starts the answer checks afresh. */
    @Volatile var pendingReset = false

    /** The hang is also kept on disk (its own file, untouched by "Delete all my data"), so an app restart can't forget it. */
    fun markHung(ctx: Context, t: Long) {
        midHungSince = t
        ctx.getSharedPreferences("safety", Context.MODE_PRIVATE).edit().putLong("hungSince", t).apply()
    }
    fun clearHung(ctx: Context) {
        midHungSince = 0L; hangWarned = false
        ctx.getSharedPreferences("safety", Context.MODE_PRIVATE).edit().remove("hungSince").apply()
    }
    fun loadHung(ctx: Context) {
        if (midHungSince == 0L) midHungSince = ctx.getSharedPreferences("safety", Context.MODE_PRIVATE).getLong("hungSince", 0L)
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
            connectingLink?.close()               // abort a Bluetooth connect still in progress
            link?.close()
            holdWakeLock(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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
            if (e.message == ClassicLink.NOT_PAIRED) {
                if (!Bike.status.startsWith("Bike not paired")) Alerts.notify(this, L.t("Pair the bike again: phone Bluetooth settings → Gesits."))
                setStatus("Bike not paired with this phone - pair it again in Bluetooth settings, then tap Connect")
            } else setStatus("Connect failed: ${e.message}")
            null
        } finally { connectingLink = null }
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
        if (!question && !writeAllowed(System.currentTimeMillis())) return false
        val asked = awaitSince
        while (awaitKey != null && System.currentTimeMillis() - asked < Traffic.ANSWER_WAIT_MS) Thread.sleep(10)
        awaitKey = null
        val wait = Traffic.nextAllowedAt(lastSend, quietUntil) - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        // Writes (clock, text, picture) only while the MID is answering - checked again right before the write.
        if (!question) {
            if (Bike.lastRxAt > live.lastAnswerAt) live.answered(Bike.lastRxAt)
            if (!writeAllowed(System.currentTimeMillis())) return false
        }
        val t0 = System.currentTimeMillis()
        l.write(cmd.toByteArray(Charsets.US_ASCII))
        lastSend = System.currentTimeMillis()
        if (lastSend - t0 > 1000) live.busy(lastSend + 2000)   // a long write (picture over BLE): not the MID's silence
        if (cmd.endsWith("?\r\n")) { awaitKey = cmd.substring(3, cmd.length - 3); awaitSince = lastSend }
        quietUntil = maxOf(quietUntil, lastSend + Traffic.quietAfterMs(cmd))
        val k = cmd.substring(3).substringBefore('=').substringBefore('?').trim()
        if (k in Bike.ACK_KEYS && cmd.contains('=')) Bike.sentAt[k] = lastSend
        return true
    }

    /** The text line: a blank first (the MID doesn't erase), then the text. Counts as shown only if both went out. */
    private fun sendText(text: String): Boolean = send(Mid.nav("")) && send(Mid.nav(text))

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
     * How often each value is asked (ms): about 1.6 questions a second while riding, 0.9 parked - below the cap
     * of 2 commands a second in send(), so there is room for the occasional write. Speed every 2 s, slow values
     * rarely, x3 when parked (except speed).
     */
    private fun pollPeriod(key: String, parked: Boolean): Long {
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
    @Volatile private var dataFrozen = false         // the MID answers, but speed and odometer look frozen
    @Volatile private var connectingLink: MidLink? = null
    private fun writeAllowed(now: Long) = live.canWrite(now) && Bike.midHungSince == 0L && !Bike.writesBlocked && !dataFrozen

    private fun runLoop(gen: Int) {
        val mine = { Bike.running && gen == Bike.workerGen }
        Bike.workerAlive = true
        val s = Settings(this)
        var navIdx = 0
        var lastNav = 0L; var lastMinute = -1; var lastLearn = 0L; var lastSave = 0L; var lastFrame = 0L
        var backoff = 2000L
        var noAnswerSince = System.currentTimeMillis()          // for auto-stop: time without any answer from the MID
        Bike.loadRange(s)
        Bike.loadTrip(s)
        Bike.loadBattery(s)
        L.init(s.language)
        s.migrateSafeDefaults()
        Bike.currentSign.load(s.currentSign); Bike.telemetry.ibatSign = Bike.currentSign.sign
        Bike.loadHung(this)
        if (Bike.pendingReset) { Bike.telemetry.resetAll(); Bike.pendingReset = false }
        // Battery size from rides recorded earlier, if the energy model has nothing yet.
        try {
            RideStore.all(this).filter { it.energyWh > 0f && it.socStart - it.socEnd >= 2f }.let { r ->
                if (r.isNotEmpty()) synchronized(Bike.range) {
                    Bike.energy.seedCapacity(r.sumOf { it.energyWh.toDouble() }.toFloat(), r.sumOf { (it.socStart - it.socEnd).toDouble() }.toFloat())
                }
            }
        } catch (e: Exception) { Bike.log("Reading rides failed: ${e.message}") }
        var lastProbe = 0L
        var lastNavText = ""
        var lastDropAt = 0L
        var lastOdoSeen = Float.NaN; var odoChangedAt = 0L; var fastSince = 0L
        var lastWidget = 0L; var lastNotif = 0L; var lastErrorLog = 0L
        var lastMovingAt = 0L
        var answeredHere = false        // the MID answered on the current link
        var hadLink = false             // a link was open (so a closed one means it dropped)
        var ownLink: MidLink? = null
        try {
            while (mine()) {
                try {
                    val tNow = System.currentTimeMillis()
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
                            val dropAt = System.currentTimeMillis()
                            lastDropAt = dropAt
                            // A write that got stuck, or a drop while riding, is not "switched off": the MID hung.
                            val stalled = link?.stalled == true
                            val whileMoving = answeredHere && live.movingWhenItStopped(lastMovingAt) && dropAt - live.lastAnswerAt < 20_000L
                            if (Bike.midHungSince == 0L && (stalled || whileMoving)) {
                                Bike.markHung(this, dropAt); Bike.outbox.clear()
                                if (!Bike.hangWarned) { Alerts.notify(this, L.t("The MID stopped answering. When it is safe, stop and switch the bike off and on. Until it answers again the app only asks one question every 15 s.")); Bike.hangWarned = true }
                                Bike.log(if (stalled) "The MID stopped taking data - treated as a hang" else "Link lost while riding - treated as a hang")
                            }
                            BikeWidget.update(this)
                            setStatus(if (Bike.midHungSince > 0L) "The MID stopped answering - app silent (stop safely, switch the bike off and on)" else "Connection lost - retrying")
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
                        val linkAt = System.currentTimeMillis()
                        // "Questions only" ends once the link was down for 10 s or more (the bike was switched off and on).
                        if (Bike.writesBlocked && lastDropAt > 0L && linkAt - lastDropAt >= 10_000L) {
                            Bike.writesBlocked = false; Bike.log("Link was down 10 s or more - normal mode again")
                        }
                        hadLink = true; answeredHere = false; dataFrozen = false; Bike.outbox.clear()
                        live.connected(linkAt); Bike.lastRxAt = 0L; lastMovingAt = 0L
                        lastMinute = -1; lastFrameKey = ""; lastPolled.clear(); lastSave = linkAt
                        lastNavText = ""; lastFrame = 0L
                        lastOdoSeen = Float.NaN; odoChangedAt = linkAt; fastSince = 0L
                        synchronized(parser) { parser.reset() }
                        send(Mid.VERSION_QUERY); lastProbe = System.currentTimeMillis()
                        BikeWidget.update(this)
                    }
                    val now = System.currentTimeMillis()
                    if (Bike.lastRxAt > live.lastAnswerAt) live.answered(Bike.lastRxAt)
                    if (live.lastAnswerAt > 0L && !answeredHere) {
                        // First answer on this link.
                        answeredHere = true
                        backoff = 2000L                          // only a MID that answers resets the pause
                        if (Bike.midHungSince > 0L) {           // answered on a new link: the bike was switched off and on
                            Bike.clearHung(this)
                            setStatus("The MID answers again - connected")
                        } else setStatus("Connected to ${s.deviceName ?: "the bike"}")
                        Bike.connected = true
                        holdWakeLock(true)
                    }
                    if (answeredHere && Bike.midHungSince > 0L && live.lastAnswerAt > Bike.midHungSince) {
                        // The MID came back by itself on the same link. It might still be half-frozen: questions
                        // only (no clock, text or pictures) until the bike is switched off and on.
                        Bike.clearHung(this); Bike.writesBlocked = true
                        Bike.connected = true; holdWakeLock(true)
                        setStatus("The MID answers again - questions only until the bike is switched off and on")
                    }
                    if (answeredHere && !live.silent(now)) noAnswerSince = now

                    // Quiet modes: never answered on this link, or judged hung. Only the odd question goes out.
                    if (!answeredHere || Bike.midHungSince > 0L) {
                        val every = if (Bike.midHungSince > 0L) 15_000L else 3_000L
                        if (now - lastProbe >= every) { lastProbe = now; send(Mid.VERSION_QUERY) }
                        if (Bike.midHungSince == 0L && live.silenceMs(now) >= Liveness.STOPPED_HANG_MS) {
                            // The link is up (so the bike is on) but the MID hasn't answered for 30 s: it has hung,
                            // e.g. it froze before the app connected.
                            Bike.markHung(this, now); Bike.outbox.clear(); lastProbe = now
                            if (!Bike.hangWarned) { Alerts.notify(this, L.t("The MID stopped answering. When it is safe, stop and switch the bike off and on. Until it answers again the app only asks one question every 15 s.")); Bike.hangWarned = true }
                            setStatus("The MID stopped answering - app silent (stop safely, switch the bike off and on)")
                            refreshNotif()
                        } else if (Bike.midHungSince == 0L && !Bike.status.startsWith("Link open")) {
                            setStatus("Link open - waiting for the MID to answer")
                        }
                        Thread.sleep(500)
                        continue
                    }
                    if (live.silent(now)) {
                        // It answered on this link, then went silent: stop asking (one question every 3 s).
                        if (Bike.connected) { Bike.connected = false; Bike.telemetry.clearLive(); BikeWidget.update(this) }
                        when (live.verdict(now, lastMovingAt)) {
                            Liveness.Verdict.HUNG -> {
                                Bike.markHung(this, now); lastProbe = now; Bike.outbox.clear()
                                holdWakeLock(false)
                                if (!Bike.hangWarned) {
                                    Alerts.notify(this, L.t("The MID stopped answering. When it is safe, stop and switch the bike off and on. Until it answers again the app only asks one question every 15 s."))
                                    Bike.hangWarned = true
                                }
                                setStatus("The MID stopped answering - app silent (stop safely, switch the bike off and on)")
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
                    val parked = tm.gear == "P" && (tm.f("SPD") ?: 0f) < 1f
                    if (s.screenMode.startsWith("grid") && now > s.gridUntil) { s.screenMode = "off"; Bike.log("Calibration grid finished - pictures off") }
                    val picturesOn = s.screenMode != "off"

                    Bike.outbox.poll()?.let { cmd -> send(cmd); Bike.log("-> " + cmd.trim()) }   // one console question per pass

                    // Ask the single most overdue value.
                    var nextPoll = Long.MAX_VALUE
                    var due: String? = null; var dueScore = 1.0
                    for (k in Mid.POLL_KEYS) {
                        val period = pollPeriod(k, parked || Bike.writesBlocked)
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
                    // Once per second: charts, ride recording, parking, alarms, trip and range learning.
                    if (now - lastLearn >= 1000) {
                        // single values that stopped coming (only polled ones; the version stays)
                        tm.expire(now) { k -> if (k in Mid.POLL_KEYS) 3 * pollPeriod(k, parked) + 5000L else Long.MAX_VALUE }
                        val spdNow = if (tm.fresh("SPD", 5000L, now)) tm.f("SPD") else null
                        if (tm.fresh("SPD", 2500L, now) && (spdNow ?: 0f) > 3f) lastMovingAt = now
                        // MID answering but frozen? Speed of 15 km/h or more for 90 s with the odometer not moving.
                        val odoNow = tm.f("ODO")
                        if (odoNow != null && odoNow != lastOdoSeen) {
                            if (dataFrozen) { dataFrozen = false; Bike.log("Odometer moving again - writes resume") }
                            lastOdoSeen = odoNow; odoChangedAt = now
                        }
                        if ((spdNow ?: 0f) >= 15f) { if (fastSince == 0L) fastSince = now } else fastSince = 0L
                        if (!dataFrozen && fastSince > 0L && now - fastSince > 90_000L && now - odoChangedAt > 90_000L) {
                            dataFrozen = true
                            Alerts.notify(this, L.t("The MID's values look frozen (speed shown but the odometer doesn't move). When it is safe, stop and switch the bike off and on."))
                            Bike.log("MID values look frozen - writes stopped")
                        }
                        // the current sign learns from each new reading once (not from the same one again and again)
                        if (Bike.currentSign.update(spdNow, if (tm.fresh("IBAT", 1000L, now)) tm.ibatRaw else null)) {
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
                            // charging is told from the current's sign, so only once that sign is known for this battery
                            Bike.battery.update(tm.f("SOC"), if (Bike.currentSign.decided) tm.f("IBAT") else null, tm.f("SPD"), now, s.chargeTarget.toFloat())
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
                        synchronized(Bike.trip) { Bike.trip.update(tm.f("ODO"), tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), now) }
                        val chargingNow = synchronized(Bike.battery) { Bike.battery.charging }
                        synchronized(Bike.range) { Bike.energy.update(tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), tm.f("ODO"), chargingNow, now) }
                        val odo = tm.f("ODO"); val soc = tm.f("SOC")
                        if (odo != null && soc != null) {
                            val learned = synchronized(Bike.range) {
                                Bike.range.priorKmPerPercent = Bike.priorKmPerPercent(s)
                                Bike.range.update(odo, soc, now, chargingNow)
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
                        // Text goes out as soon as it changes (blank first, as the MID doesn't erase) and only counts as
                        // shown if both writes went out - otherwise it is tried again on the next pass.
                        if (line == null && own.size > 1) {
                            if (now - lastNav >= 15_000) {                                       // own long text: a part every 15 s
                                navIdx %= own.size
                                val part = own[navIdx]
                                if (sendText(part)) { lastNavText = part; lastNav = now; navIdx++ }
                            }
                        } else if ((line ?: "") != lastNavText) {
                            if (line == null) { if (send(Mid.nav(""))) { lastNavText = ""; lastNav = now } }
                            else if (sendText(line)) { lastNavText = line; lastNav = now }
                        }
                    }

                    // Pictures: only if switched on, when what they show changes (or once a minute), at most one every
                    // 10 s, never bigger than the MID can take; a text change never forces one. After each picture the
                    // current text is drawn again (the picture wiped that area).
                    val frameMs = maxOf(s.frameSeconds * 1000L, Traffic.PICTURE_MIN_INTERVAL_MS)
                    if (talk && picturesOn && now - lastFrameSent >= frameMs && now - lastFrame >= frameMs) {
                        lastFrame = now
                        val b64 = try { picturePayload(s, now) } catch (e: Exception) { Bike.log("Picture failed: ${e.message}"); null }
                        if (b64 != null && b64.length > Traffic.PICTURE_MAX_CHARS) {
                            Bike.lastFrameInfo = L.t("Picture too big for the MID - not sent")
                        } else if (b64 != null) {
                            val t0 = System.currentTimeMillis()
                            if (send(Mid.maps(b64))) {
                                lastFrameSent = now
                                if (lastNavText.isNotEmpty() && !send(Mid.nav(lastNavText))) lastNavText = "\u0000"   // retry next pass
                                Bike.lastFrameInfo = (if (L.indo) "Gambar terkirim: " else "Picture sent: ") + "${b64.length} " + (if (L.indo) "karakter dalam " else "chars in ") + "${System.currentTimeMillis() - t0} ms"
                            } else lastFrameKey = ""                                            // not sent: try again later
                        } else if (s.screenMode == "picture" && !java.io.File(filesDir, "picture.b64").exists()) {
                            Bike.lastFrameInfo = L.t("No picture chosen")
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
                    if (!mine()) break                         // an old worker: leave the state to the new one
                    // The Bluetooth link failed: drop it; the pause before the next try follows at the top.
                    Bike.log("Connection error: ${e.message}")
                    Bike.connected = false; Bike.telemetry.clearLive()
                    link?.close(); link = null
                } catch (e: Exception) {
                    // A bug elsewhere (drawing...): keep the link, note it, carry on.
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
                if (!Bike.status.startsWith("Bike off") && !Bike.status.startsWith("No answer from the MID for")) setStatus("Stopped")
                Bike.running = false                     // also after an unexpected error: never "running" without a worker
                Bike.workerAlive = false
            } else {
                // An old worker: close only its own link, never the new worker's.
                ownLink?.close()
                if (link === ownLink) link = null
            }
        }
    }

    private var lastGear: String? = null
    @Volatile private var connectedAt = 0L
    @Volatile private var pairingAllowed = false   // see ClassicLink.allowPairing
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
