package io.github.mzalmuzakki.gesitsmid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import java.io.File

/** One per-second snapshot for the Diagnosis charts. */
data class Sample(val t: Long, val spd: Float?, val powerW: Float?, val trt: Float?, val tbat: Float?, val tmot: Float?, val tinv: Float?)

/** Finished rides, kept in a small text file in the app's private storage (one line per ride). */
object RideStore {
    private const val FILE = "rides.txt"
    private var cache: MutableList<Ride>? = null

    @Synchronized fun all(ctx: Context): List<Ride> {
        Demo.rides?.let { return it }
        cache?.let { return it.toList() }
        val f = File(ctx.filesDir, FILE)
        val list = if (f.exists()) f.readLines().mapNotNull { Ride.fromLine(it) }.toMutableList() else mutableListOf()
        cache = list
        return list.toList()
    }

    @Synchronized fun add(ctx: Context, r: Ride) {
        if (Demo.rides != null) return
        all(ctx)
        cache!!.add(r)
        File(ctx.filesDir, FILE).appendText(r.toLine() + "\n")
    }

    @Synchronized fun clear(ctx: Context) {
        cache = mutableListOf(); File(ctx.filesDir, FILE).delete()
    }

    fun csv(ctx: Context): String = buildString {
        appendLine(Ride.CSV_HEADER)
        all(ctx).forEach { appendLine(it.toCsv()) }
    }
}

/** Phone notifications for alarms (low battery, hot battery/motor, low SOH, MID errors). */
object Alerts {
    private const val CHANNEL = "alerts"
    private var id = 100

    fun notify(ctx: Context, msg: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bike alarms", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, DiagnosisActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Gesits MID").setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(id++, n) } catch (_: SecurityException) {}
        Bike.log("ALARM: $msg")
    }
}

/** Remembers where the bike was parked, using the phone's own GPS (no Google services, no internet). */
object Parking {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun capture(ctx: Context, s: Settings) {
        if (!s.rememberParking || !hasPermission(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val save = { l: Location? ->
            if (l != null) {
                s.parkLat = l.latitude.toFloat(); s.parkLon = l.longitude.toFloat(); s.parkTime = System.currentTimeMillis()
                Bike.log("Parking location saved")   // coordinates are never written to the log
            }
        }
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
                           else LocationManager.NETWORK_PROVIDER
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { l -> save(l ?: lm.getLastKnownLocation(provider)) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { l -> save(l) }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Bike.log("Parking location failed: ${e.message}")
        }
    }
}

/** Fake live data and rides for screenshots. Everything stays in memory: nothing is written to storage. */
object Demo {
    @Volatile var rides: List<Ride>? = null

    fun load() {
        val t = Bike.telemetry
        listOf("SPD" to "38", "SOC" to "78", "SOH" to "96", "ODO" to "3812.4", "MODE" to "1", "PARK" to "0",
            "VBAT" to "79.6", "IBAT" to "21.5", "TBAT" to "36", "TMOT" to "54", "TINV" to "47", "TRT" to "42",
            "ERR" to "0", "VER" to "demo").forEach { (k, v) -> t.put(k, v) }
        Bike.connected = true
        Bike.status = "Demo data (no bike connected)"
        val now = System.currentTimeMillis()
        synchronized(Bike.samples) {
            Bike.samples.clear()
            for (i in 0 until 600) {
                val x = i / 30.0
                val spd = (35 + 15 * Math.sin(x)).toFloat().coerceAtLeast(0f)
                Bike.samples.addLast(Sample(now - (600 - i) * 1000L, spd, (spd * 45 + 300 * Math.cos(x * 1.7)).toFloat(),
                    (spd * 1.1f), 34f + i / 120f, 48f + i / 60f, 43f + i / 100f))
            }
        }
        val day = 24 * 3600_000L
        rides = listOf(22 to 40.2f, 15 to 19.9f, 10 to 26.3f, 8 to 14.0f, 5 to 31.5f, 4 to 9.8f, 2 to 22.7f, 1 to 6.1f, 1 to 18.2f, 0 to 12.4f)
            .mapIndexed { i, (d, km) ->
                val start = now - d * day - 3 * 3600_000L
                Ride(start, start + (km * 110_000).toLong(), km, km * (27f + (i % 4) * 2f), km * 2.1f,
                    90f, 90f - km * 1.4f, 58f + km / 3, (km * 100_000).toLong())
            }
        synchronized(Bike.trip) { Bike.trip.update(3800f, 86f, null, null, now); Bike.trip.update(3812.4f, 78f, null, null, now) }
    }
}
