package io.github.mzalmuzakki.gesitsmid

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One finished ride. Times in epoch ms; energy in Wh (net, regen already subtracted). */
data class Ride(
    val startMs: Long, val endMs: Long, val km: Float, val energyWh: Float, val regenWh: Float,
    val socStart: Float, val socEnd: Float, val maxSpeed: Float, val movingMs: Long,
) {
    val whPerKm: Float? get() = if (km >= 0.5f && energyWh > 0f) energyWh / km else null
    val avgSpeed: Float? get() = if (movingMs > 60_000) km / (movingMs / 3_600_000f) else null

    fun toLine(): String = String.format(Locale.US, "%d;%d;%.3f;%.2f;%.2f;%.1f;%.1f;%.1f;%d",
        startMs, endMs, km, energyWh, regenWh, socStart, socEnd, maxSpeed, movingMs)

    companion object {
        fun fromLine(s: String): Ride? = try {
            val p = s.trim().split(";")
            Ride(p[0].toLong(), p[1].toLong(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(),
                p[5].toFloat(), p[6].toFloat(), p[7].toFloat(), p[8].toLong())
        } catch (_: Exception) { null }

        const val CSV_HEADER = "start,end,km,energy_wh,regen_wh,wh_per_km,soc_start,soc_end,max_speed_kmh,avg_speed_kmh,moving_min"
    }

    fun toCsv(tz: TimeZone = TimeZone.getDefault()): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
        return String.format(Locale.US, "%s,%s,%.2f,%.0f,%.0f,%s,%.0f,%.0f,%.0f,%s,%.0f",
            f.format(startMs), f.format(endMs), km, energyWh, regenWh, whPerKm?.let { "%.1f".format(Locale.US, it) } ?: "",
            socStart, socEnd, maxSpeed, avgSpeed?.let { "%.1f".format(Locale.US, it) } ?: "", movingMs / 60_000f)
    }
}

/**
 * Splits the live data into rides. A ride starts when speed goes above [startSpeed] and ends
 * after [idleEndMs] without movement (also while disconnected - e.g. ignition off), or on [finish].
 * Distance comes from the odometer; energy from VBAT x IBAT (gaps > 10 s are not integrated).
 */
class RideRecorder(
    private val startSpeed: Float = 3f,
    private val idleEndMs: Long = 5 * 60_000L,
    private val minKm: Float = 0.2f,
) {
    var active = false; private set
    private var startMs = 0L
    private var startOdo = Float.NaN
    private var lastOdo = Float.NaN
    private var socStart = Float.NaN
    private var socEnd = Float.NaN
    private var energy = 0f
    private var regen = 0f
    private var maxSpd = 0f
    private var moving = 0L
    private var lastMoveMs = 0L
    private var lastSampleMs = -1L
    private var speedKm = 0f   // distance from speed, used only if there is no odometer

    /** Feed one sample; returns a ride when one just finished. */
    fun sample(nowMs: Long, spd: Float?, odo: Float?, soc: Float?, vbat: Float?, ibat: Float?): Ride? {
        val ended = tick(nowMs)
        val s = spd ?: 0f
        if (!active && s > startSpeed) {
            active = true; startMs = nowMs; startOdo = odo ?: Float.NaN; lastOdo = startOdo
            socStart = soc ?: Float.NaN; socEnd = socStart
            energy = 0f; regen = 0f; maxSpd = 0f; moving = 0L; speedKm = 0f; lastMoveMs = nowMs; lastSampleMs = -1L
        }
        if (active) {
            val dt = if (lastSampleMs >= 0) nowMs - lastSampleMs else 0L
            val ok = dt in 1..10_000
            if (odo != null) { if (startOdo.isNaN()) startOdo = odo; lastOdo = odo }
            if (soc != null) { if (socStart.isNaN()) socStart = soc; socEnd = soc }
            if (s > maxSpd) maxSpd = s
            if (s > 1f) { lastMoveMs = nowMs; if (ok) moving += dt }
            if (ok) {
                speedKm += s * dt / 3_600_000f
                if (vbat != null && ibat != null) {
                    val wh = vbat * ibat * dt / 3_600_000f
                    energy += wh
                    if (wh < 0) regen -= wh
                }
            }
            lastSampleMs = nowMs
        }
        return ended
    }

    /** Call regularly (also while disconnected): ends the ride after the idle time. */
    fun tick(nowMs: Long): Ride? = if (active && nowMs - lastMoveMs > idleEndMs) close(lastMoveMs) else null

    /** End now (e.g. the user stopped the app). */
    fun finish(nowMs: Long): Ride? = if (active) close(minOf(nowMs, lastMoveMs.coerceAtLeast(startMs))) else null

    private fun close(endMs: Long): Ride? {
        active = false
        val km = if (!startOdo.isNaN() && !lastOdo.isNaN() && lastOdo >= startOdo) lastOdo - startOdo else speedKm
        if (km < minKm) return null
        return Ride(startMs, endMs, km, energy, regen, socStart.orZero(), socEnd.orZero(), maxSpd, moving)
    }

    private fun Float.orZero() = if (isNaN()) 0f else this
}

/** Totals, periods, records and savings over all rides. Pure Kotlin. */
class RideStats(private val rides: List<Ride>, private val tz: TimeZone = TimeZone.getDefault()) {
    val count get() = rides.size
    val totalKm get() = rides.sumOf { it.km.toDouble() }.toFloat()
    val totalWh get() = rides.sumOf { it.energyWh.toDouble() }.toFloat()
    val totalRegenWh get() = rides.sumOf { it.regenWh.toDouble() }.toFloat()
    val avgWhPerKm: Float? get() {
        val r = rides.filter { it.whPerKm != null }
        val km = r.sumOf { it.km.toDouble() }
        return if (km >= 1.0) (r.sumOf { it.energyWh.toDouble() } / km).toFloat() else null
    }
    val longest get() = rides.maxByOrNull { it.km }
    val mostEfficient get() = rides.filter { it.km >= 2f }.mapNotNull { r -> r.whPerKm?.let { r to it } }.minByOrNull { it.second }?.first
    val topSpeed get() = rides.maxOfOrNull { it.maxSpeed }

    private fun cal(ms: Long) = Calendar.getInstance(tz).apply { timeInMillis = ms; firstDayOfWeek = Calendar.MONDAY }

    private fun startOfDay(ms: Long): Long = cal(ms).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfWeek(ms: Long): Long = cal(startOfDay(ms)).apply {
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    private fun kmBetween(from: Long, to: Long) = rides.filter { it.startMs in from until to }.sumOf { it.km.toDouble() }.toFloat()

    /** km per day for the last [days] days, oldest first (last = today). */
    fun dailyKm(nowMs: Long, days: Int = 7): List<Float> {
        val today = startOfDay(nowMs)
        return (days - 1 downTo 0).map { back ->
            val from = cal(today).apply { add(Calendar.DAY_OF_MONTH, -back) }.timeInMillis
            val to = cal(from).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            kmBetween(from, to)
        }
    }

    /** km per week (Monday-Sunday) for the last [weeks] weeks, oldest first (last = this week). */
    fun weeklyKm(nowMs: Long, weeks: Int = 8): List<Float> {
        val thisWeek = startOfWeek(nowMs)
        return (weeks - 1 downTo 0).map { back ->
            val from = cal(thisWeek).apply { add(Calendar.WEEK_OF_YEAR, -back) }.timeInMillis
            val to = cal(from).apply { add(Calendar.WEEK_OF_YEAR, 1) }.timeInMillis
            kmBetween(from, to)
        }
    }

    data class Savings(val litres: Float, val petrolRp: Float, val electricityRp: Float, val netRp: Float, val co2Kg: Float)

    /** Compared with a petrol bike doing [kmPerLitre] at [rpPerLitre]; electricity at [rpPerKwh]. */
    fun savings(kmPerLitre: Float, rpPerLitre: Float, rpPerKwh: Float, co2PerLitre: Float = 2.31f): Savings {
        val litres = if (kmPerLitre > 0) totalKm / kmPerLitre else 0f
        val petrol = litres * rpPerLitre
        val elec = totalWh.coerceAtLeast(0f) / 1000f * rpPerKwh
        return Savings(litres, petrol, elec, petrol - elec, litres * co2PerLitre)
    }
}

/**
 * Turns live values into alarm messages, once per crossing (with hysteresis so a value
 * hovering at the limit does not repeat the alarm).
 */
class AlarmMonitor(
    var socLow: Float = 20f, var tbatHigh: Float = 45f, var tmotHigh: Float = 85f, var sohLow: Float = 75f,
) {
    private val active = HashSet<String>()

    private fun check(key: String, firing: Boolean, cleared: Boolean, msg: () -> String, out: MutableList<String>) {
        if (firing && active.add(key)) out += msg()
        else if (cleared) active.remove(key)
    }

    fun evaluate(soc: Float?, tbat: Float?, tmot: Float?, soh: Float?, errors: List<String>): List<String> {
        val out = mutableListOf<String>()
        soc?.let { check("soc", it < socLow, it >= socLow + 5, { "Battery low: ${it.toInt()}%" }, out) }
        tbat?.let { check("tbat", it > tbatHigh, it <= tbatHigh - 3, { "Battery hot: ${it.toInt()}°C" }, out) }
        tmot?.let { check("tmot", it > tmotHigh, it <= tmotHigh - 5, { "Motor hot: ${it.toInt()}°C" }, out) }
        soh?.let { check("soh", it < sohLow, it >= sohLow + 2, { "Battery health low: ${it.toInt()}%" }, out) }
        check("err", errors.isNotEmpty(), errors.isEmpty(), { "MID error: " + errors.joinToString() }, out)
        return out
    }
}
