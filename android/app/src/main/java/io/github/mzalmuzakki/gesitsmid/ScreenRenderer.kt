package io.github.mzalmuzakki.gesitsmid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Draws the custom MID dashboard (same layout as gesits/screen.py) and packs pictures for AT+MAPS. */
object ScreenRenderer {
    private val BG = Color.rgb(12, 14, 18)
    private val PANEL = Color.rgb(30, 34, 42)
    private val TEXT = Color.rgb(235, 238, 242)
    private val MUTED = Color.rgb(140, 148, 160)
    private val GREEN = Color.rgb(60, 200, 110)
    private val AMBER = Color.rgb(255, 176, 32)
    private val RED = Color.rgb(235, 60, 60)
    private val BLUE = Color.rgb(60, 140, 255)
    private const val MAX_POWER_W = 5000f

    private fun paint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = Typeface.DEFAULT_BOLD
    }

    private fun fmt(v: Float?, spec: String = "%.0f") = if (v == null) "--" else String.format(Locale.US, spec, v)

    private fun tempColour(v: Float?, warn: Float, crit: Float) =
        if (v == null) MUTED else if (v >= crit) RED else if (v >= warn) AMBER else TEXT

    fun render(t: Telemetry, kmPerPercent: Float): Bitmap {
        val w = Mid.FRAME_W.toFloat(); val h = Mid.FRAME_H.toFloat()
        val bmp = Bitmap.createBitmap(Mid.FRAME_W, Mid.FRAME_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val box = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rr(l: Float, top: Float, r: Float, b: Float, col: Int, stroke: Boolean = false) {
            box.color = col
            box.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
            box.strokeWidth = 2f
            c.drawRoundRect(RectF(l, top, r, b), 6f, 6f, box)
        }
        val small = paint(14f, MUTED); val med = paint(21f, TEXT); val big = paint(46f, TEXT)

        // top bar
        c.drawText(SimpleDateFormat("HH:mm", Locale.US).format(Date()), 10f, 27f, med)
        val mode = t.mode ?: "--"
        val mc = when (mode) { "ECO" -> GREEN; "URBAN" -> BLUE; "SPORT" -> RED; else -> MUTED }
        val mw = small.measureText(mode) + 16f
        rr(w - 10 - mw, 8f, w - 10, 30f, mc)
        c.drawText(mode, w - 10 - mw + 8, 25f, paint(14f, BG))
        val gear = t.gear ?: "-"
        val gc = when (gear) { "P" -> AMBER; "D" -> GREEN; else -> MUTED }
        rr(w - 20 - mw - 26, 8f, w - 20 - mw, 30f, gc, stroke = true)
        c.drawText(gear, w - 20 - mw - 18, 25f, paint(14f, gc))

        // battery
        val soc = t.f("SOC")
        c.drawText(fmt(soc) + "%", 12f, 86f, big)
        rr(12f, 96f, 162f, 112f, PANEL)
        if (soc != null && soc > 0) rr(12f, 96f, 12f + 150f * soc.coerceIn(0f, 100f) / 100f, 112f,
            if (soc <= 20) RED else if (soc <= 35) AMBER else GREEN)
        c.drawText("~${fmt(t.rangeKm(kmPerPercent))} km range", 12f, 134f, small)

        // power
        box.color = PANEL; box.style = Paint.Style.FILL
        c.drawRect(Rect(175, 44, 177, 136), box)
        val p = t.powerW
        c.drawText((if (p == null) "--" else String.format(Locale.US, "%.1f", p / 1000f)) + " kW", 188f, 64f, med)
        c.drawText(if (p != null && p < 0) "REGEN" else "POWER", 188f, 86f, small)
        rr(188f, 96f, 338f, 112f, PANEL)
        if (p != null && p != 0f) rr(188f, 96f, 188f + 150f * (abs(p) / MAX_POWER_W).coerceIn(0f, 1f), 112f, if (p < 0) GREEN else BLUE)
        c.drawText("ODO ${fmt(t.f("ODO"), "%.1f")} km", 188f, 134f, small)

        // temperatures or error banner
        val errs = t.errors
        if (errs.isNotEmpty()) {
            rr(8f, 150f, w - 8, h - 8, RED)
            c.drawText("ERROR", 18f, 176f, med)
            c.drawText(errs.first().take(38), 18f, 200f, paint(14f, TEXT))
        } else {
            val tiles = listOf(Triple("BAT", "TBAT", 45f to 55f), Triple("MOT", "TMOT", 70f to 85f), Triple("INV", "TINV", 70f to 85f))
            val tw = (w - 16 - 16) / 3
            tiles.forEachIndexed { i, (label, key, lim) ->
                val x0 = 8 + i * (tw + 8)
                val v = t.f(key)
                rr(x0, 150f, x0 + tw, h - 8, PANEL)
                c.drawText(label, x0 + 10, 170f, small)
                c.drawText(fmt(v) + "°C", x0 + 10, 200f, paint(21f, tempColour(v, lim.first, lim.second)))
            }
        }
        return bmp
    }

    /** Crop-to-fill any picture to the MID frame size. */
    fun cropToFrame(src: Bitmap): Bitmap {
        val ratio = Mid.FRAME_W.toFloat() / Mid.FRAME_H
        val (sw, sh) = if (src.width.toFloat() / src.height > ratio) (src.height * ratio).toInt() to src.height
                       else src.width to (src.width / ratio).toInt()
        val out = Bitmap.createBitmap(Mid.FRAME_W, Mid.FRAME_H, Bitmap.Config.ARGB_8888)
        val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, Mid.FRAME_W, Mid.FRAME_H), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** JPEG -> base64, lowering quality until it fits the MID's 7000-char limit. Null if it never fits. */
    fun toMapsBase64(bmp: Bitmap, maxChars: Int = Mid.MAPS_MAX_CHARS): String? {
        var q = 85
        while (q >= 5) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= maxChars) return b64
            q -= if (q > 30) 5 else 3
        }
        return null
    }
}
