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
        cache?.let { return it.toList() }
        val f = File(ctx.filesDir, FILE)
        val list = if (f.exists()) f.readLines().mapNotNull { Ride.fromLine(it) }.toMutableList() else mutableListOf()
        cache = list
        return list.toList()
    }

    @Synchronized fun add(ctx: Context, r: Ride) {
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
                Bike.log(String.format(java.util.Locale.US, "Parked at %.5f, %.5f", l.latitude, l.longitude))
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
