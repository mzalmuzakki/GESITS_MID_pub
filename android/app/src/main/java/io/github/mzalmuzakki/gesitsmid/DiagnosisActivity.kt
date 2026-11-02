package io.github.mzalmuzakki.gesitsmid

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Live charts, error explanations and alarm settings. */
class DiagnosisActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var errors: TextView
    private lateinit var values: TextView
    private lateinit var speed: ChartView
    private lateinit var power: ChartView
    private lateinit var temps: ChartView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }

    /** What to do for each MID error bit (community list, unofficial). */
    private val fixes = mapOf(
        0 to "Let the bike rest until the inverter cools down; avoid long heavy load.",
        1 to "Rest the motor until its temperature is back to normal.",
        2 to "Check motor cables and connectors; visit a Gesits workshop if it repeats.",
        3 to "Check the motor's hall-sensor connector (loose or dirty).",
        4 to "Check throttle position and cable; make sure nothing is stuck.",
        5 to "Restart the system (off/on). If it stays, visit a Gesits workshop.",
        6 to "Check the charger and mains voltage.",
        7 to "Charge the battery: its voltage is too low.",
        11 to "Seen together with real throttle problems (ERR=0800). Check throttle cable/connector; report the code to a workshop.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val (root, col) = Ui.page(this, "Diagnosis")
        setContentView(root)
        val a = this

        Ui.header(a, col, "Errors")
        errors = Ui.text(a, col)
        Ui.header(a, col, "Now")
        values = Ui.text(a, col, mono = true)

        Ui.header(a, col, "Speed (last 10 min)")
        speed = ChartView(this, 120).also { col.addView(it) }
        Ui.header(a, col, "Power (kW, below 0 = regen)")
        power = ChartView(this, 120).also { col.addView(it) }
        Ui.header(a, col, "Temperatures (°C)")
        temps = ChartView(this, 140).also { col.addView(it) }

        Ui.header(a, col, "Alarms (phone notifications)")
        col.addView(CheckBox(this).apply {
            text = L.t("Notify me about low battery, hot battery (> 45°C) or motor (> 85°C), battery health below 75%, and MID errors")
            isChecked = settings.alarmsOn; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, b -> settings.alarmsOn = b }
        })
        val eSoc = Ui.number(a, col, "Low battery alarm below (%)", settings.socLow)
        Ui.button(a, col, "Save") {
            eSoc.text.toString().toFloatOrNull()?.takeIf { it in 1f..90f }?.let { settings.socLow = it }
            Toast.makeText(this, L.t("Saved"), Toast.LENGTH_SHORT).show()
        }
        Ui.text(a, col, "Alarms work while the app is connected (also with the screen off).", 12f, Ui.MUTED)
    }

    override fun onResume() { super.onResume(); Bike.uiFast = true; ui.post(tick) }
    override fun onPause() { super.onPause(); Bike.uiFast = false; ui.removeCallbacks(tick) }

    private fun refresh() {
        val t = Bike.telemetry
        val mask = t.errorMask
        errors.text = when {
            !Bike.connected && mask == null -> L.t("Not connected")
            mask == null -> L.t("Waiting for data...")
            mask == 0 -> L.t("OK - no errors")
            else -> (0 until 16).filter { ((mask shr it) and 1) == 1 }.joinToString("\n\n") { b ->
                "⚠ ${Mid.ERROR_BITS[b]?.let { L.t(it) } ?: (L.t("Unknown error bit") + " $b")}\n${L.t(fixes[b] ?: "Unknown code - note it and ask a workshop.")}"
            } + String.format(Locale.US, "\n\n(raw ERR=%04X)", mask)
        }
        errors.setTextColor(if (mask != null && mask != 0) Color.rgb(235, 60, 60) else Color.WHITE)
        // frozen values (the MID answers but nothing changes) are not shown as if they were live
        fun v(k: String, u: String) = (if (Bike.dataFrozen) null else t.raw[k])?.let { "$it$u" } ?: "--"
        values.text = (if (Bike.dataFrozen) "⚠ " + L.t("MID values look frozen") + "\n" else "") + L.t("Speed") + " ${v("SPD", " km/h")}\n" +
            L.t("Battery") + " ${v("SOC", "%")}  " + L.t("health") + " ${v("SOH", "%")}  ${v("VBAT", " V")}  ${v("IBAT", " A")}\n" +
            L.t("Temps") + "  " + L.t("battery") + " ${v("TBAT", "")}  " + L.t("motor") + " ${v("TMOT", "")}  " + L.t("inverter") + " ${v("TINV", "")} °C"

        val s = synchronized(Bike.samples) { Bike.samples.toList() }
        speed.series = listOf(ChartView.Series("km/h", Color.rgb(255, 150, 60), s.map { it.spd }))
        power.series = listOf(ChartView.Series("kW", Ui.GREEN, s.map { it.powerW?.div(1000f) }))
        temps.series = listOf(
            ChartView.Series("battery", Color.rgb(170, 90, 255), s.map { it.tbat }),
            ChartView.Series("motor", Color.rgb(255, 150, 60), s.map { it.tmot }),
            ChartView.Series("inverter", Color.rgb(60, 140, 255), s.map { it.tinv }),
        )
    }
}
