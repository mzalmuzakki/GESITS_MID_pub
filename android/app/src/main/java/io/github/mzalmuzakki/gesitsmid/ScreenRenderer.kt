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

    /** Margins (pixels of the 350x220 MID picture) that the MID's own widgets cover. */
    data class Margins(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 56)

    // The dashboard is designed on a 350 x 160 canvas and scaled into the free area.
    private const val DW = 350f
    private const val DH = 160f

    fun render(t: Telemetry, rangeKm: Float?, m: Margins = Margins()): Bitmap {
        val bmp = Bitmap.createBitmap(Mid.FRAME_W, Mid.FRAME_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val cw = (Mid.FRAME_W - m.left - m.right).coerceAtLeast(40).toFloat()
        val ch = (Mid.FRAME_H - m.top - m.bottom).coerceAtLeast(30).toFloat()
        val sc = minOf(cw / DW, ch / DH)
        c.translate(m.left.toFloat(), m.top.toFloat())
        c.scale(sc, sc)
        c.clipRect(0f, 0f, DW, DH)
        drawDashboard(c, t, rangeKm)
        return bmp
    }

    private fun drawDashboard(c: Canvas, t: Telemetry, rangeKm: Float?) {
        val w = DW
        val box = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rr(l: Float, top: Float, r: Float, b: Float, col: Int, stroke: Boolean = false) {
            box.color = col
            box.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
            box.strokeWidth = 2f
            c.drawRoundRect(RectF(l, top, r, b), 6f, 6f, box)
        }
        val small = paint(13f, MUTED); val med = paint(20f, TEXT); val big = paint(44f, TEXT)

        // row 1: clock, gear, mode
        c.drawText(SimpleDateFormat("HH:mm", Locale.US).format(Date()), 8f, 21f, med)
        val mode = t.mode ?: "--"
        val mc = when (mode) { "ECO" -> GREEN; "URBAN" -> BLUE; "SPORT" -> RED; else -> MUTED }
        val mw = small.measureText(mode) + 16f
        rr(w - 8 - mw, 4f, w - 8, 25f, mc)
        c.drawText(mode, w - 8 - mw + 8, 20f, paint(13f, BG))
        val gear = t.gear ?: "-"
        val gc = when (gear) { "P" -> AMBER; "D" -> GREEN; else -> MUTED }
        rr(w - 16 - mw - 24, 4f, w - 16 - mw, 25f, gc, stroke = true)
        c.drawText(gear, w - 16 - mw - 16, 20f, paint(13f, gc))
        if (rangeKm != null) c.drawText("${fmt(rangeKm)} km", 92f, 21f, paint(16f, MUTED))

        // row 2: battery (left) and power (right)
        val soc = t.f("SOC")
        c.drawText(fmt(soc) + "%", 8f, 72f, big)
        rr(8f, 80f, 162f, 92f, PANEL)
        if (soc != null && soc > 0) rr(8f, 80f, 8f + 154f * soc.coerceIn(0f, 100f) / 100f, 92f,
            if (soc <= 20) RED else if (soc <= 35) AMBER else GREEN)
        box.color = PANEL; box.style = Paint.Style.FILL
        c.drawRect(Rect(174, 34, 176, 94), box)
        val p = t.powerW
        c.drawText((if (p == null) "--" else String.format(Locale.US, "%.1f", p / 1000f)) + " kW", 188f, 56f, med)
        c.drawText(if (p != null && p < 0) "REGEN" else "POWER", 188f, 73f, small)
        rr(188f, 80f, 342f, 92f, PANEL)
        if (p != null && p != 0f) rr(188f, 80f, 188f + 154f * (abs(p) / MAX_POWER_W).coerceIn(0f, 1f), 92f, if (p < 0) GREEN else BLUE)

        // row 3: temperatures, or error banner
        val errs = t.errors
        if (errs.isNotEmpty()) {
            rr(4f, 102f, w - 4, DH - 2, RED)
            c.drawText("ERROR", 14f, 124f, med)
            c.drawText(errs.first().take(40), 14f, 148f, paint(13f, TEXT))
        } else {
            val tiles = listOf(Triple("BAT", "TBAT", 45f to 55f), Triple("MOT", "TMOT", 70f to 85f), Triple("INV", "TINV", 70f to 85f))
            val tw = (w - 8 - 16) / 3
            tiles.forEachIndexed { i, (label, key, lim) ->
                val x0 = 4 + i * (tw + 8)
                val v = t.f(key)
                rr(x0, 102f, x0 + tw, DH - 2, PANEL)
                c.drawText(label, x0 + 8, 120f, small)
                c.drawText(fmt(v) + "\u00B0C", x0 + 8, 148f, paint(20f, tempColour(v, lim.first, lim.second)))
            }
        }
    }

    /**
     * Calibration grid: lines every 10 px (brighter every 50 px) with x/y labels and a coloured
     * border, so one photo of the MID shows exactly which part of the picture is visible.
     */
    fun calibrationGrid(): Bitmap {
        val bmp = Bitmap.createBitmap(Mid.FRAME_W, Mid.FRAME_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val thin = Paint().apply { color = Color.rgb(50, 50, 50); strokeWidth = 1f }
        val major = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 1f }
        for (x in 0..Mid.FRAME_W step 10) c.drawLine(x.toFloat(), 0f, x.toFloat(), Mid.FRAME_H.toFloat(), if (x % 50 == 0) major else thin)
        for (y in 0..Mid.FRAME_H step 10) c.drawLine(0f, y.toFloat(), Mid.FRAME_W.toFloat(), y.toFloat(), if (y % 50 == 0) major else thin)
        val lbl = paint(12f, Color.YELLOW)
        for (x in 50 until Mid.FRAME_W step 50) c.drawText("$x", x + 2f, 12f, lbl)
        for (y in 50 until Mid.FRAME_H step 50) c.drawText("$y", 2f, y - 2f, lbl)
        val edge = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        edge.color = Color.RED; c.drawRect(1.5f, 1.5f, Mid.FRAME_W - 1.5f, Mid.FRAME_H - 1.5f, edge)
        edge.color = Color.GREEN; c.drawRect(20f, 20f, Mid.FRAME_W - 20f, Mid.FRAME_H - 20f, edge)
        c.drawText("TOP", Mid.FRAME_W / 2f - 14, 34f, paint(14f, Color.CYAN))
        c.drawText("350x220", Mid.FRAME_W / 2f - 30, Mid.FRAME_H / 2f + 5, paint(16f, Color.WHITE))
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
