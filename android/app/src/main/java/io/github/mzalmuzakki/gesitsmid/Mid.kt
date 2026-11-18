package io.github.mzalmuzakki.gesitsmid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/** The Gesits G1 MID's Bluetooth protocol (as seen on MID firmware 11-50-09-G1). */
object Mid {
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Everything the MID was seen to answer. */
    val ALL_KEYS = listOf("SPD", "ODO", "SOC", "IBAT", "VBAT", "PARK", "MODE", "ERR", "TBAT", "TMOT", "TINV", "SOH", "RANGE")
    /** Just what the range needs (+ speed for the screen). */
    val RANGE_KEYS = listOf("SPD", "ODO", "SOC")

    fun query(key: String) = "AT+$key?\r\n"
    fun time(h: Int, m: Int) = String.format(Locale.US, "AT+TIME=\"%02d%02d\"\r\n", h, m)
    /** The MID's text line: 20 characters, padded with spaces. */
    fun nav(text: String) = "AT+NAV=\"" + ascii(text).take(20).padEnd(20) + "\"\r\n"
    fun maps(b64: String) = "AT+MAPS=\"$b64\"\r\n"

    fun ascii(s: String) = s.map { if (it in ' '..'~' && it != '"') it else ' ' }.joinToString("")

    /** "AT+SOC=088" -> ("SOC", "088"); anything else -> null. */
    private val reply = Regex("""AT\+([A-Z]+)=(.*)""")
    fun parse(line: String): Pair<String, String>? = reply.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2].trim().trim('"') }

    const val W = 320
    const val H = 128

    /** The range picture: one big green number on black (small JPEG, changes only with the range). */
    fun rangePicture(rangeKm: Float?, label: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        fun paint(size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = Typeface.DEFAULT_BOLD; textAlign = align
        }
        val num = rangeKm?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
        val big = paint(84f, Color.rgb(60, 220, 110), Paint.Align.CENTER)
        val cx = W / 2f - 24f
        c.drawText(num, cx, 92f, big)
        val half = big.measureText(num) / 2
        c.drawText("km", cx + half + 8f, 92f, paint(26f, Color.WHITE))
        c.drawText(label, cx - half, 118f, paint(16f, Color.rgb(150, 155, 165)))
        return bmp
    }

    /** JPEG -> base64, lowering the quality until it is at most [maxChars]. */
    fun toBase64(bmp: Bitmap, maxChars: Int): String? {
        var q = 85
        while (q >= 5) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= maxChars) return b64
            q -= 10
        }
        return null
    }
}
