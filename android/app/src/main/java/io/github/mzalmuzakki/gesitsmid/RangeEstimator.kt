package io.github.mzalmuzakki.gesitsmid

import java.util.Locale

/**
 * Adaptive range: learns "km per 1 % battery" from the odometer (ODO) and battery level (SOC).
 *
 * Each time SOC drops by >= 1 %, the km ridden since the previous drop is recorded as one segment.
 * Only the most recent [windowPercent] % of battery use is kept, so the estimate follows your
 * current riding (season, load, drive mode). With no data the result is [priorKmPerPercent]; the
 * prior counts like [priorWeightPercent] % of riding and fades out linearly until [fadePercent] % of
 * battery use has been learned - from then on the range is purely measured.
 *
 * Not counted: charging (SOC rises), odometer going backwards, gaps longer than [maxGapMs] between
 * samples (the bike may have been charged or ridden without the app), and impossible segments.
 *
 * Same algorithm as gesits/rangeest.py. Pure Kotlin so it can be unit-tested off-device.
 */
class RangeEstimator(
    var priorKmPerPercent: Float = 0.5f,   // Gesits G1: about 50 km per full battery
    private val windowPercent: Float = 60f,
    private val priorWeightPercent: Float = 8f,
    private val fadePercent: Float = 20f,
    private val maxKmPerPercent: Float = 5f,
    private val maxGapMs: Long = 30 * 60 * 1000L,
) {
    data class Segment(val km: Float, val percent: Float)

    private val segments = ArrayDeque<Segment>()
    private var anchorOdo = Float.NaN
    private var anchorSoc = Float.NaN
    private var lastSampleMs = 0L

    val learnedKm: Float get() = segments.sumOf { it.km.toDouble() }.toFloat()
    val learnedPercent: Float get() = segments.sumOf { it.percent.toDouble() }.toFloat()

    /** Blend of measured riding and the (fading) prior; never below 0.05 km/%. */
    val kmPerPercent: Float
        get() {
            val pct = learnedPercent
            val w = priorWeightPercent * (1f - pct / fadePercent).coerceAtLeast(0f)
            if (pct + w <= 0f) return priorKmPerPercent
            return ((learnedKm + priorKmPerPercent * w) / (pct + w)).coerceAtLeast(0.05f)
        }

    /** True once the prior has faded out completely. */
    val fullyLearned: Boolean get() = learnedPercent >= fadePercent

    fun rangeKm(soc: Float): Float = (soc.coerceIn(0f, 100f) * kmPerPercent)

    /** Feed every new (ODO, SOC) pair. Returns true if a segment was learned. */
    fun update(odoKm: Float, socPercent: Float, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (odoKm.isNaN() || socPercent.isNaN() || socPercent < 0f || socPercent > 100f) return false
        val gap = nowMs - lastSampleMs
        lastSampleMs = nowMs
        val restart = anchorOdo.isNaN() || anchorSoc.isNaN() ||
            gap > maxGapMs ||                    // app was off: unknown what happened in between
            odoKm < anchorOdo - 0.05f ||         // odometer went backwards
            socPercent > anchorSoc + 0.5f        // charging
        if (restart) { anchor(odoKm, socPercent); return false }

        val drop = anchorSoc - socPercent
        if (drop < 1f) return false
        val km = odoKm - anchorOdo
        anchor(odoKm, socPercent)
        if (km < 0f || km > drop * maxKmPerPercent) return false   // implausible: skip
        segments.addLast(Segment(km, drop))
        while (segments.size > 1 && learnedPercent - segments.first().percent >= windowPercent) segments.removeFirst()
        return true
    }

    private fun anchor(odo: Float, soc: Float) { anchorOdo = odo; anchorSoc = soc }

    fun reset() { segments.clear(); anchorOdo = Float.NaN; anchorSoc = Float.NaN; lastSampleMs = 0L }

    fun describe(): String = String.format(
        Locale.US, "%.2f km per 1%% - %s (%.1f km over %.0f %% battery)", kmPerPercent,
        if (fullyLearned) "learned" else "learning", learnedKm, learnedPercent,
    )

    /** Compact text form for SharedPreferences. */
    fun save(): String = buildString {
        append(String.format(Locale.US, "%s,%s,%d;", anchorOdo, anchorSoc, lastSampleMs))
        segments.joinTo(this, "|") { String.format(Locale.US, "%.3f:%.1f", it.km, it.percent) }
    }

    fun load(s: String?) {
        reset()
        if (s.isNullOrBlank()) return
        try {
            val (head, body) = s.split(";", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val h = head.split(",")
            anchorOdo = h[0].toFloat(); anchorSoc = h[1].toFloat(); lastSampleMs = h[2].toLong()
            body.split("|").filter { it.isNotBlank() }.forEach {
                val (k, p) = it.split(":")
                segments.addLast(Segment(k.toFloat(), p.toFloat()))
            }
        } catch (_: Exception) {
            reset()
        }
    }
}
