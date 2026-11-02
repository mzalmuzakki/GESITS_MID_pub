package io.github.mzalmuzakki.gesitsmid


import kotlin.test.*

class ProtocolTest {
    @Test fun commands() {
        assertEquals("AT+SPD?\r\n", Mid.query("SPD"))
        assertEquals("AT+TIME=\"0705\"\r\n", Mid.time(7, 5))
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
        val good = "AT+SOC=100\r\nAT+ODO=003791.6\r\nAT+IBAT=   1\r\nAT+ERR=0000\r\n"
        p.feed(good.toByteArray()) { k, v -> t.offer(k, v) }      // first answers: candidates
        p.feed(good.toByteArray()) { k, v -> t.offer(k, v) }      // second matching answers: confirmed
        p.feed("AT+VDO=003791.6\r\nAT+SD=012\r\n7\r\nAT+TINV003796.0\r\n".toByteArray()) { k, v -> t.offer(k, v) }
        assertEquals("100", t.raw["SOC"]); assertEquals("3791.6", t.raw["ODO"]); assertEquals("1", t.raw["IBAT"])
        assertNull(t.raw["VDO"]); assertNull(t.raw["SD"]); assertNull(t.raw["TINV"])
        assertTrue(t.errors.isEmpty())
    }

    @Test fun firstValueNeedsASecondReply() {
        val t = Telemetry()
        assertFalse(t.offer("SOC", "010"))              // a garbled first reply after connecting (real: 100)
        assertNull(t.f("SOC"))
        assertFalse(t.offer("SOC", "100"))              // not close to the candidate: new candidate
        assertTrue(t.offer("SOC", "100"))               // two that agree: confirmed
        assertEquals(100f, t.f("SOC"))
        t.clearLive()                                   // reconnect: nothing shown...
        assertNull(t.f("SOC"))
        assertTrue(t.offer("SOC", "099"))               // ...but the confirmed 100 still guards the next one
        assertFalse(t.offer("SOC", "008"))              // one odd reading: ignored
        assertEquals(99f, t.f("SOC"))
        assertTrue(t.offer("SPD", "042"))               // speed: shown at once
    }

    @Test fun realChangesAreFollowedGarbleIsNot() {
        val t = Telemetry()
        t.offer("TMOT", "0070"); t.offer("TMOT", "0070")
        for (x in listOf(74, 78, 82, 86, 90)) assertTrue(t.offer("TMOT", String.format("%04d", x)))   // rising motor: followed
        assertEquals(90f, t.f("TMOT"))
        t.offer("VBAT", "079"); t.offer("VBAT", "079")
        assertTrue(t.offer("VBAT", "068"))              // sag under load: followed
        assertFalse(t.offer("VBAT", "03")); assertFalse(t.offer("SOC", "8"))   // a character lost: wrong width
        t.offer("ODO", "003791.6"); t.offer("ODO", "003791.6")
        assertFalse(t.offer("ODO", "0037916"))          // the dot lost
        assertFalse(t.offer("ODO", "000379.6"))         // a digit lost but same width: far away, one reading
        assertEquals(3791.6f, t.f("ODO"))
        assertFalse(t.offer("PARK", "7")); assertFalse(t.offer("SOC", "abc"))
    }

    @Test fun anErrorThatComesAndGoesIsStillCaught() {
        val t = Telemetry()
        t.offer("ERR", "0000"); t.offer("ERR", "0000")
        assertFalse(t.offer("ERR", "0080")); assertTrue(t.errors.isEmpty())   // once: could be garble
        assertTrue(t.offer("ERR", "0000"))
        assertTrue(t.offer("ERR", "0080")); assertEquals(listOf("Under voltage"), t.errors)   // again: real
    }

    @Test fun zeroPaddingIsRemoved() {
        val t = Telemetry()
        fun twice(k: String, v: String) { t.offer(k, v); t.offer(k, v) }
        twice("SOC", "080"); twice("SPD", "000"); twice("TBAT", "0039"); twice("ODO", "003791.6"); twice("VBAT", "081")
        assertEquals("80", t.raw["SOC"]); assertEquals("0", t.raw["SPD"]); assertEquals("39", t.raw["TBAT"])
        assertEquals("3791.6", t.raw["ODO"]); assertEquals("81", t.raw["VBAT"])
        t.ibatSign = -1; t.offer("IBAT", "  -25"); assertEquals("25", t.raw["IBAT"])
    }

    @Test fun staleValuesCanBeTold() {
        val t = Telemetry()
        t.offer("SPD", "042", now = 1_000)
        assertTrue(t.fresh("SPD", 3_000, now = 3_000)); assertFalse(t.fresh("SPD", 3_000, now = 5_000))
        assertFalse(t.fresh("SOC", 3_000, now = 3_000))
    }
}
