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
    const val FRAME_W = 350
    const val FRAME_H = 220

    /** One key is polled every 200 ms, round robin. */
    val POLL_KEYS = listOf("SPD", "IBAT", "VBAT", "SOC", "ODO", "MODE", "PARK", "ERR", "TBAT", "TMOT", "TINV", "SOH")

    fun query(key: String) = "AT+$key?\r\n"
    fun time(hour: Int, minute: Int) = String.format(Locale.US, "AT+TIME=\"%02d%02d\"\r\n", hour, minute)
    fun trip(km: Float) = String.format(Locale.US, "AT+TRIP=\"%.1f\"\r\n", km)
    fun maps(base64Jpeg: String) = "AT+MAPS=\"$base64Jpeg\"\r\n"

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

/** Latest values received from the bike. Thread-safe. */
class Telemetry {
    val raw = ConcurrentHashMap<String, String>()
    @Volatile var lastUpdate = 0L

    fun put(key: String, value: String) { raw[key] = value; lastUpdate = System.currentTimeMillis() }
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

    fun feed(data: ByteArray, out: (String, String) -> Unit) {
        buf.append(String(data, Charsets.US_ASCII))
        while (true) {
            val nl = buf.indexOf("\n")
            if (nl < 0) break
            var line = buf.substring(0, nl).trim()
            buf.delete(0, nl + 1)
            if (line.startsWith("AT+")) line = line.substring(3)
            val eq = line.indexOf('=')
            if (eq > 0) out(line.substring(0, eq).trim().uppercase(), line.substring(eq + 1).trim())
        }
        if (buf.length > 4096) buf.setLength(0)
    }
}
