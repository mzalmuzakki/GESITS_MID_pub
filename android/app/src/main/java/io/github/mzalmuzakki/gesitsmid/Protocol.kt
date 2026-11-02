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
    const val MAPS_MAX_CHARS = 7000
    /** Picture size for AT+MAPS. GRID Mobile's built-in MID pictures are all 320x128, which is
     *  the visible map area; bigger pictures (350x220) spill under the MID's own widgets. */
    const val FRAME_W = 320
    const val FRAME_H = 128

    /** One key is polled every 200 ms, round robin. */
    val POLL_KEYS = listOf("SPD", "IBAT", "VBAT", "SOC", "ODO", "MODE", "PARK", "ERR", "TBAT", "TMOT", "TINV", "SOH", "RANGE")

    fun query(key: String) = "AT+$key?\r\n"
    fun time(hour: Int, minute: Int) = String.format(Locale.US, "AT+TIME=\"%02d%02d\"\r\n", hour, minute)
    fun trip(km: Float) = String.format(Locale.US, "AT+TRIP=\"%.1f\"\r\n", km)
    fun maps(base64Jpeg: String) = "AT+MAPS=\"$base64Jpeg\"\r\n"

    /** Sent once on connect by GRID Mobile (meaning unknown - kept for compatibility). */
    const val ARROW_ON = "AT+ARROW=\"1\"\r\n"
    /** Guesses for a battery cycle-count query; asked once per connection, shown if the MID answers. */
    val CYCLE_KEYS = listOf("CYCLE", "CYC", "CYCLES", "NCYC", "BCYC", "CHGCYC")

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

    /**
     * Read-only queries for the "probe" button: names seen in either app plus plausible guesses.
     * Only "AT+NAME?" is sent - never a write. Unknown names just get an error reply.
     */
    val PROBE_KEYS = listOf(
        "SOC2", "NUMBAT", "FWD", "CBAT", "TRIP", "TIME", "NAV", "MAPS", "MAPON", "RPM", "PWR", "CRZ", "REM",
        "VER", "VERSION", "FW", "HW", "SW", "SN", "ID", "NAME", "MODEL", "VIN", "DATE", "BT", "BLE", "MAC",
        "LIGHT", "BRIGHT", "THEME", "LANG", "UNIT", "MENU", "PAGE", "SCREEN", "LAYOUT", "LOGO", "MODE2",
        "RANGE", "AVG", "ECO", "KM", "TEMP", "VOLT", "CURR", "CAP", "CYCLE", "CHG", "HELP", "LIST", "ALL",
    )

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

    // confirmJump: a change bigger than this is only accepted when the next reply says the same.
    private val rules = mapOf(
        "SPD" to Rule(INT, 0f, 200f, Float.MAX_VALUE),
        "SOC" to Rule(INT, 0f, 100f, 1f),
        "SOH" to Rule(INT, 0f, 100f, 0f),
        "ODO" to Rule(DEC, 0f, 9_999_999f, 0.5f),
        "TINV" to Rule(INT, -40f, 150f, 3f), "TMOT" to Rule(INT, -40f, 150f, 3f), "TBAT" to Rule(INT, -40f, 100f, 3f),
        "VBAT" to Rule(DEC, 20f, 150f, 5f),
        "IBAT" to Rule(DEC, -300f, 400f, Float.MAX_VALUE),
        "TRT" to Rule(INT, 0f, 1000f, Float.MAX_VALUE),
        "MODE" to Rule(Regex("[0-9]"), 0f, 9f, 0f),
        "PARK" to Rule(Regex("[01]"), 0f, 1f, 0f),
        "RANGE" to Rule(DEC, 0f, 999f, 0f),
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
    private val OTHER_KEYS = setOf("VER", "NUMBAT", "FWD") + Mid.CYCLE_KEYS

    /** A number without the MID's zero padding. */
    fun num(f: Float, original: String = ""): String =
        if (f == Math.round(f).toFloat() && !original.contains('.')) Math.round(f).toString()
        else java.math.BigDecimal(original.ifEmpty { f.toString() }).toPlainString()

    /** How big a change must be before it needs a second, matching reply (0 = any change). */
    fun confirmJump(key: String): Float = if (key == "ERR") 0f else rules[key]?.confirmJump ?: Float.MAX_VALUE
}

/** Latest values received from the bike. Thread-safe. */
class Telemetry {
    val raw = ConcurrentHashMap<String, String>()
    @Volatile var lastUpdate = 0L
    private val pending = ConcurrentHashMap<String, String>()
    /** -1 if this bike's battery reports current drawn as negative (see CurrentSign); IBAT is stored as "drawn > 0". */
    @Volatile var ibatSign = 1
    /** IBAT exactly as the MID sent it, for learning the sign. */
    @Volatile var ibatRaw: Float? = null

    fun put(key: String, value: String) { raw[key] = value; lastUpdate = System.currentTimeMillis() }

    /**
     * A reply from the MID: kept only if it is well-formed and plausible, and a big jump (battery %,
     * error code, odometer...) only once a second reply agrees. Returns true if it was taken.
     */
    fun offer(key: String, value: String): Boolean {
        val v = Replies.clean(key, value) ?: return false
        val cur = raw[key]
        val limit = Replies.confirmJump(key)
        if (cur != null && cur != v && limit != Float.MAX_VALUE) {
            val a = cur.toFloatOrNull(); val b = v.toFloatOrNull()
            val big = if (a != null && b != null && key != "ERR") kotlin.math.abs(a - b) > limit else true
            if (big && pending[key] != v) { pending[key] = v; return false }
        }
        pending.remove(key)
        if (key == "IBAT") {
            val a = v.toFloat(); ibatRaw = a
            put(key, if (ibatSign < 0) Replies.num(-a, if (v.startsWith("-")) v.substring(1) else "-$v") else v)
        } else put(key, v)
        return true
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
