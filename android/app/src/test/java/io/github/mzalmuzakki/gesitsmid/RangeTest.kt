package io.github.mzalmuzakki.gesitsmid


import kotlin.math.abs
import kotlin.test.*

class RangeTest {
    private val min = 60_000L

    /** Ride `pct` percent at `kmPerPct`, one sample per 10 s, SOC reported as whole numbers like the MID. */
    private fun ride(r: RangeEstimator, startOdo: Float, startSoc: Int, pct: Int, kmPerPct: Float, t0: Long): Pair<Float, Long> {
        var t = t0; var odo = startOdo
        val steps = pct * 10
        for (i in 0..steps) {
            val exactSoc = startSoc - i / 10f
            r.update(odo, kotlin.math.ceil(exactSoc.toDouble()).toFloat(), t)   // MID shows integer %
            odo += kmPerPct / 10f; t += 10_000
        }
        return odo to t
    }

    @Test fun startsAtPriorAndLearnsRealConsumption() {
        val r = RangeEstimator(priorKmPerPercent = 1.0f)
        assertEquals(1.0f, r.kmPerPercent)
        assertEquals(80f, r.rangeKm(80f))
        ride(r, 1000f, 90, 40, 0.6f, 0L)
        assertTrue(abs(r.kmPerPercent - 0.6f) < 0.02f, "got ${r.kmPerPercent}")   // prior gone after 20 %
        assertTrue(r.fullyLearned)
        assertTrue(r.learnedPercent in 38f..40f)
    }

    @Test fun windowFollowsRecentRiding() {
        val r = RangeEstimator()
        val (odo, t) = ride(r, 0f, 100, 50, 1.2f, 0L)
        ride(r, odo, 50, 45, 0.5f, t)                         // later: harder riding
        assertTrue(r.learnedPercent <= 61f)
        assertTrue(r.kmPerPercent < 0.75f, "got ${r.kmPerPercent}")
    }

    @Test fun chargingAndGapsAreNotCounted() {
        val r = RangeEstimator()
        r.update(100f, 50f, 0); r.update(110f, 40f, min)       // 10 km / 10 %
        assertEquals(10f, r.learnedKm)
        r.update(110f, 90f, 2 * min)                            // charged: restart, nothing learned
        r.update(130f, 60f, 3 * min)                            // next ride after charging: 20 km / 30 %
        assertEquals(30f, r.learnedKm); assertEquals(40f, r.learnedPercent)
    }

    @Test fun earlyDataIsBlendedWithPrior() {
        val r = RangeEstimator(priorKmPerPercent = 1.0f)
        r.update(0f, 80f, 0); r.update(3f, 75f, min)            // 5 % at 0.6 km/%
        // weight 8 * (1 - 5/20) = 6 -> (3 + 6) / (5 + 6) = 0.818
        assertEquals(0.818f, r.kmPerPercent, 0.002f)
        assertFalse(r.fullyLearned)
    }

    @Test fun longGapRestarts() {
        val r = RangeEstimator()
        r.update(100f, 80f, 0)
        r.update(150f, 30f, 2 * 60 * min)                      // 2 h later: unknown what happened
        assertEquals(0f, r.learnedPercent)
    }

    @Test fun implausibleAndBackwardsSkipped() {
        val r = RangeEstimator()
        r.update(100f, 80f, 0); r.update(160f, 79f, min)        // 60 km for 1 %: impossible
        assertEquals(0f, r.learnedPercent)
        r.update(50f, 78f, 2 * min)                             // odometer went back: restart
        assertEquals(0f, r.learnedPercent)
    }

    @Test fun saveLoadRoundTrip() {
        val r = RangeEstimator()
        ride(r, 500f, 80, 20, 0.7f, 0L)
        val s = r.save()
        val r2 = RangeEstimator(); r2.load(s)
        assertEquals(r.learnedKm, r2.learnedKm, 0.01f)
        assertEquals(r.kmPerPercent, r2.kmPerPercent, 0.001f)
        r2.load("garbage"); assertEquals(0f, r2.learnedPercent)
    }

    @Test fun aGarbledBatteryJumpIsNotLearned() {
        val r = RangeEstimator()
        r.update(1000f, 96f, 0)
        assertFalse(r.update(1000.1f, 2f, 1000))     // 96 -> 2 % in a second: a bad reading, not riding
        assertEquals(0f, r.learnedPercent)
    }

    @Test fun appSettingsLearnADoubleBatteryWithinTenPercent() {
        val r = RangeEstimator(priorKmPerPercent = 0.5f, windowPercent = 40f, priorWeightPercent = 5f, fadePercent = 10f)
        assertEquals(46f, r.rangeKm(92f), 0.5f)                 // before riding: the original battery's 0.5 km per 1 %
        ride(r, 1000f, 100, 12, 1.1f, 0L)                         // 12 % at 1.1 km per 1 %
        assertTrue(r.fullyLearned)
        assertTrue(abs(r.rangeKm(88f) - 97f) < 8f, "range ${r.rangeKm(88f)}")
    }

    @Test fun aBatteryPercentBounceDoesNotHalveTheRange() {
        val r = RangeEstimator(priorKmPerPercent = 0.5f, windowPercent = 40f, priorWeightPercent = 5f, fadePercent = 10f)
        var odo = 1000f; var t = 0L
        for (pct in 100 downTo 80) {                             // 1.5 km per 1 %, bouncing back once at every step
            r.update(odo, pct.toFloat(), t); t += 30_000
            r.update(odo + 0.2f, (pct + 1).coerceAtMost(100).toFloat(), t); t += 30_000
            odo += 1.5f
        }
        r.update(odo, 79f, t)
        assertTrue(abs(r.kmPerPercent - 1.5f) < 0.2f, "km per 1 % ${r.kmPerPercent}")
    }

    @Test fun aSmallTopUpChargeRestartsTheMeasurement() {
        val r = RangeEstimator()
        r.update(1000f, 80f, 0); r.update(1010f, 70f, 600_000)               // 10 km / 10 %
        r.update(1010f, 72f, 700_000, charging = true)                      // 2 % top-up while connected
        r.update(1020f, 62f, 1_300_000)                                     // next 10 km / 10 %
        assertTrue(abs(r.kmPerPercent - 1.0f) < 0.15f, "km per 1 % ${r.kmPerPercent}")
    }
}

