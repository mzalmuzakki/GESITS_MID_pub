package io.github.mzalmuzakki.gesitsmid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Trips, totals, weekly target, charts, records, savings vs petrol, CSV export. All offline. */
@Suppress("DEPRECATION")
class StatsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var col: LinearLayout
    private val exportReq = 7
    private val id = Locale("id", "ID")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val (root, c) = Ui.page(this, "Stats")
        col = c
        setContentView(root)
        build()
    }

    private fun rp(v: Float) = "Rp " + NumberFormat.getIntegerInstance(id).format(v.toLong())
    private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun build() {
        val a = this
        col.removeViews(1, col.childCount - 1)
        val rides = RideStore.all(this)
        val st = RideStats(rides)
        val now = System.currentTimeMillis()

        Ui.header(a, col, "Totals")
        var r = Ui.row(a, col)
        Ui.tile(a, r, "TOTAL DISTANCE").text = "${f1(st.totalKm)} km"
        Ui.tile(a, r, "RIDES").text = "${st.count}"
        r = Ui.row(a, col)
        Ui.tile(a, r, "ENERGY USED").text = String.format(Locale.US, "%.1f kWh", st.totalWh / 1000f)
        Ui.tile(a, r, "AVG EFFICIENCY").text = st.avgWhPerKm?.let { String.format(Locale.US, "%.0f Wh/km", it) } ?: "-- Wh/km"
        r = Ui.row(a, col)
        Ui.tile(a, r, "REGEN RECOVERED").text = String.format(Locale.US, "%.0f Wh", st.totalRegenWh)
        Ui.tile(a, r, "LEARNED RANGE").text = synchronized(Bike.range) { String.format(Locale.US, "%.2f km/1%%", Bike.range.kmPerPercent) }

        Ui.header(a, col, "Weekly target")
        val week = st.weeklyKm(now, 8)
        val target = settings.weeklyTargetKm
        val pct = if (target > 0) (week.last() / target * 100).toInt() else 0
        val prev = week[week.size - 2]
        Ui.text(a, col, "This week: ${f1(week.last())} / ${target.toInt()} km  ($pct%)", 16f)
        Ui.text(a, col, "Last week: ${f1(prev)} km" + if (prev > 0) String.format(Locale.US, "  (%+.0f%%)", (week.last() - prev) / prev * 100) else "", 13f, Ui.MUTED)
        val wk = ChartView(this).apply {
            bars = true; unit = " km"
            series = listOf(ChartView.Series("km per week", Ui.GREEN, week))
            val f = SimpleDateFormat("d MMM", id)
            val start = st.startOfWeek(now)
            xLabels = (7 downTo 0).map { back -> f.format(Date(start - back * 7L * 24 * 3600 * 1000)) }
        }
        col.addView(wk)

        Ui.header(a, col, "Last 7 days")
        val days = st.dailyKm(now, 7)
        col.addView(ChartView(this).apply {
            bars = true; unit = " km"
            series = listOf(ChartView.Series("km per day", Color.rgb(60, 140, 255), days))
            val f = SimpleDateFormat("EEE", id)
            xLabels = (6 downTo 0).map { back -> f.format(Date(now - back * 24L * 3600 * 1000)) }
        })

        Ui.header(a, col, "Savings vs a petrol motorcycle")
        val sv = st.savings(settings.kmPerLitre, settings.rpPerLitre, settings.rpPerKwh)
        Ui.text(a, col, rp(sv.netRp) + " saved", 26f, Ui.GREEN)
        r = Ui.row(a, col)
        Ui.tile(a, r, "PETROL NOT BURNED").text = String.format(Locale.US, "%.1f L", sv.litres)
        Ui.tile(a, r, "CO₂ NOT EMITTED").text = String.format(Locale.US, "%.1f kg", sv.co2Kg)
        Ui.text(a, col, "Petrol ${rp(sv.petrolRp)} - electricity ${rp(sv.electricityRp)}. Compared with " +
            "${settings.kmPerLitre.toInt()} km/L at ${rp(settings.rpPerLitre)}/L; electricity ${rp(settings.rpPerKwh)}/kWh.", 12f, Ui.MUTED)

        Ui.header(a, col, "Records")
        val df = SimpleDateFormat("d MMM yyyy", id)
        Ui.text(a, col, "Longest ride: " + (st.longest?.let { "${f1(it.km)} km (${df.format(Date(it.startMs))})" } ?: "--"))
        Ui.text(a, col, "Most efficient (≥ 2 km): " + (st.mostEfficient?.let { String.format(Locale.US, "%.0f Wh/km (%s)", it.whPerKm, df.format(Date(it.startMs))) } ?: "--"))
        Ui.text(a, col, "Top speed: " + (st.topSpeed?.let { "${it.toInt()} km/h" } ?: "--"))

        Ui.header(a, col, "Ride history")
        val tf = SimpleDateFormat("EEE d MMM, HH:mm", id)
        if (rides.isEmpty()) Ui.text(a, col, "No rides yet. Rides are recorded automatically while the app is connected.", 13f, Ui.MUTED)
        rides.takeLast(30).reversed().forEach { ride ->
            Ui.text(a, col, String.format(Locale.US, "%s  %.1f km  %s  %d→%d%%  max %d km/h", tf.format(Date(ride.startMs)), ride.km,
                ride.whPerKm?.let { String.format(Locale.US, "%.0f Wh/km", it) } ?: "--", ride.socStart.toInt(), ride.socEnd.toInt(),
                ride.maxSpeed.toInt()), 13f, mono = true)
        }

        Ui.header(a, col, "Settings")
        val eTarget = Ui.number(a, col, "Weekly target (km)", settings.weeklyTargetKm)
        val eKmL = Ui.number(a, col, "Petrol bike consumption (km per litre)", settings.kmPerLitre)
        val eRpL = Ui.number(a, col, "Petrol price (Rp per litre)", settings.rpPerLitre)
        val eRpKwh = Ui.number(a, col, "Electricity price (Rp per kWh)", settings.rpPerKwh)
        Ui.button(a, col, "Save settings") {
            eTarget.text.toString().toFloatOrNull()?.let { settings.weeklyTargetKm = it }
            eKmL.text.toString().toFloatOrNull()?.takeIf { it > 0 }?.let { settings.kmPerLitre = it }
            eRpL.text.toString().toFloatOrNull()?.let { settings.rpPerLitre = it }
            eRpKwh.text.toString().toFloatOrNull()?.let { settings.rpPerKwh = it }
            build(); toast("Saved")
        }
        val bottom = Ui.row(a, col)
        Ui.button(a, bottom, "Export CSV") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "gesits-rides.csv"), exportReq)
        }
        Ui.button(a, bottom, "Delete all rides") {
            AlertDialog.Builder(this).setTitle("Delete all rides?").setMessage("This cannot be undone. Export first if you want to keep them.")
                .setPositiveButton("Delete") { _, _ -> RideStore.clear(this); build() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == exportReq && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(RideStore.csv(this).toByteArray()) }
                toast("Exported ${RideStore.all(this).size} rides")
            } catch (e: Exception) { toast("Export failed: ${e.message}") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
