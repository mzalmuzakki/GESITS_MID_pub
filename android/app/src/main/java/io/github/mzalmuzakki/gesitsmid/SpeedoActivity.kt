package io.github.mzalmuzakki.gesitsmid

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Full-screen landscape speedometer for a phone on the handlebar. Tap to close. */
class SpeedoActivity : Activity() {
    private lateinit var view: SpeedoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = SpeedoView(this).apply { setOnClickListener { finish() } }
        @Suppress("DEPRECATION")
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(view)
    }

    override fun onResume() { super.onResume(); view.running = true; view.postInvalidate() }
    override fun onPause() { super.onPause(); view.running = false }
}

class SpeedoView(ctx: Context) : View(ctx) {
    @Volatile var running = false
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private fun p(size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = bold; textAlign = align }
    private val BG = Color.rgb(8, 10, 14); private val PANEL = Color.rgb(30, 34, 42); private val TEXT = Color.rgb(235, 238, 242)
    private val MUTED = Color.rgb(140, 148, 160); private val GREEN = Color.rgb(60, 200, 110); private val AMBER = Color.rgb(255, 176, 32)
    private val RED = Color.rgb(235, 60, 60); private val BLUE = Color.rgb(60, 140, 255)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, col: Int) { box.color = col; c.drawRoundRect(RectF(l, t, r, b), 14f, 14f, box) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = minOf(w / 16f, h / 9f)   // layout unit
        c.drawColor(BG)
        val t = Bike.telemetry

        // top bar: clock, connection, mode, gear
        c.drawText(SimpleDateFormat("HH:mm", Locale.US).format(Date()), u * 0.5f, u * 0.9f, p(u * 0.6f, TEXT))
        c.drawText(if (Bike.connected) "● connected" else "○ not connected", u * 3f, u * 0.85f,
            p(u * 0.35f, if (Bike.connected) GREEN else RED))
        val mode = t.mode ?: "--"
        val mc = when (mode) { "ECO" -> GREEN; "URBAN" -> BLUE; "SPORT" -> RED; else -> MUTED }
        c.drawText(mode, w - u * 0.5f, u * 0.9f, p(u * 0.6f, mc, Paint.Align.RIGHT))
        val gear = t.gear ?: "-"
        c.drawText(gear, w - u * 3.6f, u * 0.9f, p(u * 0.6f, if (gear == "P") AMBER else if (gear == "D") GREEN else MUTED, Paint.Align.RIGHT))

        // centre: speed
        val spd = t.f("SPD")
        c.drawText(spd?.toInt()?.toString() ?: "--", w * 0.5f, h * 0.62f, p(u * 4.2f, TEXT, Paint.Align.CENTER))
        c.drawText("km/h", w * 0.5f, h * 0.62f + u * 0.8f, p(u * 0.5f, MUTED, Paint.Align.CENTER))

        // left: battery
        val soc = t.f("SOC")
        c.drawText("BATTERY", u * 0.6f, h * 0.35f, p(u * 0.35f, MUTED))
        c.drawText((soc?.toInt()?.toString() ?: "--") + "%", u * 0.6f, h * 0.35f + u * 1.4f, p(u * 1.3f, TEXT))
        rr(c, u * 0.6f, h * 0.35f + u * 1.8f, u * 4.6f, h * 0.35f + u * 2.2f, PANEL)
        if (soc != null) rr(c, u * 0.6f, h * 0.35f + u * 1.8f, u * 0.6f + u * 4f * soc.coerceIn(0f, 100f) / 100f, h * 0.35f + u * 2.2f,
            if (soc <= 20) RED else if (soc <= 35) AMBER else GREEN)
        val range = Bike.rangeKm()
        c.drawText("RANGE", u * 0.6f, h * 0.35f + u * 3.1f, p(u * 0.35f, MUTED))
        c.drawText((range?.let { String.format(Locale.US, "%.0f", it) } ?: "--") + " km", u * 0.6f, h * 0.35f + u * 4.1f, p(u * 0.9f, TEXT))

        // right: power
        val pw = t.powerW
        val regen = pw != null && pw < 0
        c.drawText(if (regen) "REGEN" else "POWER", w - u * 0.6f, h * 0.35f, p(u * 0.35f, MUTED, Paint.Align.RIGHT))
        c.drawText((pw?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "--") + " kW", w - u * 0.6f, h * 0.35f + u * 1.4f,
            p(u * 1.1f, if (regen) GREEN else TEXT, Paint.Align.RIGHT))
        rr(c, w - u * 4.6f, h * 0.35f + u * 1.8f, w - u * 0.6f, h * 0.35f + u * 2.2f, PANEL)
        if (pw != null) rr(c, w - u * 4.6f, h * 0.35f + u * 1.8f, w - u * 4.6f + u * 4f * (abs(pw) / 5000f).coerceIn(0f, 1f),
            h * 0.35f + u * 2.2f, if (regen) GREEN else BLUE)
        val trip = synchronized(Bike.trip) { Bike.trip.km }
        c.drawText("TRIP", w - u * 0.6f, h * 0.35f + u * 3.1f, p(u * 0.35f, MUTED, Paint.Align.RIGHT))
        c.drawText(String.format(Locale.US, "%.1f km", trip), w - u * 0.6f, h * 0.35f + u * 4.1f, p(u * 0.9f, TEXT, Paint.Align.RIGHT))

        // bottom: temperatures or error
        val errs = t.errors
        val bottom = if (errs.isNotEmpty()) "⚠ " + errs.first()
            else "battery ${t.raw["TBAT"] ?: "--"}°  motor ${t.raw["TMOT"] ?: "--"}°  inverter ${t.raw["TINV"] ?: "--"}°C"
        c.drawText(bottom, w * 0.5f, h - u * 0.4f, p(u * 0.4f, if (errs.isNotEmpty()) RED else MUTED, Paint.Align.CENTER))
        c.drawText("tap to close", w - u * 0.5f, h - u * 0.4f, p(u * 0.25f, Color.rgb(70, 76, 88), Paint.Align.RIGHT))

        if (running) postInvalidateDelayed(200)
    }
}
