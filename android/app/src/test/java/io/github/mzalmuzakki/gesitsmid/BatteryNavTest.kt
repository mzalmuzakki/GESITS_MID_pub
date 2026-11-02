package io.github.mzalmuzakki.gesitsmid

import kotlin.test.*

class BatteryNavTest {
    private val s = 1000L

    @Test fun cyclesAndChargeSessions() {
        val b = BatteryTracker()
        var t = 0L
        for (soc in 90 downTo 40) { b.update(soc.toFloat(), 20f, 30f, t); t += 60 * s }   // ride: 50 %
        for (soc in 40..95) { b.update(soc.toFloat(), -10f, 0f, t); t += 60 * s }        // charge to 95
        for (soc in 95 downTo 45) { b.update(soc.toFloat(), 20f, 30f, t); t += 60 * s }  // ride: 50 %
        assertEquals(1.0f, b.equivalentCycles, 0.01f)
        assertEquals(1, b.chargeSessions)
        val b2 = BatteryTracker(); b2.load(b.save())
        assertEquals(b.equivalentCycles, b2.equivalentCycles, 0.001f); assertEquals(1, b2.chargeSessions)
    }

    @Test fun ignoresJumpsLikeBatterySwap() {
        val b = BatteryTracker()
        b.update(80f, null, null, 0); b.update(30f, null, null, s)
        assertEquals(0f, b.equivalentCycles)
    }

    @Test fun chargingDetectionRateAndTarget() {
        val b = BatteryTracker()
        var t = 0L
        val events = mutableListOf<BatteryTracker.Event>()
        repeat(15) { events += b.update(60f, -12f, 0f, t); t += s }                 // 10 s of charging current
        assertTrue(b.charging); assertTrue(BatteryTracker.Event.ChargeStarted in events)
        assertEquals(75f, b.chargeRatePctPerHour(t, -900f, 1.2f)!!, 0.1f)            // from power: 0.9 kW / 1.2 kWh
        // 30 min later at 70 %: measured 20 %/h
        t += 30 * 60 * s
        events += b.update(70f, -12f, 0f, t)
        val rate = b.chargeRatePctPerHour(t, -900f, 1.2f)!!
        assertEquals(20f, rate, 0.5f)
        assertTrue(kotlin.math.abs(b.minutesTo(80f, rate)!! - 30) <= 1)
        events += b.update(80f, -12f, 0f, t + s)
        assertTrue(events.any { it is BatteryTracker.Event.TargetReached })
        assertTrue(b.update(80f, 15f, 0f, t + 2 * s).contains(BatteryTracker.Event.ChargeEnded))
    }

    @Test fun regenWhileRidingIsNotCharging() {
        val b = BatteryTracker()
        repeat(30) { b.update(60f, -15f, 40f, it * s) }
        assertFalse(b.charging)
    }

    @Test fun capacityFromRides() {
        val rides = listOf(Ride(0, 1, 10f, 300f, 0f, 90f, 75f, 50f, 1), Ride(2, 3, 10f, 300f, 0f, 75f, 60f, 50f, 1))
        assertEquals(2.0f, estimateCapacityKwh(rides)!!, 0.01f)   // 600 Wh for 30 %
        assertNull(estimateCapacityKwh(rides.take(1)))             // 15 % is not enough data
    }

    @Test fun speedWarning() {
        val w = SpeedWarning(70)
        assertFalse(w.update(75f, 0)); assertFalse(w.update(75f, 2000)); assertTrue(w.update(75f, 3000))
        assertTrue(w.update(69f, 4000)); assertFalse(w.update(66f, 5000))
        w.limit = 0; assertFalse(w.update(120f, 9000))
    }

    @Test fun navParserEnglishAndIndonesian() {
        val en = NavParser.parse("200 m", "Turn left onto Jl. Sudirman", "15 min · 5.2 km · ETA 14:32")!!
        assertEquals("200m", en.distance); assertEquals(NavParser.Turn.LEFT, en.turn)
        assertEquals("Jl. Sudirman", en.street); assertEquals("14:32", en.eta)
        val id = NavParser.parse("1,2 km", "Belok kanan ke Jl. Gatot Subroto", "Tiba 09.05")!!
        assertEquals("1,2km", id.distance); assertEquals(NavParser.Turn.RIGHT, id.turn); assertEquals("Jl. Gatot Subroto", id.street)
        assertEquals(NavParser.Turn.UTURN, NavParser.turnOf("Putar balik di Jl. Thamrin"))
        assertEquals(NavParser.Turn.ROUNDABOUT, NavParser.turnOf("Di bundaran, ambil jalan keluar ke-2"))
        assertEquals(NavParser.Turn.SLIGHT_LEFT, NavParser.turnOf("Keep left at the fork"))
        assertEquals(NavParser.Turn.ARRIVE, NavParser.turnOf("Anda telah tiba di tujuan"))
    }

    @Test fun learnsReversedBatteryCurrent() {
        val c = CurrentSign()
        var changed = false
        repeat(60) { i -> changed = changed or c.update(35f, if (i % 10 == 0) 8f else -25f) }   // drawn negative, some regen
        assertTrue(changed); assertEquals(-1, c.sign)
        val c2 = CurrentSign(); c2.load(c.save()); assertEquals(-1, c2.sign); assertTrue(c2.decided)
        repeat(60) { assertFalse(CurrentSign().update(0f, -1f)) }      // parked: nothing learned
        val t = Telemetry(); t.ibatSign = -1
        assertTrue(t.offer("IBAT", "-25")); assertEquals(25f, t.f("IBAT")); assertEquals(-25f, t.ibatRaw)
    }

    @Test fun aLongRegenDescentDoesNotFlipTheSign() {
        val c = CurrentSign()
        repeat(40) { c.update(30f, 20f) }                        // first window: normal battery
        assertTrue(c.decided); assertEquals(1, c.sign)
        repeat(80) { assertFalse(c.update(40f, -15f)) }          // two windows of regen downhill
        assertEquals(1, c.sign)
        repeat(40) { c.update(30f, 20f) }                        // riding again: back to normal, counter reset
        repeat(80) { c.update(40f, -15f) }
        assertEquals(1, c.sign)
    }
}
