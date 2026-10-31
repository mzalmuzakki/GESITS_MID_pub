package io.github.mzalmuzakki.gesitsmid


import kotlin.test.*

class ProtocolTest {
    @Test fun commands() {
        assertEquals("AT+SPD?\r\n", Mid.query("SPD"))
        assertEquals("AT+TIME=\"0705\"\r\n", Mid.time(7, 5))
        assertEquals("AT+TRIP=\"42.0\"\r\n", Mid.trip(42f))
        assertEquals("AT+NAV=\"Halo Medan          \"\r\n", Mid.nav("Halo Médan"))
        assertEquals(listOf("Belok kiri ke Jalan", "Jenderal Sudirman"), Mid.splitNav("Belok kiri ke Jalan Jenderal Sudirman"))
    }
    @Test fun parsing() {
        val t = Telemetry(); val p = LineParser()
        p.feed("SP".toByteArray()) { k, v -> t.put(k, v) }
        p.feed("D=42\r\nAT+PARK=1\r\nERR=0800\r\nVBAT=72.5\r\nIBAT=-10\r\nMODE=2\r\n".toByteArray()) { k, v -> t.put(k, v) }
        assertEquals(42f, t.f("SPD")); assertEquals("P", t.gear); assertEquals("SPORT", t.mode)
        assertEquals(-725f, t.powerW); assertEquals(0x800, t.errorMask)
        assertEquals(1, t.errors.size); assertTrue(t.errors[0].contains("Throttle"))
    }
}
