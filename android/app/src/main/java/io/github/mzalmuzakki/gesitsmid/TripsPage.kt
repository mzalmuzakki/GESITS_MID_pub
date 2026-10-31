package io.github.mzalmuzakki.gesitsmid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
/** Trips tab: totals, weekly target, charts, savings vs petrol, records, history, export. All offline. */
class TripsPage(private val a: Activity, private val settings: Settings) {
    companion object { const val EXPORT_REQ = 7 }
    private val indo = Locale("id", "ID")
    private fun rp(v: Float) = "Rp " + NumberFormat.getIntegerInstance(indo).format(v.toLong())
    private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

    /** Rebuilds the content (called when the tab is shown - not on every tick). */
    fun build(col: LinearLayout) {
        col.removeAllViews()
        val rides = RideStore.all(a)
        val st = RideStats(rides)
        val now = System.currentTimeMillis()

        // Weekly target first: the number people check most
        val week = st.weeklyKm(now, 8)
        val target = settings.weeklyTargetKm
        val pct = if (target > 0) (week.last() / target * 100).toInt() else 0
        val prev = week[week.size - 2]
        val wc = Ui.card(a, col, "THIS WEEK")
        Ui.text(a, wc, "${f1(week.last())} km", 30f, bold = true)
        Ui.text(a, wc, "$pct% of your ${target.toInt()} km target" +
            if (prev > 0) String.format(Locale.US, "  ·  %+.0f%% vs last week", (week.last() - prev) / prev * 100) else "", 13f, Ui.MUTED)
        wc.addView(ChartView(a, 120).apply {
            bars = true; unit = " km"
            series = listOf(ChartView.Series("km per week", Ui.GREEN, week))
            val f = SimpleDateFormat("d MMM", indo); val start = st.startOfWeek(now)
            xLabels = (7 downTo 0).map { back -> f.format(Date(start - back * 7L * 24 * 3600 * 1000)) }
        })

        val tc = Ui.card(a, col, "ALL RIDES")
        var r = Ui.row(a, tc)
        Ui.tile(a, r, "DISTANCE").text = "${f1(st.totalKm)} km"
        Ui.tile(a, r, "RIDES").text = "${st.count}"
        r = Ui.row(a, tc)
        Ui.tile(a, r, "ENERGY").text = String.format(Locale.US, "%.1f kWh", st.totalWh / 1000f)
        Ui.tile(a, r, "EFFICIENCY").text = st.avgWhPerKm?.let { String.format(Locale.US, "%.0f Wh/km", it) } ?: "--"
        r = Ui.row(a, tc)
        Ui.tile(a, r, "REGEN").text = String.format(Locale.US, "%.0f Wh", st.totalRegenWh)
        Ui.tile(a, r, "KM PER 1%").text = synchronized(Bike.range) { String.format(Locale.US, "%.2f", Bike.range.kmPerPercent) }

        val sc = Ui.card(a, col, "SAVED VS A PETROL MOTORCYCLE", Ui.GREEN)
        val sv = st.savings(settings.kmPerLitre, settings.rpPerLitre, settings.rpPerKwh)
        Ui.text(a, sc, rp(sv.netRp), 30f, Ui.GREEN, bold = true)
        r = Ui.row(a, sc)
        Ui.tile(a, r, "PETROL NOT BURNED").text = String.format(Locale.US, "%.1f L", sv.litres)
        Ui.tile(a, r, "CO₂ AVOIDED").text = String.format(Locale.US, "%.1f kg", sv.co2Kg)
        Ui.text(a, sc, "Petrol ${rp(sv.petrolRp)} minus electricity ${rp(sv.electricityRp)}", 12f, Ui.MUTED)

        val dc = Ui.card(a, col, "LAST 7 DAYS")
        dc.addView(ChartView(a, 110).apply {
            bars = true; unit = " km"
            series = listOf(ChartView.Series("km per day", Ui.BLUE, st.dailyKm(now, 7)))
            val f = SimpleDateFormat("EEE", indo)
            xLabels = (6 downTo 0).map { back -> f.format(Date(now - back * 24L * 3600 * 1000)) }
        })

        val rc = Ui.card(a, col, "RECORDS")
        val df = SimpleDateFormat("d MMM yyyy", indo)
        Ui.text(a, rc, "Longest ride   " + (st.longest?.let { "${f1(it.km)} km · ${df.format(Date(it.startMs))}" } ?: "--"))
        Ui.text(a, rc, "Most efficient " + (st.mostEfficient?.let { String.format(Locale.US, "%.0f Wh/km · %s", it.whPerKm, df.format(Date(it.startMs))) } ?: "--"))
        Ui.text(a, rc, "Top speed      " + (st.topSpeed?.let { "${it.toInt()} km/h" } ?: "--"))

        val hc = Ui.card(a, col, "RIDE HISTORY")
        val tf = SimpleDateFormat("d MMM HH:mm", indo)
        if (rides.isEmpty()) Ui.text(a, hc, "No rides yet. Rides are saved automatically while the app is connected: " +
            "a ride starts when you move and ends after 5 minutes stopped.", 13f, Ui.MUTED)
        rides.takeLast(30).reversed().forEach { ride ->
            Ui.text(a, hc, String.format(Locale.US, "%-12s %5.1f km  %3s Wh/km  %d→%d%%", tf.format(Date(ride.startMs)), ride.km,
                ride.whPerKm?.let { String.format(Locale.US, "%.0f", it) } ?: "--", ride.socStart.toInt(), ride.socEnd.toInt()), 12f, mono = true)
        }
        r = Ui.row(a, hc)
        Ui.secondary(a, r, "Export CSV", 1f) {
            a.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "gesits-rides.csv"), EXPORT_REQ)
        }
        Ui.secondary(a, r, "Delete all", 1f, Ui.RED) {
            AlertDialog.Builder(a).setTitle("Delete all rides?").setMessage("This cannot be undone. Export first to keep a copy.")
                .setPositiveButton("Delete") { _, _ -> RideStore.clear(a); build(col) }.setNegativeButton("Cancel", null).show()
        }

        val set = Ui.collapsible(a, col, "Target & prices")
        val eTarget = Ui.number(a, set, "Weekly target (km)", settings.weeklyTargetKm)
        val eKmL = Ui.number(a, set, "Petrol motorcycle: km per litre", settings.kmPerLitre)
        val eRpL = Ui.number(a, set, "Petrol price (Rp per litre)", settings.rpPerLitre)
        val eRpKwh = Ui.number(a, set, "Electricity price (Rp per kWh)", settings.rpPerKwh)
        Ui.primary(a, set, "Save") {
            eTarget.text.toString().toFloatOrNull()?.takeIf { it >= 0 }?.let { settings.weeklyTargetKm = it }
            eKmL.text.toString().toFloatOrNull()?.takeIf { it > 0 }?.let { settings.kmPerLitre = it }
            eRpL.text.toString().toFloatOrNull()?.takeIf { it >= 0 }?.let { settings.rpPerLitre = it }
            eRpKwh.text.toString().toFloatOrNull()?.takeIf { it >= 0 }?.let { settings.rpPerKwh = it }
            Toast.makeText(a, "Saved", Toast.LENGTH_SHORT).show(); build(col)
        }
    }

    /** Called from MainActivity.onActivityResult for the CSV file the user picked. */
    fun export(uri: android.net.Uri) {
        try {
            a.contentResolver.openOutputStream(uri)?.use { it.write(RideStore.csv(a).toByteArray()) }
            Toast.makeText(a, "Exported ${RideStore.all(a).size} rides", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(a, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
