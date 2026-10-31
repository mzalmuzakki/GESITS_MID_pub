package io.github.mzalmuzakki.gesitsmid

import java.util.Locale

/**
 * Trip & efficiency: distance and battery use since the trip started, plus energy measured
 * by integrating VBAT x IBAT over time (regen counts back). Pure Kotlin, unit-tested.
 *
 * A trip starts at the first reading, on "Reset trip", and automatically after charging
 * (battery % higher than at trip start by >= [chargeResetPercent]).
 */
class TripTracker(
    private val chargeResetPercent: Float = 5f,
    private val maxStepMs: Long = 10_000L,   // longer gaps between readings are not integrated
) {
    var startOdo = Float.NaN; private set
    var startSoc = Float.NaN; private set
    var lastOdo = Float.NaN; private set
    var lastSoc = Float.NaN; private set
    /** Net energy taken from the battery, Wh (regen subtracts). */
    var energyWh = 0f; private set
    private var lastPowerMs = -1L   // -1 = no power reading yet

    val km: Float get() = if (startOdo.isNaN() || lastOdo.isNaN()) 0f else (lastOdo - startOdo).coerceAtLeast(0f)
    val socUsed: Float get() = if (startSoc.isNaN() || lastSoc.isNaN()) 0f else (startSoc - lastSoc).coerceAtLeast(0f)
    /** Wh per km, once at least 0.5 km has been ridden. */
    val whPerKm: Float? get() = if (km >= 0.5f && energyWh > 0f) energyWh / km else null
    /** km per 1 % battery on this trip, once at least 2 % has been used. */
    val kmPerPercent: Float? get() = if (socUsed >= 2f) km / socUsed else null

    /** Forget everything (used by "Delete all my data"). */
    fun clear() {
        startOdo = Float.NaN; startSoc = Float.NaN; lastOdo = Float.NaN; lastSoc = Float.NaN; energyWh = 0f; lastPowerMs = -1L
    }

    fun reset() {
        startOdo = lastOdo; startSoc = lastSoc; energyWh = 0f; lastPowerMs = -1L
    }

    /** Feed the latest readings (any may be null). */
    fun update(odo: Float?, soc: Float?, vbat: Float?, ibat: Float?, nowMs: Long = System.currentTimeMillis()) {
        if (odo != null && !odo.isNaN()) {
            if (startOdo.isNaN() || odo < startOdo - 0.05f) startOdo = odo   // first reading / odometer reset
            lastOdo = odo
        }
        if (soc != null && !soc.isNaN()) {
            if (startSoc.isNaN()) startSoc = soc
            lastSoc = soc
            if (soc >= startSoc + chargeResetPercent) {                        // charged: new trip
                startSoc = soc; startOdo = lastOdo; energyWh = 0f
            }
        }
        if (vbat != null && ibat != null) {
            val dt = nowMs - lastPowerMs
            if (lastPowerMs >= 0L && dt in 1..maxStepMs) energyWh += vbat * ibat * dt / 3_600_000f
            lastPowerMs = nowMs
        }
    }

    fun save(): String = String.format(Locale.US, "%s,%s,%s,%s,%.4f", startOdo, startSoc, lastOdo, lastSoc, energyWh)

    fun load(s: String?) {
        if (s.isNullOrBlank()) return
        try {
            val p = s.split(",")
            startOdo = p[0].toFloat(); startSoc = p[1].toFloat(); lastOdo = p[2].toFloat(); lastSoc = p[3].toFloat()
            energyWh = p[4].toFloat()
        } catch (_: Exception) {
            startOdo = Float.NaN; startSoc = Float.NaN; lastOdo = Float.NaN; lastSoc = Float.NaN; energyWh = 0f
        }
    }
}
