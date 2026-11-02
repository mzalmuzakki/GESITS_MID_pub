package io.github.mzalmuzakki.gesitsmid

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Gesits G1 MID text protocol. Same as docs/PROTOCOL.md and gesits/protocol.py. */
object Mid {
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    val FFF2: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val BLE_NAME = "GESITS"

    const val NAV_MAX = 20
    const val MAPS_MAX_CHARS = Traffic.PICTURE_MAX_CHARS
    /** Picture size for AT+MAPS. GRID Mobile's built-in MID pictures are all 320x128, which is
     *  the visible map area; bigger pictures (350x220) spill under the MID's own widgets. */
    const val FRAME_W = 320
    const val FRAME_H = 128

    /** Keys asked in turn, the most overdue first (periods in MidService.pollPeriod, at most 2 commands a second). */
    val POLL_KEYS = listOf("SPD", "IBAT", "VBAT", "SOC", "ODO", "MODE", "PARK", "ERR", "TBAT", "TMOT", "TINV", "SOH", "RANGE")

    fun query(key: String) = "AT+$key?\r\n"
    fun time(hour: Int, minute: Int) = String.format(Locale.US, "AT+TIME=\"%02d%02d\"\r\n", hour, minute)
    fun maps(base64Jpeg: String) = "AT+MAPS=\"$base64Jpeg\"\r\n"


    /** MID firmware version (GRID Mobile shows it as "MID ver ..."). */
    const val VERSION_QUERY = "AT+VER?\r\n"

    /** Today's date as GRID Mobile shows it on the MID text line, e.g. "31 Oktober 2026". */
    fun dateText(d: java.util.Date = java.util.Date()): String =
        java.text.SimpleDateFormat("d MMMM yyyy", Locale("id", "ID")).format(d)

    /** AT+NAV with exactly 20 chars: the MID only redraws changed characters. */
    fun nav(text: String): String = "AT+NAV=\"" + navAscii(text).take(NAV_MAX).padEnd(NAV_MAX) + "\"\r\n"

    fun navAscii(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.map { if (it in ' '..'~' && it != '"') it else ' ' }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()
    }

    /** Split text into parts of <= 20 chars at word boundaries (for rotating AT+NAV). */
    fun splitNav(text: String): List<String> {
        val words = navAscii(text).split(' ').filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        var line = ""
        for (w in words) {
            val cand = if (line.isEmpty()) w else "$line $w"
            if (cand.length <= NAV_MAX) line = cand
            else { if (line.isNotEmpty()) parts += line; line = w.take(NAV_MAX) }
        }
        if (line.isNotEmpty()) parts += line
        return parts
    }

    val ERROR_BITS = mapOf(
        0 to "Inverter over temperature", 1 to "Motor over temperature", 2 to "Motor fault",
        3 to "Hall sensor fault", 4 to "Throttle fault", 5 to "Controller fault",
        6 to "Over voltage", 7 to "Under voltage", 11 to "Throttle signal abnormal (unofficial)",
    )
}

/**
 * What a believable reply looks like, per key (formats seen on a real G1, MID 11-50-09-G1: SOC=100,
 * ODO=003791.6, TINV=0046, IBAT=  -1, ERR=0000 ...). The MID sometimes drops characters, giving lines like
 * "AT+VDO=003791.6" or "AT+TINV003796.0"; those must never become a reading.
 */
object Replies {
    private val INT = Regex("""-?\d{1,4}""")
    private val DEC = Regex("""-?\d{1,7}(\.\d{1,2})?""")
    private class Rule(val re: Regex, val min: Float, val max: Float, val confirmJump: Float)

    // confirmJump: a change bigger than this from the last accepted value needs a second reply close to the
    // first (or three replies in a row) before it counts. Sized to what really changes between two answers.
    private val rules = mapOf(
        "SPD" to Rule(INT, 0f, 200f, 40f),
        "SOC" to Rule(INT, 0f, 100f, 2f),
        "SOH" to Rule(INT, 0f, 100f, 0f),
        "ODO" to Rule(DEC, 0f, 9_999_999f, 1f),
        "TINV" to Rule(INT, -40f, 150f, 10f), "TMOT" to Rule(INT, -40f, 150f, 10f), "TBAT" to Rule(INT, -40f, 100f, 10f),
        "VBAT" to Rule(DEC, 20f, 150f, 8f),
        "IBAT" to Rule(DEC, -300f, 400f, 80f),
        "TRT" to Rule(INT, 0f, 1000f, Float.MAX_VALUE),
        "MODE" to Rule(Regex("[0-9]"), 0f, 9f, 0f),
        "PARK" to Rule(Regex("[01]"), 0f, 1f, 0f),
        "RANGE" to Rule(DEC, 0f, 999f, 2f),
        "CBAT" to Rule(Regex("""\d{1,6}"""), 0f, 999_999f, Float.MAX_VALUE),
    )

    /** null = not believable; else the cleaned value. ERR is hex; VER and unknown keys pass as they are. */
    fun clean(key: String, value: String): String? {
        val v = value.trim('"', ' ')
        if (key == "ERR") return v.takeIf { Regex("[0-9A-Fa-f]{1,4}").matches(it) }
        val r = rules[key] ?: return v.takeIf { it.isNotEmpty() && key in OTHER_KEYS }
        if (!r.re.matches(v)) return null
        val f = v.toFloatOrNull() ?: return null
        if (f !in r.min..r.max) return null
        return if (key == "CBAT") v else num(f, v)   // "080" -> "80", "003791.6" -> "3791.6"
    }

    /** Other answers worth keeping as they are (the console log shows every line anyway). */
    private val OTHER_KEYS = setOf("VER", "NUMBAT", "FWD")

    /** A number without the MID's zero padding. */
    fun num(f: Float, original: String = ""): String =
        if (f == Math.round(f).toFloat() && !original.contains('.')) Math.round(f).toString()
        else java.math.BigDecimal(original.ifEmpty { f.toString() }).toPlainString()

    /**
     * Keys the MID always sends with the same number of characters (zero padded: SOC=080, ODO=003791.6).
     * Once a width is seen, a reply of another width has lost or gained a character and is dropped.
     */
    val WIDTH_LOCKED = setOf("SPD", "SOC", "SOH", "ODO", "TINV", "TMOT", "TBAT", "VBAT", "ERR", "RANGE", "MODE", "PARK")

    /** How big a change must be before it needs a second, matching reply (0 = any change). */
    fun confirmJump(key: String): Float = if (key == "ERR") 0f else rules[key]?.confirmJump ?: Float.MAX_VALUE
}

/** Latest values received from the bike. Thread-safe. */
class Telemetry {
    /** Values shown and used right now (cleared when the link drops or the MID stops answering). */
    val raw = ConcurrentHashMap<String, String>()
    private val times = ConcurrentHashMap<String, Long>()
    @Volatile var lastUpdate = 0L
    // Kept across reconnects (cleared by [resetAll]): the last confirmed value per key, its width once two
    // confirmed answers agree on it, and the latest unconfirmed candidate.
    private val refs = HashMap<String, String>()
    private val widths = HashMap<String, Int>()
    private val widthSeen = HashMap<String, Int>()            // width of the last accepted answer (not locked yet)
    private val altWidth = HashMap<String, Pair<Int, Int>>()  // another width seen in a row: (width, count)
    private val pending = HashMap<String, String>()
    private val errHistory = ArrayDeque<String>()             // last 4 ERR answers
    /** -1 if this bike's battery reports current drawn as negative (see CurrentSign); IBAT is stored as "drawn > 0". */
    @Volatile var ibatSign = 1
    /** IBAT exactly as the MID sent it, for learning the sign. */
    @Volatile var ibatRaw: Float? = null

    fun put(key: String, value: String, now: Long = System.currentTimeMillis()) { raw[key] = value; times[key] = now; lastUpdate = now }

    /** Milliseconds since [key] was last received, or null if it isn't known now. */
    fun ageMs(key: String, now: Long = System.currentTimeMillis()): Long? = times[key]?.let { now - it }
    fun fresh(key: String, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean = (ageMs(key, now) ?: Long.MAX_VALUE) <= maxAgeMs

    /** Forget what is shown (link lost / MID silent) so no screen keeps showing old values as if live. */
    fun clearLive() { raw.clear(); times.clear(); ibatRaw = null }

    /** Drop single values that stopped coming in (older than [maxAgeMs] for that key). */
    fun expire(now: Long, maxAgeMs: (String) -> Long) {
        for (k in times.keys.toList()) if (now - (times[k] ?: now) > maxAgeMs(k)) {
            raw.remove(k); times.remove(k); if (k == "IBAT") ibatRaw = null
        }
    }

    /** Forget everything, including what answers are expected to look like (new bike, data deleted). */
    @Synchronized fun resetAll() {
        clearLive(); refs.clear(); widths.clear(); widthSeen.clear(); altWidth.clear(); pending.clear(); errHistory.clear()
    }

    private fun close(key: String, a: String, b: String, limit: Float): Boolean {
        if (key == "ERR" || limit == 0f) return a == b
        val x = a.toFloatOrNull() ?: return a == b
        val y = b.toFloatOrNull() ?: return false
        // current: a lost "-" (-12 -> 12) is a flip, not a small change
        if (key == "IBAT" && x * y < 0f && kotlin.math.abs(x) > 3f && kotlin.math.abs(y) > 3f) return false
        return kotlin.math.abs(x - y) <= limit
    }

    /**
     * A reply from the MID. Kept only if it is well-formed, plausible and has the usual width. A value far
     * from the last confirmed one (or the very first one for a key) needs the next reply to agree with it -
     * one garbled answer never counts. An error code counts once it shows up twice in the last 4 answers
     * (so one that comes and goes is caught, scattered garble isn't). Returns true if it was taken.
     */
    @Synchronized
    fun offer(key: String, value: String, now: Long = System.currentTimeMillis()): Boolean {
        val trimmed = value.trim('"', ' ')
        val v = Replies.clean(key, value) ?: return false
        if (!widthOk(key, trimmed.length)) return false
        val limit = Replies.confirmJump(key)
        if (key == "ERR") {
            errHistory.addLast(v); while (errHistory.size > 4) errHistory.removeFirst()
            if (refs[key] != v && errHistory.count { it == v } < 2) return false
        } else if (limit != Float.MAX_VALUE) {
            val ref = refs[key]
            val p = pending[key]
            when {
                ref != null && close(key, ref, v, limit) -> pending.remove(key)
                p != null && close(key, p, v, limit) -> pending.remove(key)
                else -> { pending[key] = v; return false }
            }
        }
        refs[key] = v
        noteWidth(key, trimmed.length)
        if (key == "IBAT") {
            val a = v.toFloat(); ibatRaw = a
            put(key, if (ibatSign < 0) Replies.num(-a, if (v.startsWith("-")) v.substring(1) else "-$v") else v, now)
        } else put(key, v, now)
        return true
    }

    /**
     * Width check for zero-padded keys. Nothing is locked until two accepted answers had the same width. A locked
     * width that turns out wrong (e.g. learned from an odd answer) is replaced after 3 answers in a row with a new
     * width - those three still have to pass the value checks.
     */
    private fun widthOk(key: String, w: Int): Boolean {
        if (key !in Replies.WIDTH_LOCKED) return true
        val locked = widths[key] ?: return true
        if (w == locked) { altWidth.remove(key); return true }
        val (aw, n) = altWidth[key] ?: (w to 0)
        val count = if (aw == w) n + 1 else 1
        altWidth[key] = w to count
        if (count >= 3) { widths.remove(key); widthSeen.remove(key); altWidth.remove(key); return true }
        return false
    }

    private fun noteWidth(key: String, w: Int) {
        if (key !in Replies.WIDTH_LOCKED || widths.containsKey(key)) return
        if (widthSeen[key] == w) widths[key] = w else widthSeen[key] = w
    }

    fun f(key: String): Float? = raw[key]?.trim('"', ' ')?.toFloatOrNull()

    val mode: String? get() = when (f("MODE")?.toInt()) { 0 -> "ECO"; 1 -> "URBAN"; 2 -> "SPORT"; null -> null; else -> "?" }
    val gear: String? get() = when (raw["PARK"]?.trim('"', ' ')?.uppercase()) { "1", "P", "PARK" -> "P"; "0", "D", "DRIVE" -> "D"; else -> null }
    val powerW: Float? get() { val v = f("VBAT") ?: return null; val i = f("IBAT") ?: return null; return v * i }
    val errorMask: Int? get() = raw["ERR"]?.trim('"', ' ')?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16)
    val errors: List<String> get() {
        val m = errorMask ?: return emptyList()
        return (0 until 16).filter { ((m shr it) and 1) == 1 }.map { Mid.ERROR_BITS[it] ?: "Unknown error bit $it" }
    }
}

/** Joins raw bytes into "KEY=value" lines. Not thread-safe: use from one thread. */
class LineParser {
    private val buf = StringBuilder()

    /** Drop a half line left over from a previous connection. */
    fun reset() = buf.setLength(0)

    /** [out] gets KEY=value lines; [raw] gets every non-empty line as received (for the console). */
    fun feed(data: ByteArray, raw: (String) -> Unit = {}, out: (String, String) -> Unit) {
        buf.append(String(data, Charsets.US_ASCII))
        while (true) {
            val nl = buf.indexOf("\n")
            if (nl < 0) break
            var line = buf.substring(0, nl).trim()
            buf.delete(0, nl + 1)
            if (line.isNotEmpty()) raw(line)
            if (line.startsWith("AT+")) line = line.substring(3)
            val eq = line.indexOf('=')
            if (eq > 0) out(line.substring(0, eq).trim().uppercase(), line.substring(eq + 1).trim())
        }
        if (buf.length > 4096) buf.setLength(0)
    }
}
