package io.github.mzalmuzakki.gesitsmid

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.*

class EnergyRangeTest {
    /** Simulated bike: battery of [capWh], riding at [kmh] using [whPerKm]; SOC whole %, ODO 0.1 km. */
    private class Sim(val capWh: Double, var energy: Double, var odo: Double = 1000.0) {
        val soc get() = floor(energy / capWh * 100.0).toFloat()
        val odoShown get() = (floor(odo * 10.0) / 10.0).toFloat()
    }

    private fun ride(r: EnergyRange, s: Sim, km: Double, whPerKm: Double, t0: Long, kmh: Double = 36.0): Long {
        var t = t0
        val vbat = 72.0
        val watts = whPerKm * kmh
        val seconds = (km / kmh * 3600).toInt()
        repeat(seconds) {
            s.energy -= watts / 3600.0; s.odo += kmh / 3600.0; t += 1000
            r.update(s.soc, vbat.toFloat(), (watts / vbat).toFloat(), s.odoShown, false, t)
        }
        return t
    }

    @Test fun startsAtOriginalBattery() {
        assertEquals(50f, EnergyRange().rangeKm(100f), 0.01f)
    }

    @Test fun learnsBigBatteryAndConsumptionWhileRiding() {
        val r = EnergyRange()
        val s = Sim(capWh = 3000.0, energy = 2900.0)
        ride(r, s, km = 25.0, whPerKm = 25.0, t0 = 0)
        assertTrue(r.capacityLearned); assertTrue(r.consumptionLearned)
        assertTrue(abs(r.capacityWh - 3000f) < 120f, "capacity ${r.capacityWh}")
        assertTrue(abs(r.whPerKm - 25f) < 1.5f, "use ${r.whPerKm}")
        assertTrue(abs(r.rangeKm(100f) - 120f) < 8f, "range ${r.rangeKm(100f)}")
    }

    @Test fun consumptionAdaptsQuickly() {
        val r = EnergyRange()
        val s = Sim(capWh = 3000.0, energy = 2900.0)
        var t = ride(r, s, km = 15.0, whPerKm = 25.0, t0 = 0)
        t = ride(r, s, km = 15.0, whPerKm = 40.0, t0 = t)   // faster / heavier riding
        assertTrue(abs(r.whPerKm - 40f) < 2f, "use ${r.whPerKm}")
    }

    @Test fun learnsCapacityWhileCharging() {
        val r = EnergyRange()
        val s = Sim(capWh = 3200.0, energy = 640.0)          // 20 %
        var t = 0L
        repeat(3 * 3600) {                                   // 1000 W charger for up to 3 h, stop at 80 %
            if (s.soc < 80f) s.energy += 1000.0 / 3600.0
            t += 1000
            r.update(s.soc, 80f, if (s.soc < 80f) -12.5f else 0f, s.odoShown, true, t)
        }
        assertTrue(r.capacityLearned)
        assertTrue(abs(r.capacityWh - 3200f) < 120f, "capacity ${r.capacityWh}")
        assertFalse(r.consumptionLearned)                     // charging says nothing about riding
    }

    @Test fun gapsAndSwapsAreIgnored() {
        val r = EnergyRange()
        r.update(80f, 72f, 10f, 100f, false, 0)
        r.update(79f, 72f, 10f, 100f, false, 1000)
        r.update(60f, 72f, 10f, 100f, false, 3_600_000)      // an hour later: energy unknown
        assertFalse(r.hasData)
        r.update(30f, 72f, 10f, 100f, false, 3_601_000)       // jump without the energy: not plausible
        assertFalse(r.hasData)
    }

    @Test fun saveAndLoad() {
        val r = EnergyRange()
        ride(r, Sim(3000.0, 2900.0), 20.0, 28.0, 0)
        val r2 = EnergyRange(); r2.load(r.save())
        assertEquals(r.capacityWh, r2.capacityWh, 10f)   // saved rounded to 0.1 Wh per step
        assertEquals(r.whPerKm, r2.whPerKm, 0.2f)
        r2.load("garbage"); assertFalse(r2.hasData)
    }
}
