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
import java.util.Calendar
import java.util.concurrent.LinkedBlockingQueue

/** Shared state between the service and the screen. */
object Bike {
    val telemetry = Telemetry()
    @Volatile var status = "Not connected"
    @Volatile var connected = false
    @Volatile var lastFrame: Bitmap? = null
    @Volatile var lastFrameInfo = ""
    @Volatile var running = false
    /** One-off commands from the UI (text, picture...), sent by the service thread. */
    val outbox = LinkedBlockingQueue<String>()
    val log = ArrayDeque<String>()
    /** Adaptive range learner; guard access with synchronized(range). */
    val range = RangeEstimator()
    @Volatile var rangeLoaded = false

    fun loadRange(s: Settings) = synchronized(range) {
        if (!rangeLoaded) { range.load(s.rangeState); rangeLoaded = true }
        range.priorKmPerPercent = s.kmPerPercent
    }
    fun rangeKm(): Float? = Bike.telemetry.f("SOC")?.let { soc -> synchronized(range) { range.rangeKm(soc) } }

    /** Trip & efficiency; guard access with synchronized(trip). */
    val trip = TripTracker()
    @Volatile var tripLoaded = false
    fun loadTrip(s: Settings) = synchronized(trip) { if (!tripLoaded) { trip.load(s.tripState); tripLoaded = true } }

    fun log(msg: String) = synchronized(log) {
        log.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg)
        while (log.size > 300) log.removeFirst()
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
    var kmPerPercent: Float get() = p.getFloat("kmpp", 1.0f); set(v) = p.edit().putFloat("kmpp", v).apply()
    var tripState: String? get() = p.getString("tripState", null); set(v) = p.edit().putString("tripState", v).apply()
    /** "auto" (BIG while riding, MAIN/TRIP when stopped), "main", "trip", "big", "rotate" */
    var dashStyle: String get() = p.getString("dashStyle", "auto")!!; set(v) = p.edit().putString("dashStyle", v).apply()
    var rangeState: String? get() = p.getString("rangeState", null); set(v) = p.edit().putString("rangeState", v).apply()
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
        goForeground()
        holdWakeLock(true)
        if (worker?.isAlive != true) {
            Bike.running = true
            worker = Thread({ runLoop() }, "mid-worker").also { it.start() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bike connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Gesits MID")
            .setContentText("Connected to the bike's dashboard")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
    }

    private fun setStatus(s: String) { Bike.status = s; Bike.log(s) }

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
    }

    private fun picturePayload(s: Settings): String? = when (s.screenMode) {
        "dashboard" -> {
            frameNo++
            val moving = (Bike.telemetry.f("SPD") ?: 0f) > 3f
            val page = when (s.dashStyle) {
                "main" -> ScreenRenderer.Page.MAIN
                "trip" -> ScreenRenderer.Page.TRIP
                "big" -> ScreenRenderer.Page.BIG
                "rotate" -> ScreenRenderer.Page.values()[frameNo % 3]
                else -> if (moving) ScreenRenderer.Page.BIG
                        else if ((frameNo / 2) % 2 == 0) ScreenRenderer.Page.MAIN else ScreenRenderer.Page.TRIP
            }
            val bmp = synchronized(Bike.trip) { ScreenRenderer.render(Bike.telemetry, Bike.rangeKm(), s.margins, page, Bike.trip) }
            Bike.lastFrame = bmp
            ScreenRenderer.toMapsBase64(bmp)
        }
        "grid" -> ScreenRenderer.calibrationGrid().let { Bike.lastFrame = it; ScreenRenderer.toMapsBase64(it) }
        "picture" -> try { openFileInput("picture.b64").bufferedReader().readText() } catch (_: Exception) { null }
        else -> null
    }

    private fun runLoop() {
        val s = Settings(this)
        var keyIdx = 0
        var navIdx = 0
        var lastPoll = 0L; var lastFrame = 0L; var lastTrip = 0L; var lastNav = 0L; var lastTime = 0L
        var lastMinute = -1; var lastLearn = 0L; var lastSave = 0L
        Bike.loadRange(s)
        Bike.loadTrip(s)
        while (Bike.running) {
            try {
                if (link?.isOpen != true) {
                    link?.close()
                    link = openLink(s)
                    if (link == null) { Thread.sleep(2000); continue }
                    lastFrame = System.currentTimeMillis() - s.frameSeconds * 1000L + 1500   // first frame after some data
                    lastTime = 0L; lastMinute = -1   // set the MID clock right after connecting
                    Bike.telemetry.raw.clear()         // never pair old values with new ones (range learning)
                    send(Mid.VERSION_QUERY)            // MID firmware version (shown in the app)
                    send(Mid.ARROW_ON)                 // as GRID Mobile does on connect
                }
                val now = System.currentTimeMillis()

                while (true) {
                    val cmd = Bike.outbox.poll() ?: break
                    send(cmd)
                    if (!cmd.startsWith("AT+MAPS")) Bike.log("-> " + cmd.trim())
                }

                if (now - lastPoll >= 200) {
                    send(Mid.query(Mid.POLL_KEYS[keyIdx]))
                    keyIdx = (keyIdx + 1) % Mid.POLL_KEYS.size
                    lastPoll = now
                }
                // Clock: on connect, whenever the phone's minute changes, and every 30 s as a safety net.
                if (s.syncTime) {
                    val c = Calendar.getInstance()
                    val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                    if (minute != lastMinute || now - lastTime >= 30_000) {
                        send(Mid.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
                        lastMinute = minute; lastTime = now
                    }
                }
                // Adaptive range: learn from ODO vs SOC once per second, save when something was learned.
                if (now - lastLearn >= 1000) {
                    val tm = Bike.telemetry
                    synchronized(Bike.trip) { Bike.trip.update(tm.f("ODO"), tm.f("SOC"), tm.f("VBAT"), tm.f("IBAT"), now) }
                    val odo = Bike.telemetry.f("ODO"); val soc = Bike.telemetry.f("SOC")
                    if (odo != null && soc != null) {
                        val learned = synchronized(Bike.range) {
                            Bike.range.priorKmPerPercent = s.kmPerPercent
                            Bike.range.update(odo, soc, now)
                        }
                        // also save regularly, so a quick reconnect continues the same segment
                        if (learned || now - lastSave >= 10_000) {
                            s.rangeState = synchronized(Bike.range) { Bike.range.save() }
                            s.tripState = synchronized(Bike.trip) { Bike.trip.save() }
                            lastSave = now
                        }
                        if (learned) Bike.log("Range learned: " + synchronized(Bike.range) { Bike.range.describe() })
                    }
                    lastLearn = now
                }
                // Every second: the MID falls back to its own range figure if we go quiet.
                if (s.sendRange && now - lastTrip >= 1000) {
                    Bike.rangeKm()?.let { send(Mid.trip(it)) }
                    lastTrip = now
                }
                val parts = Mid.splitNav(s.navText).ifEmpty { if (s.navDate) listOf(Mid.dateText()) else emptyList() }
                if (parts.isNotEmpty() && now - lastNav >= 5000) {
                    navIdx %= parts.size
                    send(Mid.nav(parts[navIdx++]))
                    lastNav = now
                }
                if (s.screenMode != "off" && now - lastFrame >= s.frameSeconds * 1000L) {
                    val t0 = System.currentTimeMillis()
                    val b64 = picturePayload(s)
                    if (b64 != null) {
                        send(Mid.maps(b64))
                        if (s.sendRange) Bike.rangeKm()?.let { send(Mid.trip(it)) }   // right after a (slow) frame
                        Bike.lastFrameInfo = "Picture sent: ${b64.length} chars in ${System.currentTimeMillis() - t0} ms"
                    } else if (s.screenMode == "dashboard") {
                        Bike.lastFrameInfo = "Dashboard picture too large"
                    }
                    lastFrame = System.currentTimeMillis()
                }
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                setStatus("Error: ${e.message} - reconnecting")
                Bike.connected = false
                link?.close(); link = null
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
        link?.close(); link = null
        Bike.connected = false
        setStatus("Stopped")
    }

    override fun onDestroy() {
        holdWakeLock(false)
        Bike.running = false
        worker?.interrupt()
        link?.close()
        super.onDestroy()
    }
}
