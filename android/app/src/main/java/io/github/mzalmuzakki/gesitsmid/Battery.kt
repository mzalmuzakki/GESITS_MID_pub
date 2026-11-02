package io.github.mzalmuzakki.gesitsmid

import java.util.Locale

/**
 * Battery bookkeeping, counted by this app from the day it is installed:
 *  - equivalent full cycles = total % discharged / 100 (e.g. 4 rides of 25 % = 1 cycle)
 *  - charging sessions (battery % went up by >= [minChargePct])
 *  - charging detection, % per hour and time to a target level
 * The true lifetime cycle count lives in the battery's BMS; if the MID ever reports one
 * the MID doesn't report it). Pure Kotlin, unit-tested.
 */
class BatteryTracker(private val minChargePct: Float = 10f) {
    var dischargedPct = 0f; private set
    var chargeSessions = 0; private set
    var sinceMs = 0L; private set
    private var lastSoc = Float.NaN
    private var lowSinceCharge = Float.NaN   // lowest SOC since the last counted charge
    private var peak = Float.NaN             // highest SOC of the charge just counted
    private var counted = false              // the current rise was already counted

    // live charging state (not saved)
    var charging = false; private set
    private var negSinceMs = -1L   // -1 = no charging current seen
    private var chargeStartMs = 0L
    private var chargeStartSoc = Float.NaN
    private var targetNotified = false

    val equivalentCycles: Float get() = dischargedPct / 100f

    sealed class Event {
        object ChargeStarted : Event()
        data class TargetReached(val soc: Float) : Event()
        object ChargeEnded : Event()
    }

    /**
     * Feed readings about once a second. IBAT < 0 while not moving = charging
     * (regen also makes IBAT negative, but only while moving).
     */
    fun update(soc: Float?, ibat: Float?, spd: Float?, nowMs: Long, targetPct: Float = 80f): List<Event> {
        val ev = mutableListOf<Event>()
        if (sinceMs == 0L) sinceMs = nowMs
        if (soc != null && !soc.isNaN()) {
            if (!lastSoc.isNaN()) {
                val d = lastSoc - soc
                if (d > 0 && d <= 5f) dischargedPct += d          // ignore jumps (e.g. battery swap)
            }
            if (!counted) {
                if (lowSinceCharge.isNaN() || soc < lowSinceCharge) lowSinceCharge = soc
                if (soc - lowSinceCharge >= minChargePct) { chargeSessions++; counted = true; peak = soc }
            } else {
                if (peak.isNaN() || soc > peak) peak = soc
                if (peak - soc >= 3f) { counted = false; lowSinceCharge = soc }   // riding again: next rise is a new charge
            }
            lastSoc = soc
        }
        val still = (spd ?: 0f) < 1f
        val negative = ibat != null && ibat < -2f && still          // real charging is several amps
        if (negative) {
            if (negSinceMs < 0L) negSinceMs = nowMs
            if (!charging && nowMs - negSinceMs >= 10_000) {
                charging = true; chargeStartMs = nowMs; chargeStartSoc = soc ?: lastSoc; targetNotified = false
                ev += Event.ChargeStarted
            }
        } else {
            negSinceMs = -1L
            if (charging && (ibat != null && ibat > 0.5f || !still)) { charging = false; ev += Event.ChargeEnded }
        }
        if (charging && soc != null && !targetNotified && soc >= targetPct) { targetNotified = true; ev += Event.TargetReached(soc) }
        return ev
    }

    /** % per hour while charging: measured from SOC once it moved >= 2 %, else estimated from power. */
    fun chargeRatePctPerHour(nowMs: Long, powerW: Float?, capacityKwh: Float?): Float? {
        if (!charging) return null
        val gained = lastSoc - chargeStartSoc
        val hours = (nowMs - chargeStartMs) / 3_600_000f
        if (gained >= 2f && hours > 0f) return gained / hours
        if (powerW != null && powerW < 0 && capacityKwh != null && capacityKwh > 0) return (-powerW / 1000f) / capacityKwh * 100f
        return null
    }

    /** Minutes until [targetPct], or null if unknown. */
    fun minutesTo(targetPct: Float, ratePctPerHour: Float?): Int? {
        if (ratePctPerHour == null || ratePctPerHour <= 0f || lastSoc.isNaN()) return null
        val left = targetPct - lastSoc
        return if (left <= 0f) 0 else (left / ratePctPerHour * 60f).toInt()
    }

    fun save(): String = String.format(Locale.US, "%.2f;%d;%d;%s;%s;%s;%b", dischargedPct, chargeSessions, sinceMs, lastSoc, lowSinceCharge, peak, counted)

    fun load(s: String?) {
        if (s.isNullOrBlank()) return
        try {
            val p = s.split(";")
            dischargedPct = p[0].toFloat(); chargeSessions = p[1].toInt(); sinceMs = p[2].toLong()
            lastSoc = p[3].toFloat(); lowSinceCharge = p[4].toFloat()
            if (p.size >= 7) { peak = p[5].toFloat(); counted = p[6].toBoolean() }
        } catch (_: Exception) { clear() }
    }

    fun clear() {
        dischargedPct = 0f; chargeSessions = 0; sinceMs = 0L; lastSoc = Float.NaN; lowSinceCharge = Float.NaN
        peak = Float.NaN; counted = false; charging = false; negSinceMs = -1L
    }
}

/**
 * Which sign of the battery current (IBAT) means "taken from the battery" on this bike. The original G1
 * battery reports it positive, but some batteries (e.g. a custom pack's BMS) report it negative, which
 * would turn every ride into "charging". Learned while riding: then the current is mostly drawn, with
 * regen only now and then. Pure Kotlin, unit-tested.
 */
class CurrentSign {
    var sign = 1; private set
    var decided = false; private set
    private var pos = 0
    private var neg = 0
    private var against = 0                            // windows in a row that disagree with the decided sign
    private var firstVote = 0                          // the first window's result, until a second one agrees

    /**
     * Feed each new current reading once (one every ~5 s while riding); returns true when the sign changed. Two
     * windows of 20 readings (~2 min of riding each) that agree decide it; after that it only flips when 8
     * windows in a row (~13 min of riding with the current the "wrong" way) disagree.
     */
    fun update(spd: Float?, rawIbat: Float?): Boolean {
        if (spd == null || rawIbat == null || spd < 8f || kotlin.math.abs(rawIbat) < 2f) return false
        if (rawIbat > 0f) pos++ else neg++
        val n = pos + neg
        if (n < 20) return false                       // 20 current readings while riding (~2 min)
        val s = when { neg >= n * 0.75f -> -1; pos >= n * 0.75f -> 1; else -> 0 }
        pos = 0; neg = 0
        if (s == 0) { against = 0; return false }
        if (!decided) {
            if (firstVote != s) { firstVote = s; return false }
            decided = true; against = 0
            if (s == sign) return false
            sign = s; return true
        }
        if (s == sign) { against = 0; return false }
        against++
        if (against < 8) return false
        against = 0; sign = s; return true
    }

    fun save(): String = "$sign;$decided"
    fun load(s: String?) {
        val p = s?.split(";") ?: return
        sign = if (p.getOrNull(0) == "-1") -1 else 1
        decided = p.getOrNull(1) == "true"
    }
}

/** Usable capacity estimated from rides: energy used / battery % used x 100. Needs >= 20 % of data. */
fun estimateCapacityKwh(rides: List<Ride>): Float? {
    val r = rides.filter { it.energyWh > 0 && it.socStart - it.socEnd >= 2f }
    val pct = r.sumOf { (it.socStart - it.socEnd).toDouble() }
    if (pct < 20.0) return null
    return (r.sumOf { it.energyWh.toDouble() } / pct * 100.0 / 1000.0).toFloat()
}

/** Shows a warning while speed stays above [limit] for 3 s; clears 3 km/h below it. 0 = off. */
class SpeedWarning(var limit: Int = 0) {
    private var overSince = -1L
    var active = false; private set
    private var activeSince = -1L

    /** Once shown, the warning stays at least 10 s (riding around the limit doesn't make it flicker). */
    fun update(spd: Float?, nowMs: Long): Boolean {
        if (limit <= 0) { active = false; overSince = -1L; return false }
        if (spd == null) { if (active && nowMs - activeSince >= 10_000) active = false; overSince = -1L; return active }
        if (spd > limit) {
            if (overSince < 0L) overSince = nowMs
            if (!active && nowMs - overSince >= 3000) { active = true; activeSince = nowMs }
        } else {
            overSince = -1L
            if (active && spd <= limit - 3 && nowMs - activeSince >= 10_000) active = false
        }
        return active
    }
}

/**
 * Turns a Google Maps navigation notification into a short instruction for the MID.
 * Works with English and Indonesian Google Maps.
 */
object NavParser {
    enum class Turn { LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, ROUNDABOUT, STRAIGHT, ARRIVE, NONE }
    data class Instruction(val distance: String?, val turn: Turn, val street: String, val eta: String?)

    private val dist = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft)\b""", RegexOption.IGNORE_CASE)

    fun turnOf(s: String): Turn {
        val t = s.lowercase(Locale.ROOT)
        return when {
            listOf("arrive", "destination", "tiba", "tujuan").any { it in t } -> Turn.ARRIVE
            listOf("u-turn", "putar balik", "make a u").any { it in t } -> Turn.UTURN
            listOf("roundabout", "bundaran").any { it in t } -> Turn.ROUNDABOUT
            listOf("slight left", "keep left", "sedikit ke kiri", "agak ke kiri", "tetap di kiri", "tetap di jalur kiri").any { it in t } -> Turn.SLIGHT_LEFT
            listOf("slight right", "keep right", "sedikit ke kanan", "agak ke kanan", "tetap di kanan", "tetap di jalur kanan").any { it in t } -> Turn.SLIGHT_RIGHT
            listOf("left", "kiri").any { it in t } -> Turn.LEFT
            listOf("right", "kanan").any { it in t } -> Turn.RIGHT
            listOf("straight", "continue", "head", "lurus", "terus", "lanjutkan").any { it in t } -> Turn.STRAIGHT
            else -> Turn.NONE
        }
    }

    /** title / text / subText as Google Maps puts them in its navigation notification. */
    fun parse(title: String?, text: String?, subText: String?): Instruction? {
        val all = listOfNotNull(title, text).joinToString("  ").trim()
        if (all.isEmpty()) return null
        val d = dist.find(title ?: "") ?: dist.find(all)
        val turn = turnOf(all)
        // street: the part after "onto/to/ke/menuju", else the text without the distance
        val streetSrc = text?.takeIf { it.isNotBlank() } ?: title ?: ""
        val m = Regex("""(?i)\b(onto|to|toward|towards|menuju|ke)\s+(.+)$""").find(streetSrc)
        val street = (m?.groupValues?.get(2) ?: streetSrc.replace(dist, "")).replace(Regex("""\s+"""), " ").trim().trim('-', '·', ',')
        val eta = subText?.let { Regex("""(?i)(?:eta|tiba)\s*([0-2]?\d[:.][0-5]\d)""").find(it)?.groupValues?.get(1) }
        return Instruction(d?.value?.replace(" ", ""), turn, street, eta)
    }
}
