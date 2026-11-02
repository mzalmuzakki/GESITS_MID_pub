package io.github.mzalmuzakki.gesitsmid

import java.util.Locale

/**
 * Adaptive range from energy: range = battery % x battery capacity (Wh) / your consumption (Wh/km).
 *
 *  - Consumption is measured from VBAT x IBAT while riding (regen counts back) over the last
 *    [effWindowKm] km, so it follows riding style, mode and load within the first kilometre.
 *  - Battery capacity is learned from energy against battery % - both while riding and while
 *    charging - over the last [capWindowPct] % of battery movement. One charge is enough to learn
 *    a bigger (custom) battery; slow changes such as ageing are followed too.
 *
 * Until enough is measured, starting values (the original G1: ~1.5 kWh, ~30 Wh/km = ~50 km) are
 * blended in and fade out. Battery % only changes in whole steps, so measurements run from one
 * change to the next (no rounding error). Pure Kotlin, unit-tested.
 */
class EnergyRange(
    var priorCapacityWh: Float = 1500f,
    var priorWhPerKm: Float = 30f,
    private val capWindowPct: Float = 100f,
    private val capPriorPct: Float = 6f,
    private val capFadePct: Float = 15f,
    private val effWindowKm: Float = 15f,
    private val effPriorKm: Float = 1f,
    private val effFadeKm: Float = 3f,
    private val maxStepMs: Long = 10_000L,
) {
    private class Seg(val a: Float, val wh: Float)   // a = % (capacity) or km (consumption)
    private val cap = ArrayDeque<Seg>()
    private val eff = ArrayDeque<Seg>()

    private var lastMs = -1L
    private var lastSoc = Float.NaN
    private var edgeSoc = Float.NaN      // battery % at the last change
    private var whSinceEdge = 0f
    private var lastOdo = Float.NaN
    private var edgeOdo = Float.NaN      // odometer at the last change
    private var whSinceOdo = 0f

    val capPct: Float get() = cap.sumOf { it.a.toDouble() }.toFloat()
    val effKm: Float get() = eff.sumOf { it.a.toDouble() }.toFloat()
    val hasData: Boolean get() = cap.isNotEmpty() || eff.isNotEmpty()
    val capacityLearned: Boolean get() = capPct >= capFadePct
    val consumptionLearned: Boolean get() = effKm >= effFadeKm

    /** Battery capacity in Wh: measured, blended with the starting value until [capFadePct] % is seen. */
    val capacityWh: Float
        get() {
            val pct = capPct
            val w = capPriorPct * (1f - pct / capFadePct).coerceAtLeast(0f)
            if (pct + w <= 0f) return priorCapacityWh
            return ((cap.sumOf { it.wh.toDouble() }.toFloat() + priorCapacityWh / 100f * w) / (pct + w) * 100f)
        }

    /** Consumption in Wh per km over the recent riding. */
    val whPerKm: Float
        get() {
            val km = effKm
            val w = effPriorKm * (1f - km / effFadeKm).coerceAtLeast(0f)
            if (km + w <= 0f) return priorWhPerKm
            return ((eff.sumOf { it.wh.toDouble() }.toFloat() + priorWhPerKm * w) / (km + w)).coerceAtLeast(3f)
        }

    fun rangeKm(soc: Float): Float = soc.coerceIn(0f, 100f) / 100f * capacityWh / whPerKm

    /**
     * Feed readings about once a second. IBAT > 0 = taken from the battery. [charging] = the bike is
     * being charged (energy then only counts towards capacity, not consumption).
     */
    fun update(soc: Float?, vbat: Float?, ibat: Float?, odo: Float?, charging: Boolean, nowMs: Long) {
        var dWh = 0f
        if (vbat != null && ibat != null) {
            val dt = nowMs - lastMs
            if (lastMs >= 0L && dt in 1..maxStepMs) dWh = vbat * ibat * dt / 3_600_000f
            else if (lastMs >= 0L) { edgeSoc = Float.NaN; edgeOdo = Float.NaN }   // energy unknown for a while: start over
            lastMs = nowMs
        }
        whSinceEdge += dWh
        if (soc != null && !soc.isNaN() && soc in 0f..100f) {
            if (!lastSoc.isNaN() && soc != lastSoc) {
                if (kotlin.math.abs(soc - lastSoc) > 3f) edgeSoc = Float.NaN     // a jump, not use (garbled reading)
                if (!edgeSoc.isNaN()) {
                    val dPct = soc - edgeSoc
                    val perPct = -whSinceEdge / dPct              // > 0 when energy and % moved together
                    if (perPct in 8f..250f) add(cap, Seg(kotlin.math.abs(dPct), kotlin.math.abs(whSinceEdge)), capWindowPct)
                }
                edgeSoc = soc; whSinceEdge = 0f
            }
            lastSoc = soc
        }
        if (odo != null && !odo.isNaN()) {
            if (charging || (!lastOdo.isNaN() && (odo < lastOdo - 0.05f || odo > lastOdo + 1f))) { edgeOdo = Float.NaN; whSinceOdo = 0f }
            else {
                whSinceOdo += dWh
                if (!lastOdo.isNaN() && odo != lastOdo) {
                    if (edgeOdo.isNaN()) { edgeOdo = odo; whSinceOdo = 0f }
                    else {
                        val km = odo - edgeOdo
                        if (km >= 0.5f) {
                            val per = whSinceOdo / km
                            if (km <= 20f && per in 3f..200f) add(eff, Seg(km, whSinceOdo), effWindowKm)
                            edgeOdo = odo; whSinceOdo = 0f
                        }
                    }
                }
            }
            lastOdo = odo
        }
    }

    private fun add(q: ArrayDeque<Seg>, s: Seg, window: Float) {
        q.addLast(s)
        var total = q.sumOf { it.a.toDouble() }.toFloat()
        while (q.size > 1 && total - q.first().a >= window) total -= q.removeFirst().a
    }

    /** Seed capacity from rides recorded before (only if nothing is learned yet). */
    fun seedCapacity(wh: Float, pct: Float) {
        if (cap.isEmpty() && pct >= 2f && wh / pct in 8f..250f) cap.addLast(Seg(pct, wh))
    }

    fun reset() {
        cap.clear(); eff.clear(); lastMs = -1L; lastSoc = Float.NaN; edgeSoc = Float.NaN; whSinceEdge = 0f
        lastOdo = Float.NaN; edgeOdo = Float.NaN; whSinceOdo = 0f
    }

    fun describe(indo: Boolean): String {
        val c = String.format(Locale.US, "%.2f kWh", capacityWh / 1000f)
        val e = String.format(Locale.US, "%.0f Wh/km", whPerKm)
        val full = String.format(Locale.US, "%.0f km", rangeKm(100f))
        return if (indo)
            "Baterai $c (" + (if (capacityLearned) "terukur" else String.format(Locale.US, "belajar %.0f/%.0f%%", capPct, capFadePct)) + ")  ·  " +
                "Konsumsi $e (" + (if (consumptionLearned) String.format(Locale.US, "%.0f km terakhir", effKm) else "belajar") + ")  ·  100% = $full"
        else
            "Battery $c (" + (if (capacityLearned) "measured" else String.format(Locale.US, "learning %.0f/%.0f%%", capPct, capFadePct)) + ")  ·  " +
                "Use $e (" + (if (consumptionLearned) String.format(Locale.US, "last %.0f km", effKm) else "learning") + ")  ·  100% = $full"
    }

    /** Learned data only (positions restart when the app starts again). */
    fun save(): String = buildString {
        cap.joinTo(this, "|") { String.format(Locale.US, "%.1f:%.1f", it.a, it.wh) }
        append(";")
        eff.joinTo(this, "|") { String.format(Locale.US, "%.2f:%.1f", it.a, it.wh) }
    }

    fun load(s: String?) {
        reset()
        if (s.isNullOrBlank()) return
        try {
            val parts = s.split(";")
            fun read(t: String, q: ArrayDeque<Seg>) = t.split("|").filter { it.isNotBlank() }.forEach {
                val (a, wh) = it.split(":"); q.addLast(Seg(a.toFloat(), wh.toFloat()))
            }
            read(parts[0], cap); read(parts.getOrElse(1) { "" }, eff)
        } catch (_: Exception) { reset() }
    }
}
