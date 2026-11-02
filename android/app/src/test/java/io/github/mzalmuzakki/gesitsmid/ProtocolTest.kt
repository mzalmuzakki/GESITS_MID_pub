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

    @Test fun garbledRepliesFromARealG1AreIgnored() {
        val t = Telemetry()
        val p = LineParser()
        val bytes = "AT+SOC=100\r\nAT+ODO=003791.6\r\nAT+VDO=003791.6\r\nAT+SD=012\r\n7\r\nAT+TINV003796.0\r\nAT+IBAT=   1\r\nAT+ERR=0000\r\n"
        p.feed(bytes.toByteArray()) { k, v -> t.offer(k, v) }
        assertEquals("100", t.raw["SOC"]); assertEquals("3791.6", t.raw["ODO"]); assertEquals("1", t.raw["IBAT"])
        assertNull(t.raw["VDO"]); assertNull(t.raw["SD"]); assertNull(t.raw["TINV"])
        assertTrue(t.errors.isEmpty())
    }

    @Test fun bigJumpsNeedASecondMatchingReply() {
        val t = Telemetry()
        assertTrue(t.offer("SOC", "100"))
        assertFalse(t.offer("SOC", "2"))            // a single odd reading: ignored
        assertEquals(100f, t.f("SOC"))
        assertTrue(t.offer("SOC", "99"))            // normal 1 % step: taken at once
        assertTrue(t.offer("ERR", "0000"))
        assertFalse(t.offer("ERR", "0080")); assertTrue(t.errors.isEmpty())   // one "Under voltage": ignored
        assertTrue(t.offer("ERR", "0080")); assertEquals(listOf("Under voltage"), t.errors)   // twice: real
        assertFalse(t.offer("SOC", "abc")); assertFalse(t.offer("SPD", "1234")); assertFalse(t.offer("PARK", "7"))
    }

    @Test fun zeroPaddingIsRemoved() {
        val t = Telemetry()
        t.offer("SOC", "080"); t.offer("SPD", "000"); t.offer("TBAT", "0039"); t.offer("ODO", "003791.6"); t.offer("VBAT", "081")
        assertEquals("80", t.raw["SOC"]); assertEquals("0", t.raw["SPD"]); assertEquals("39", t.raw["TBAT"])
        assertEquals("3791.6", t.raw["ODO"]); assertEquals("81", t.raw["VBAT"])
        t.ibatSign = -1; t.offer("IBAT", "  -25"); assertEquals("25", t.raw["IBAT"])
    }
}

