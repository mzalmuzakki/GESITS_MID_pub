package io.github.mzalmuzakki.gesitsmid

import java.util.TimeZone
import kotlin.test.*

class RidesTest {
    private val tz = TimeZone.getTimeZone("Asia/Jakarta")
    private val min = 60_000L

    /** 10 minutes at 36 km/h (6 km), 72 V x 25 A, then stopped. */
    private fun ride(r: RideRecorder, t0: Long, odo0: Float): Pair<Ride?, Long> {
        var t = t0; var odo = odo0; var done: Ride? = null
        for (i in 0 until 600) { done = r.sample(t, 36f, odo, 80f - i / 100f, 72f, 25f) ?: done; t += 1000; odo += 0.01f }
        for (i in 0 until 400) { done = r.sample(t, 0f, odo, 74f, 72f, 0f) ?: done; t += 1000 }   // > 5 min stopped
        return done to t
    }

    @Test fun recordsOneRide() {
        val r = RideRecorder()
        val (ride, _) = ride(r, 1_000_000L, 100f)
        assertNotNull(ride)
        assertEquals(6f, ride.km, 0.05f)
        assertEquals(300f, ride.energyWh, 2f)          // 1800 W for 10 min
        assertEquals(50f, ride.whPerKm!!, 1f)
        assertEquals(36f, ride.avgSpeed!!, 0.5f)
        assertEquals(36f, ride.maxSpeed)
        assertFalse(r.active)
    }

    @Test fun shortStopsDoNotSplitRides_andTickEndsWhileDisconnected() {
        val r = RideRecorder()
        var t = 0L
        repeat(60) { r.sample(t, 30f, 10f + it * 0.008f, 80f, null, null); t += 1000 }
        t += 2 * min                                     // 2 min at a traffic light / BT blip
        repeat(60) { r.sample(t, 30f, 10.5f + it * 0.008f, 79f, null, null); t += 1000 }
        assertTrue(r.active)
        assertNull(r.tick(t + 4 * min))
        val ride = r.tick(t + 6 * min)                     // ignition off: ends by itself
        assertNotNull(ride); assertEquals(0.97f, ride.km, 0.02f)
    }

    @Test fun tinyMovementIsNotARide() {
        val r = RideRecorder()
        r.sample(0, 5f, 10f, 80f, null, null); r.sample(1000, 5f, 10.05f, 80f, null, null)
        assertNull(r.finish(2000))
    }

    @Test fun lineRoundTripAndCsv() {
        val ride = Ride(1_700_000_000_000, 1_700_000_600_000, 6f, 300f, 12f, 80f, 74f, 52f, 540_000)
        assertEquals(ride, Ride.fromLine(ride.toLine()))
        assertNull(Ride.fromLine("garbage"))
        assertTrue(ride.toCsv(tz).startsWith("2023-11-15 05:13,2023-11-15 05:23,6.00,300,12,50.0"))
    }

    @Test fun statsPeriodsRecordsAndSavings() {
        val day = 24 * 60 * min
        val now = 1_761_900_000_000L                      // 31 Oct 2025, a Friday
        val rides = listOf(
            Ride(now - 1000, now, 10f, 300f, 20f, 90f, 80f, 60f, 1_200_000),
            Ride(now - day, now - day + 1, 20f, 500f, 30f, 80f, 60f, 70f, 2_400_000),
            Ride(now - 10 * day, now - 10 * day + 1, 30f, 1200f, 40f, 90f, 50f, 50f, 3_600_000),
        )
        val s = RideStats(rides, tz)
        assertEquals(60f, s.totalKm); assertEquals(3, s.count)
        assertEquals(2000f / 60f, s.avgWhPerKm!!, 0.01f)
        assertEquals(30f, s.longest!!.km); assertEquals(25f, s.mostEfficient!!.whPerKm!!); assertEquals(70f, s.topSpeed)
        val d = s.dailyKm(now, 7); assertEquals(7, d.size); assertEquals(10f, d.last()); assertEquals(20f, d[5])
        val w = s.weeklyKm(now, 8); assertEquals(30f, w.last()); assertEquals(30f, w[w.size - 2])
        val sv = s.savings(kmPerLitre = 40f, rpPerLitre = 10_000f, rpPerKwh = 1444.7f)
        assertEquals(1.5f, sv.litres); assertEquals(15_000f, sv.petrolRp)
        assertEquals(2.889f * 1000f, sv.electricityRp, 1f); assertEquals(3.465f, sv.co2Kg, 0.001f)
    }

    @Test fun alarmsFireOnceWithHysteresis() {
        val a = AlarmMonitor()
        assertEquals(listOf("Battery low: 19%"), a.evaluate(19f, null, null, null, emptyList()))
        assertTrue(a.evaluate(18f, null, null, null, emptyList()).isEmpty())   // no repeat
        assertTrue(a.evaluate(22f, null, null, null, emptyList()).isEmpty())   // not yet cleared
        a.evaluate(26f, null, null, null, emptyList())                          // cleared
        assertEquals(1, a.evaluate(19f, null, null, null, emptyList()).size)    // fires again
        assertEquals(listOf("MID error: Throttle fault"), a.evaluate(null, null, null, null, listOf("Throttle fault")))
    }
}
