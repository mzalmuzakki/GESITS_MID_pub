package io.github.mzalmuzakki.gesitsmid

import kotlin.test.*

class SafetyTest {
    @Test fun onlyKnownCommandsMayBeSent() {
        assertTrue(Traffic.allowed(Mid.query("SOC")))
        assertTrue(Traffic.allowed(Mid.VERSION_QUERY))
        assertTrue(Traffic.allowed(Mid.time(9, 5)))
        assertTrue(Traffic.allowed(Mid.nav("RANGE 112 km")))
        assertTrue(Traffic.allowed(Mid.nav("")))
        assertTrue(Traffic.allowed(Mid.maps("A".repeat(Traffic.PICTURE_MAX_CHARS))))
        // never: odometer, range, range override, unknown writes, oversized pictures, missing line end
        assertFalse(Traffic.allowed("AT+ODO=\"0\"\r\n"))
        assertFalse(Traffic.allowed("AT+ODO =\"0\"\r\n"))
        assertFalse(Traffic.allowed("AT+RANGE=\"123.0\"\r\n"))
        assertFalse(Traffic.allowed("AT+TRIP=\"50.0\"\r\n"))
        assertFalse(Traffic.allowed("AT+NUMBAT=\"2\"\r\n"))
        assertFalse(Traffic.allowed("AT+ARROW=\"1\"\r\n"))
        assertFalse(Traffic.allowed(Mid.maps("A".repeat(Traffic.PICTURE_MAX_CHARS + 1))))
        assertFalse(Traffic.allowed("AT+SOC?"))
        assertFalse(Traffic.allowed("AT+NAV=\"too short\"\r\n"))
        assertFalse(Traffic.allowed("AT+SOC?\r\nAT+ODO=\"0\"\r\n"))
    }

    @Test fun consoleOnlyAsks() {
        assertEquals("AT+SOC?\r\n", Traffic.console("at+soc ?"))
        assertEquals("AT+VER?\r\n", Traffic.console("VER?"))
        assertNull(Traffic.console("AT+RANGE =\"1\""))
        assertNull(Traffic.console("AT+ODO=\"0\""))
        assertNull(Traffic.console("AT+SOC?\nAT+ODO=1"))
        assertNull(Traffic.console(""))
    }

    @Test fun atMostTwoCommandsASecondAndQuietAfterPictures() {
        assertEquals(1500L, Traffic.nextAllowedAt(1000L, 0L))
        val pic = Mid.maps("A".repeat(4000))
        assertTrue(Traffic.quietAfterMs(pic) >= 1900L)
        assertEquals(0L, Traffic.quietAfterMs(Mid.query("SOC")))
        assertEquals(5000L, Traffic.nextAllowedAt(1000L, 5000L))
    }

    @Test fun writesOnlyWhileTheMidAnswers() {
        val l = Liveness()
        l.connected(0)
        assertFalse(l.canWrite(100))                 // nothing answered yet: questions only
        assertFalse(l.silent(19_000)); assertTrue(l.silent(20_001))
        l.answered(1_000)
        assertTrue(l.canWrite(2_000)); assertFalse(l.canWrite(4_001))   // silent 3 s: stop writing
        assertFalse(l.silent(9_000)); assertTrue(l.silent(9_001))
    }

    @Test fun silenceWhileMovingIsAHangStoppedWaitsForTheLinkToDrop() {
        val l = Liveness()
        l.connected(0); l.answered(10_000)
        // moving when the answers stopped: a hang once the link stayed up 15 s
        assertEquals(Liveness.Verdict.WAIT, l.verdict(20_000, lastMovingAt = 11_000))
        assertEquals(Liveness.Verdict.HUNG, l.verdict(25_001, lastMovingAt = 11_000))
        assertTrue(l.movingWhenItStopped(lastMovingAt = 11_000))
        // stopped 5 s before the answers stopped (pulled up, switched off): not moving
        assertFalse(l.movingWhenItStopped(lastMovingAt = 5_000))
        assertEquals(Liveness.Verdict.WAIT, l.verdict(30_000, lastMovingAt = 5_000))
        assertEquals(Liveness.Verdict.HUNG, l.verdict(40_001, lastMovingAt = 5_000))   // link still up after 30 s: on
        assertEquals(Liveness.Verdict.WAIT, l.verdict(39_000, lastMovingAt = 0))
    }

    @Test fun onlyKnownQuestions() {
        assertTrue(Traffic.allowed("AT+NUMBAT?\r\n"))
        assertFalse(Traffic.allowed("AT+RESET?\r\n")); assertFalse(Traffic.allowed("AT+MAPEND?\r\n"))
        assertNull(Traffic.console("AT+RESET?"))
    }

    @Test fun theAppsOwnLongWriteIsNotTheMidsSilence() {
        val l = Liveness()
        l.connected(0); l.answered(1_000)
        l.busy(12_000)                                   // a picture took until 10 s to write, +2 s grace
        assertFalse(l.silent(11_000))
        assertTrue(l.silent(12_001))
        assertTrue(l.silenceMs(15_000) <= 3_000)
    }
}

