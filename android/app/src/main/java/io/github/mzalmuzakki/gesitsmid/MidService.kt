package io.github.mzalmuzakki.gesitsmid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Settings (own file "v23"; the bike and the learned range are taken over from older versions once). */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("v23", Context.MODE_PRIVATE)
    init {
        if (!p.getBoolean("migrated", false)) {
            val old = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            p.edit().putString("address", old.getString("address", null)).putString("name", old.getString("deviceName", null))
                .putString("method", old.getString("classicMethod", null)).putString("range", old.getString("rangeState", null))
                .putBoolean("migrated", true).apply()
        }
    }
    private fun s(k: String, d: String?) = p.getString(k, d)
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun i(k: String, d: Int) = p.getInt(k, d)
    var address: String? get() = s("address", null); set(v) = p.edit().putString("address", v).apply()
    var name: String? get() = s("name", null); set(v) = p.edit().putString("name", v).apply()
    /** How the link last connected: insecure / secure / ch1 (tried first). */
    var method: String? get() = s("method", null); set(v) = p.edit().putString("method", v).apply()
    var rangeState: String? get() = s("range", null); set(v) = p.edit().putString("range", v).apply()
    /** Which values are asked: all / range (SPD, ODO, SOC) / none. */
    var questions: String get() = s("questions", "all")!!; set(v) = p.edit().putString("questions", v).apply()
    /** Pause between two questions (ms). */
    var gapMs: Int get() = i("gapMs", 500); set(v) = p.edit().putInt("gapMs", v).apply()
    var rangePicture: Boolean get() = b("rangePicture", true); set(v) = p.edit().putBoolean("rangePicture", v).apply()
    var pictureEverySec: Int get() = i("pictureSec", 60); set(v) = p.edit().putInt("pictureSec", v).apply()
    var rangeText: Boolean get() = b("rangeText", false); set(v) = p.edit().putBoolean("rangeText", v).apply()
    var clock: Boolean get() = b("clock", true); set(v) = p.edit().putBoolean("clock", v).apply()
    /** No answer for this long while connected: close the link and connect again (0 = never). */
    var silenceSec: Int get() = i("silenceSec", 15); set(v) = p.edit().putInt("silenceSec", v).apply()
    /** Try to accept the MID's pairing request automatically. */
    var autoPair: Boolean get() = b("autoPair", true); set(v) = p.edit().putBoolean("autoPair", v).apply()
    var pairPin: String get() = s("pairPin", "1234")!!; set(v) = p.edit().putString("pairPin", v).apply()
    /** Stop trying after this many minutes without a connection (0 = never). */
    var giveUpMin: Int get() = i("giveUpMin", 30); set(v) = p.edit().putInt("giveUpMin", v).apply()
}

/** Shared state between the service and the screen. */
object Bike {
    @Volatile var running = false
    @Volatile var connected = false
    @Volatile var status = "Not connected"
    @Volatile var method: String? = null
    /** Latest raw value per key and when it came (ms). */
    val raw = HashMap<String, Pair<String, Long>>()
    @Volatile var soc: Float? = null
    @Volatile var odo: Float? = null
    @Volatile var spd: Float? = null
    @Volatile var rangeKm: Float? = null
    @Volatile var lastRxAt = 0L
    @Volatile var tx = 0L
    @Volatile var rx = 0L
    @Volatile var links = 0
    val range = RangeEstimator(priorKmPerPercent = 1.25f, windowPercent = 40f, priorWeightPercent = 5f, fadePercent = 10f)
    /** One-off commands from the console. */
    val outbox = java.util.concurrent.LinkedBlockingQueue<String>()

    fun value(k: String): String? = synchronized(raw) { raw[k]?.first }
    fun age(k: String): Long? = synchronized(raw) { raw[k]?.second }?.let { System.currentTimeMillis() - it }
}

class MidService : Service() {
    companion object {
        const val CHANNEL = "mid"
        const val NOTIF = 1
        const val ACTION_STOP = "stop"
        fun start(ctx: Context) {
            val i = Intent(ctx, MidService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, MidService::class.java).setAction(ACTION_STOP)) }

        fun bondName(s: Int) = when (s) {
            BluetoothDevice.BOND_NONE -> "none(10)"; BluetoothDevice.BOND_BONDING -> "bonding(11)"
            BluetoothDevice.BOND_BONDED -> "bonded(12)"; else -> s.toString()
        }
    }

    private var worker: Thread? = null
    @Volatile private var sock: BluetoothSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastPairPrompt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        registerBluetoothEvents()
    }

    override fun onDestroy() {
        try { unregisterReceiver(btEvents) } catch (_: Exception) {}
        Bike.running = false
        closeLink("service destroyed")
        wake?.let { if (it.isHeld) it.release() }
        Log.ev("Service stopped")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.ev("Disconnect tapped")
            Bike.running = false
            worker?.interrupt()
            closeLink("disconnect")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) Log.ev("Service restarted by Android")
        goForeground()
        if (worker?.isAlive != true) {
            Bike.running = true
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gesitsmid:link").also { it.acquire() }
            worker = Thread({ run() }, "mid").also { it.start() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bike connection", NotificationManager.IMPORTANCE_LOW))
        val n = notification("Starting")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF, n)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MidService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("Gesits MID").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Disconnect", stop).build()).build()
    }

    private var lastNotif = ""
    private fun refreshNotif() {
        val t = (Bike.rangeKm?.let { "Range ${it.toInt()} km" } ?: "Range --") + (Bike.soc?.let { " · ${it.toInt()}%" } ?: "") +
            " · " + (if (Bike.connected) "connected" else Bike.status)
        if (t != lastNotif) { lastNotif = t; getSystemService(NotificationManager::class.java).notify(NOTIF, notification(t)) }
    }

    private fun setStatus(s: String) { Bike.status = s; Log.ev(s) }

    // ------------------------------------------------------------------ Bluetooth events (all logged)

    private val btEvents = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, i: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val who = dev?.let { d -> (try { d.name } catch (_: Exception) { null } ?: "?") + " " + d.address } ?: ""
            val mine = dev != null && dev.address == Settings(ctx).address
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> Log.ev("Phone Bluetooth: " + when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> "on"; BluetoothAdapter.STATE_OFF -> "off"
                    BluetoothAdapter.STATE_TURNING_ON -> "turning on"; BluetoothAdapter.STATE_TURNING_OFF -> "turning off"; else -> "?" })
                BluetoothDevice.ACTION_ACL_CONNECTED -> Log.ev("ACL connected: $who")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> Log.ev("ACL disconnected: $who")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> Log.ev("Bond state $who: " +
                    bondName(i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)) + " -> " + bondName(i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) +
                    (if (i.hasExtra("android.bluetooth.device.extra.REASON")) " reason " + i.getIntExtra("android.bluetooth.device.extra.REASON", -1) else ""))
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val key = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    Log.ev("Pairing request from $who: variant $variant" + (if (key >= 0) ", key $key" else "") +
                        " (0 = PIN, 2 = confirm passkey, 3 = consent)")
                    val s = Settings(ctx)
                    if (mine && s.autoPair && dev != null) {
                        try {
                            when (variant) {
                                BluetoothDevice.PAIRING_VARIANT_PIN -> Log.ev("Auto-pair: setPin(${s.pairPin}) = " + dev.setPin(s.pairPin.toByteArray()))
                                else -> Log.ev("Auto-pair: setPairingConfirmation(true) = " + dev.setPairingConfirmation(true))
                            }
                        } catch (e: Exception) { Log.ev("Auto-pair failed: ${e.javaClass.simpleName}: ${e.message} - tap Pair on the phone's request") }
                    }
                }
            }
        }
    }

    private fun registerBluetoothEvents() {
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btEvents, f, RECEIVER_EXPORTED) else registerReceiver(btEvents, f)
    }

    // ------------------------------------------------------------------ link

    @SuppressLint("MissingPermission")
    private fun connect(s: Settings): BluetoothSocket? {
        val addr = s.address ?: run { setStatus("Choose the bike first"); return null }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: run { setStatus("No Bluetooth"); return null }
        if (!adapter.isEnabled) { setStatus("Phone Bluetooth is off"); return null }
        val dev = adapter.getRemoteDevice(addr)
        val listed = try { adapter.bondedDevices.any { it.address == addr } } catch (_: Exception) { false }
        val bonded = dev.bondState == BluetoothDevice.BOND_BONDED || listed
        setStatus("Connecting to ${s.name ?: addr} (bond ${bondName(dev.bondState)}, in paired list: $listed)")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        val methods = (listOfNotNull(s.method) + listOf("insecure", "secure", "ch1")).distinct()
        for (m in methods) {
            if (!Bike.running) return null
            if (m != "insecure" && !bonded) {
                // not paired: this way makes Android show a pairing request - at most once a minute
                if (System.currentTimeMillis() - lastPairPrompt < 60_000) { Log.ev("  $m skipped: not paired, pairing request shown less than 1 min ago"); continue }
                lastPairPrompt = System.currentTimeMillis()
                Log.ev("  $m: not paired - Android will ask to pair")
            }
            val t0 = System.currentTimeMillis()
            var so: BluetoothSocket? = null
            try {
                val made: BluetoothSocket = when (m) {
                    "insecure" -> dev.createInsecureRfcommSocketToServiceRecord(Mid.SPP)
                    "secure" -> dev.createRfcommSocketToServiceRecord(Mid.SPP)
                    else -> dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket
                }
                so = made
                sock = made
                made.connect()
                Log.ev("  $m: connected in ${System.currentTimeMillis() - t0} ms")
                s.method = m; Bike.method = m
                return made
            } catch (e: Exception) {
                Log.ev("  $m: failed after ${System.currentTimeMillis() - t0} ms - ${e.javaClass.simpleName}: ${e.message}")
                try { so?.close() } catch (_: Exception) {}
                sock = null
            }
        }
        return null
    }

    private fun closeLink(why: String) {
        val so = sock ?: return
        sock = null
        if (Bike.connected || so.isConnected) Log.ev("Closing the link: $why")
        Bike.connected = false
        try { so.close() } catch (_: Exception) {}
    }

    /** Reads lines from the MID until the link breaks. Every line is logged. */
    private fun reader(so: BluetoothSocket, linkNo: Int) = Thread({
        val buf = StringBuilder()
        val bytes = ByteArray(1024)
        try {
            val inp = so.inputStream
            while (true) {
                val n = inp.read(bytes)
                if (n < 0) throw IOException("end of stream")
                for (k in 0 until n) {
                    val c = (bytes[k].toInt() and 0xFF).toChar()
                    if (c == '\r' || c == '\n') {
                        if (buf.isNotEmpty()) { onLine(buf.toString()); buf.clear() }
                    } else buf.append(c)
                }
            }
        } catch (e: Exception) {
            if (buf.isNotEmpty()) onLine(buf.toString())
            if (sock === so) {
                Log.ev("Link $linkNo broke while reading: ${e.javaClass.simpleName}: ${e.message}")
                closeLink("read failed")
            }
        }
    }, "mid-read").apply { isDaemon = true; start() }

    private var pendingSoc: Float? = null
    private var pendingOdo: Float? = null

    private fun onLine(line: String) {
        Bike.rx++
        Bike.lastRxAt = System.currentTimeMillis()
        Log.rx(line)
        val (k, v) = Mid.parse(line) ?: return
        synchronized(Bike.raw) { Bike.raw[k] = v to System.currentTimeMillis() }
        val f = v.trim().toFloatOrNull() ?: return
        when (k) {
            "SPD" -> Bike.spd = f
            "SOC" -> {
                val cur = Bike.soc
                // a jump of more than 2 % counts only when a second reading agrees (garbled replies happen)
                if (f < 0f || f > 100f) Log.ev("SOC $v out of range - ignored")
                else if (cur == null || abs(f - cur) <= 2f || f == pendingSoc) { Bike.soc = f; pendingSoc = null }
                else { Log.ev("SOC $v vs ${cur.toInt()} before - waiting for a second reading"); pendingSoc = f }
            }
            "ODO" -> {
                val cur = Bike.odo
                if (cur == null || (f >= cur - 0.05f && f <= cur + 1f) || f == pendingOdo) { Bike.odo = f; pendingOdo = null }
                else { Log.ev("ODO $v vs $cur before - waiting for a second reading"); pendingOdo = f }
            }
        }
    }

    private fun send(cmd: String): Boolean {
        val so = sock ?: return false
        try {
            so.outputStream.write(cmd.toByteArray(Charsets.US_ASCII))
            so.outputStream.flush()
            Bike.tx++
            Log.tx(if (cmd.startsWith("AT+MAPS")) "AT+MAPS=<picture, ${cmd.length} chars>" else cmd)
            return true
        } catch (e: Exception) {
            Log.ev("Write failed (${cmd.take(16).trim()}): ${e.javaClass.simpleName}: ${e.message}")
            closeLink("write failed")
            return false
        }
    }

    // ------------------------------------------------------------------ main loop

    private fun run() {
        val s = Settings(this)
        Log.ev("=== Gesits MID ${packageManager.getPackageInfo(packageName, 0).versionName} started - Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.ev("Settings: questions=${s.questions}, gap=${s.gapMs} ms, picture=${s.rangePicture} every ${s.pictureEverySec} s, text=${s.rangeText}, clock=${s.clock}, silence=${s.silenceSec} s, autoPair=${s.autoPair}")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        Log.ev("Battery optimisation for this app: " + (if (pm.isIgnoringBatteryOptimizations(packageName)) "off (good)" else "ON - the phone may stop the app in the background"))
        synchronized(Bike.range) { Bike.range.load(s.rangeState) }
        Log.ev("Range: " + synchronized(Bike.range) { Bike.range.describe() })

        var backoff = 3000L
        var offSince = System.currentTimeMillis()
        var qi = 0
        var linkAt = 0L
        var lastMinute = -1
        var lastPicAt = 0L; var lastPicKm = -1
        var lastTextKm = -1
        var lastSecond = 0L; var lastSave = 0L; var lastStatus = 0L
        while (Bike.running) {
            try {
                val now = System.currentTimeMillis()
                if (sock == null) {
                    if (Bike.connected) Bike.connected = false
                    refreshNotif()
                    if (s.giveUpMin > 0 && now - offSince > s.giveUpMin * 60_000L) {
                        setStatus("No connection for ${s.giveUpMin} min - stopped (tap Connect to try again)")
                        Bike.running = false; break
                    }
                    val so = connect(s)
                    if (so == null) {
                        if (Bike.running) { Log.ev("Next try in ${backoff / 1000} s"); Thread.sleep(backoff) }
                        backoff = minOf(backoff + 3000L, 15_000L)
                        continue
                    }
                    Bike.links++
                    backoff = 3000L
                    linkAt = System.currentTimeMillis()
                    Bike.lastRxAt = 0L
                    Bike.connected = true
                    setStatus("Connected (link ${Bike.links}, ${Bike.method})")
                    reader(so, Bike.links)
                    lastMinute = -1; lastPicAt = 0L; lastPicKm = -1; lastTextKm = -1
                    send("AT+VER?\r\n")
                    Thread.sleep(s.gapMs.toLong())
                    continue
                }
                offSince = now

                // No answer for a while: close and connect again (logged, to see whether that brings the MID back).
                val heard = maxOf(Bike.lastRxAt, linkAt)
                if (s.silenceSec > 0 && now - heard > s.silenceSec * 1000L) {
                    Log.ev("No answer for ${(now - heard) / 1000} s (last answer ${if (Bike.lastRxAt == 0L) "never on this link" else "${(now - Bike.lastRxAt) / 1000} s ago"}) - reconnecting")
                    closeLink("MID silent")
                    continue
                }

                // Console first.
                Bike.outbox.poll()?.let { send(it); Thread.sleep(s.gapMs.toLong()) }

                // Clock when the minute changes.
                if (s.clock) {
                    val c = Calendar.getInstance()
                    val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                    if (minute != lastMinute) { if (send(Mid.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))) lastMinute = minute; Thread.sleep(s.gapMs.toLong()) }
                }

                // Range on the MID.
                val km = Bike.rangeKm?.let { Math.round(it) }
                if (km != null && s.rangePicture && ((km != lastPicKm && now - lastPicAt >= s.pictureEverySec * 1000L) || now - lastPicAt >= 300_000L)) {
                    val b64 = Mid.toBase64(Mid.rangePicture(Bike.rangeKm, "RANGE"), 6000)
                    if (b64 == null) Log.ev("Range picture too big - not sent")
                    else if (send(Mid.maps(b64))) { lastPicAt = now; lastPicKm = km }
                    Thread.sleep(maxOf(s.gapMs.toLong(), 1000L))
                }
                if (km != null && s.rangeText && km != lastTextKm) {
                    if (send(Mid.nav("RANGE $km km"))) lastTextKm = km
                    Thread.sleep(s.gapMs.toLong())
                }

                // One question.
                val keys = when (s.questions) { "range" -> Mid.RANGE_KEYS; "none" -> emptyList(); else -> Mid.ALL_KEYS }
                if (keys.isNotEmpty()) {
                    // speed every other question, the rest in turn
                    val k = if (qi % 2 == 0) "SPD" else keys.filter { it != "SPD" }.let { o -> if (o.isEmpty()) "SPD" else o[(qi / 2) % o.size] }
                    qi++
                    send(Mid.query(k))
                }

                // Once a second: range; every minute: save and a status line in the log.
                if (now - lastSecond >= 1000) {
                    lastSecond = now
                    val odo = Bike.odo; val soc = Bike.soc
                    if (odo != null && soc != null) {
                        val learned = synchronized(Bike.range) { Bike.range.update(odo, soc, now) }
                        if (learned) { Log.ev("Range learned: " + synchronized(Bike.range) { Bike.range.describe() }); s.rangeState = synchronized(Bike.range) { Bike.range.save() }; lastSave = now }
                        Bike.rangeKm = synchronized(Bike.range) { Bike.range.rangeKm(soc) }
                    }
                    if (now - lastSave >= 60_000) { s.rangeState = synchronized(Bike.range) { Bike.range.save() }; lastSave = now }
                    if (now - lastStatus >= 60_000) {
                        lastStatus = now
                        Log.ev(String.format(Locale.US, "STATUS link %d (%s) up %d s, last answer %s, TX %d RX %d, SPD %s SOC %s ODO %s, range %s km (%.2f km/%%)",
                            Bike.links, Bike.method, (now - linkAt) / 1000, if (Bike.lastRxAt == 0L) "never" else "${(now - Bike.lastRxAt) / 1000} s ago",
                            Bike.tx, Bike.rx, Bike.value("SPD"), Bike.value("SOC"), Bike.value("ODO"),
                            Bike.rangeKm?.let { String.format(Locale.US, "%.0f", it) } ?: "--", synchronized(Bike.range) { Bike.range.kmPerPercent }))
                    }
                    refreshNotif()
                }
                Thread.sleep(s.gapMs.toLong().coerceAtLeast(50L))
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.ev("Error: ${e.javaClass.simpleName}: ${e.message}")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
        s.rangeState = synchronized(Bike.range) { Bike.range.save() }
        closeLink("stopped")
        Bike.running = false
        Bike.status = "Not connected"
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
