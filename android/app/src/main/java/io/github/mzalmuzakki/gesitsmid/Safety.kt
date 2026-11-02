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

    private val QUESTION = Regex("""AT\+[A-Z0-9]{1,12}\?\r\n""")
    private val TIME = Regex("""AT\+TIME="\d{4}"\r\n""")
    private val NAV = Regex("""AT\+NAV="[ -!#-~]{20}"\r\n""")
    private val MAPS = Regex("""AT\+MAPS="[A-Za-z0-9+/=]{1,4000}"\r\n""")

    /**
     * The only commands that may be sent: questions (AT+KEY?), the clock, the 20-character text line and
     * a picture of at most [PICTURE_MAX_CHARS]. Never the odometer, range or any other write.
     */
    fun allowed(cmd: String): Boolean =
        QUESTION.matches(cmd) || TIME.matches(cmd) || NAV.matches(cmd) || MAPS.matches(cmd)

    /** A command typed in the console: only questions, written any way ("at+soc ?" -> "AT+SOC?\r\n"). */
    fun console(text: String): String? {
        val c = text.filterNot { it.isWhitespace() }.uppercase()
        val cmd = (if (c.startsWith("AT+")) c else "AT+$c") + "\r\n"
        return cmd.takeIf { QUESTION.matches(it) }
    }

    /** How long the MID is left alone after [cmd]: a picture needs time to be received and drawn. */
    fun quietAfterMs(cmd: String): Long = if (cmd.startsWith("AT+MAPS")) 1000L + cmd.length / 4 else 0L

    /** When the next command may go, given the last one. */
    fun nextAllowedAt(lastSendAt: Long, quietUntil: Long): Long = maxOf(lastSendAt + MIN_GAP_MS, quietUntil)
}

/**
 * Is the MID answering? Writes (clock, text, picture) only go out while it has answered a question in the
 * last [WRITE_WINDOW_MS]; questions alone are sent otherwise. Silence decides what to do next.
 */
class Liveness {
    companion object {
        const val WRITE_WINDOW_MS = 3_000L
        const val SILENT_AFTER_MS = 8_000L          // ~16 unanswered questions at 2 a second
        const val FIRST_ANSWER_MS = 20_000L         // after connecting
    }

    var connectedAt = 0L; private set
    var lastAnswerAt = 0L; private set

    fun connected(now: Long) { connectedAt = now; lastAnswerAt = 0L }
    fun answered(now: Long) { lastAnswerAt = now }

    fun canWrite(now: Long): Boolean = lastAnswerAt > 0L && now - lastAnswerAt < WRITE_WINDOW_MS

    /** True when the MID has gone silent on this connection. */
    fun silent(now: Long): Boolean =
        if (lastAnswerAt > 0L) now - lastAnswerAt > SILENT_AFTER_MS else now - connectedAt > FIRST_ANSWER_MS

    enum class Verdict {
        /** The bike was moving or in Drive: the MID has probably hung. Stay silent and warn. */
        HUNG,
        /** Parked: most likely the ignition was switched off. Drop the link quietly and retry slowly. */
        BIKE_OFF,
    }

    /** What silence means, from the last confirmed speed and gear. */
    fun verdict(lastSpeed: Float?, lastGear: String?): Verdict =
        if ((lastSpeed ?: 0f) > 3f || lastGear == "D") Verdict.HUNG else Verdict.BIKE_OFF
}
