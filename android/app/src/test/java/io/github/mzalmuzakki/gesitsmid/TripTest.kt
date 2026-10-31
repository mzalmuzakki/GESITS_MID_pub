package io.github.mzalmuzakki.gesitsmid

import kotlin.test.*

class TripTest {
    @Test fun distanceSocAndEnergy() {
        val t = TripTracker()
        // 60 s at 72 V x 20 A = 1440 W -> 24 Wh, while riding 1 km and using 2 %
        for (s in 0..60) t.update(1000f + s / 60f, if (s < 30) 80f else 78f, 72f, 20f, s * 1000L)
        assertEquals(1f, t.km, 0.01f)
        assertEquals(2f, t.socUsed)
        assertEquals(24f, t.energyWh, 0.1f)
        assertEquals(24f, t.whPerKm!!, 0.2f)
        assertEquals(0.5f, t.kmPerPercent!!, 0.01f)
    }

    @Test fun regenSubtractsAndGapsAreSkipped() {
        val t = TripTracker()
        t.update(0f, 80f, 70f, 10f, 0); t.update(0f, 80f, 70f, 10f, 3600_000)   // 1 h gap: not integrated
        assertEquals(0f, t.energyWh)
        t.update(0f, 80f, 70f, -10f, 3600_000 + 5000)                         // 5 s regen at 700 W
        assertEquals(-0.97f, t.energyWh, 0.01f)
    }

    @Test fun chargingStartsNewTrip() {
        val t = TripTracker()
        t.update(100f, 60f, null, null); t.update(120f, 40f, null, null)
        assertEquals(20f, t.km)
        t.update(120f, 90f, null, null)                                        // charged
        assertEquals(0f, t.km); assertEquals(0f, t.socUsed)
        t.update(125f, 88f, null, null)
        assertEquals(5f, t.km); assertEquals(2f, t.socUsed)
    }

    @Test fun resetAndSaveLoad() {
        val t = TripTracker()
        t.update(10f, 90f, 72f, 5f, 0); t.update(15f, 85f, 72f, 5f, 2000)
        val t2 = TripTracker(); t2.load(t.save())
        assertEquals(t.km, t2.km, 0.001f); assertEquals(t.energyWh, t2.energyWh, 0.01f)
        t.reset(); assertEquals(0f, t.km); assertEquals(0f, t.energyWh)
        assertNull(t.whPerKm)
    }
}
