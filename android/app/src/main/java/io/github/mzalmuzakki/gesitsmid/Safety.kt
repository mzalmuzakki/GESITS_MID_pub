package io.github.mzalmuzakki.gesitsmid

/**
 * Rules for everything the app sends to the MID. Pure Kotlin, unit-tested (SafetyTest).
 *
 * Why: on a real G1 (MID 11-50-09-G1) the MID froze - speedometer and turn signals stuck until the
 * ignition was switched off and on - after a few minutes of heavy traffic (pictures, text, many
 * questions), and an unknown write (AT+RANGE) froze it too. So:
 *  - one command at a time, at least [MIN_GAP_MS] apart: at most 2 a second, questions and writes
 *    together, whatever screen is open;
 *  - only commands from a fixed list ever leave the phone ([allowed]);
 *  - a picture leaves the MID alone for a while afterwards ([quietAfterMs]) and is never bigger than
 *    [PICTURE_MAX_CHARS] or sent more often than [PICTURE_MIN_INTERVAL_MS];
 *  - writes only while the MID is answering ([Liveness]).
 */
object Traffic {
    const val MIN_GAP_MS = 500L
    const val ANSWER_WAIT_MS = 300L
    const val PICTURE_MIN_INTERVAL_MS = 10_000L
    /** GRID Mobile's own MID pictures are about 4000 characters; 7000 is where the MID is said to freeze. */
    const val PICTURE_MAX_CHARS = 4000

    /** The only names ever asked: what the MID was seen to answer on a real G1. */
    val KNOWN_QUESTIONS: Set<String> = Mid.POLL_KEYS.toSet() + setOf("VER", "NUMBAT", "FWD", "CBAT", "TRT")
    private val QUESTION = Regex("""AT\+([A-Z0-9]{1,12})\?\r\n""")
    private val TIME = Regex("""AT\+TIME="\d{4}"\r\n""")
    private val NAV = Regex("""AT\+NAV="[ -!#-~]{20}"\r\n""")
    private val MAPS = Regex("""AT\+MAPS="[A-Za-z0-9+/=]{1,4000}"\r\n""")

    /**
     * The only commands that may be sent: questions (AT+KEY?), the clock, the 20-character text line and
     * a picture of at most [PICTURE_MAX_CHARS]. Never the odometer, range or any other write.
     */
    fun allowed(cmd: String): Boolean = isQuestion(cmd) || TIME.matches(cmd) || NAV.matches(cmd) || MAPS.matches(cmd)

    /** A question about a known value (AT+SOC?): the only thing sent while the MID is silent or hung. */
    fun isQuestion(cmd: String): Boolean = QUESTION.matchEntire(cmd)?.groupValues?.get(1) in KNOWN_QUESTIONS

    /** A command typed in the console: only questions, written any way ("at+soc ?" -> "AT+SOC?\r\n"). */
    fun console(text: String): String? {
        val c = text.filterNot { it.isWhitespace() }.uppercase()
        val cmd = (if (c.startsWith("AT+")) c else "AT+$c") + "\r\n"
        return cmd.takeIf { isQuestion(it) }
    }

    /** How long the MID is left alone after [cmd]: a picture needs time to be received and drawn. */
    fun quietAfterMs(cmd: String): Long = if (cmd.startsWith("AT+MAPS")) 1000L + cmd.length / 4 else 0L

    /** When the next command may go, given the last one. */
    fun nextAllowedAt(lastSendAt: Long, quietUntil: Long): Long = maxOf(lastSendAt + MIN_GAP_MS, quietUntil)
}

/**
 * Is the MID answering? Writes (clock, text, picture) only go out while it has answered a question in the
 * last [WRITE_WINDOW_MS]. Silence decides what to do next ([verdict]).
 */
class Liveness {
    companion object {
        const val WRITE_WINDOW_MS = 3_000L
        const val SILENT_AFTER_MS = 8_000L          // ~13 unanswered questions while riding
        const val FIRST_ANSWER_MS = 20_000L         // after connecting
        const val MOVING_HANG_MS = 15_000L          // moving when it went silent, link still up: hung
        const val STOPPED_HANG_MS = 30_000L         // stopped: Bluetooth should drop well before this when switched off
    }

    var connectedAt = 0L; private set
    var lastAnswerAt = 0L; private set
    private var graceUntil = 0L

    fun connected(now: Long) { connectedAt = now; lastAnswerAt = 0L; graceUntil = 0L }
    fun answered(now: Long) { lastAnswerAt = now }

    /** The app itself kept the link busy until [until] (a long picture write): that time isn't the MID's silence. */
    fun busy(until: Long) { graceUntil = maxOf(graceUntil, until) }

    fun canWrite(now: Long): Boolean = lastAnswerAt > 0L && now - lastAnswerAt < WRITE_WINDOW_MS

    /** True when the MID has gone silent on this connection. */
    fun silent(now: Long): Boolean =
        now >= graceUntil && (if (lastAnswerAt > 0L) now - lastAnswerAt > SILENT_AFTER_MS else now - connectedAt > FIRST_ANSWER_MS)

    /** Milliseconds of silence on this connection (since the last answer, or since connecting). */
    fun silenceMs(now: Long): Long = now - maxOf(lastAnswerAt, connectedAt, graceUntil)

    enum class Verdict {
        /** The MID has probably hung: stay quiet (one question now and then) and warn. */
        HUNG,
        /** Not sure yet: stopped, link still up. Ask nothing but the odd question and wait. */
        WAIT,
    }

    /**
     * What silence means (the app has already gone quiet after 8 s). Switching the ignition off drops the
     * Bluetooth link - but on some phones only after up to ~20 s. So: if the bike was moving when the answers
     * stopped, it counts as a hang once the link has stayed up for 15 s of silence; if it was stopped, after
     * 30 s (also on a bench test). Until then: wait.
     */
    fun verdict(now: Long, lastMovingAt: Long): Verdict =
        if (movingWhenItStopped(lastMovingAt) && silenceMs(now) >= MOVING_HANG_MS) Verdict.HUNG
        else if (silenceMs(now) >= STOPPED_HANG_MS) Verdict.HUNG
        else Verdict.WAIT

    /**
     * The bike was moving when the MID's answers stopped: a speed above 3 km/h was current (at most 2.5 s old)
     * within 2.5 s of the last answer. Also used when the link drops: dropping while moving is not "switched off".
     */
    fun movingWhenItStopped(lastMovingAt: Long): Boolean =
        lastMovingAt > 0L && lastAnswerAt > 0L && lastMovingAt >= lastAnswerAt - 2_500L
}
