package io.github.mzalmuzakki.gesitsmid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One screen: range first, then connection, settings (each feature can be switched), console and log. */
class MainActivity : Activity() {
    private lateinit var s: Settings
    private lateinit var col: LinearLayout
    private lateinit var rangeView: TextView
    private lateinit var rangeInfo: TextView
    private lateinit var values: TextView
    private lateinit var statusView: TextView
    private lateinit var connectBtn: Button
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var lastLogShown = ""

    private val bg = Color.rgb(16, 18, 22)
    private val fg = Color.rgb(235, 238, 242)
    private val muted = Color.rgb(150, 155, 165)
    private val green = Color.rgb(60, 220, 110)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Log.init(this)
        s = Settings(this)
        synchronized(Bike.range) { if (!Bike.running) Bike.range.load(s.rangeState) }
        askPermissions()
        build()
    }

    override fun onStart() { super.onStart(); Log.ev("App opened"); ui.post(tick) }
    override fun onStop() { super.onStop(); Log.ev("App in background"); ui.removeCallbacks(tick) }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) { need += Manifest.permission.BLUETOOTH_CONNECT; need += Manifest.permission.BLUETOOTH_SCAN }
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ------------------------------------------------------------------ small UI helpers

    private fun text(parent: LinearLayout, t: String, size: Float = 14f, color: Int = fg, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(0, 6, 0, 6)
        }.also { parent.addView(it) }

    private fun header(t: String) = text(col, t, 13f, muted, bold = true).apply { setPadding(0, 36, 0, 6) }

    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { col.addView(it) }

    private fun button(parent: LinearLayout, t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }.also { parent.addView(it) }

    private fun switch(t: String, on: Boolean, set: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = on; setTextColor(fg); textSize = 15f; setPadding(0, 12, 0, 12)
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> set(v); Log.ev("Setting: $t = $v") }
    }.also { col.addView(it) }

    private fun number(t: String, v: Int, set: (Int) -> Unit) {
        val r = row()
        text(r, t, 15f).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        EditText(this).apply {
            setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(fg); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, has -> if (!has) text.toString().toIntOrNull()?.let { n -> if (n != v) { set(n); Log.ev("Setting: $t = $n") } } }
        }.also { r.addView(it) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ screen

    private fun build() {
        val sv = ScrollView(this).apply { setBackgroundColor(bg) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }
        sv.addView(col)
        setContentView(sv)

        text(col, "Gesits MID  v" + packageManager.getPackageInfo(packageName, 0).versionName, 13f, muted)
        text(col, "RANGE", 13f, muted, bold = true).setPadding(0, 24, 0, 0)
        rangeView = text(col, "-- km", 64f, green, bold = true)
        rangeInfo = text(col, "", 13f, muted)
        values = text(col, "", 16f)
        statusView = text(col, "", 14f, muted)

        val r1 = row()
        connectBtn = button(r1, "Connect") {
            if (Bike.running) MidService.stop(this) else if (s.address == null) chooseBike() else MidService.start(this)
        }
        button(r1, "Choose bike") { chooseBike() }
        val r2 = row()
        button(r2, "Pair bike") { pairBike() }
        button(r2, "Bluetooth settings") { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button(row(), "Don't stop the app in the background (battery)") { batteryExemption() }

        header("RANGE ON THE MID")
        switch("Range picture (big number)", s.rangePicture) { s.rangePicture = it }
        number("Picture at most every (s)", s.pictureEverySec) { s.pictureEverySec = it }
        switch("Range in the text line", s.rangeText) { s.rangeText = it }
        switch("Set the MID clock (first connection after 2 h off)", s.clock) { s.clock = it }

        header("QUESTIONS TO THE MID")
        val rg = RadioGroup(this).also { col.addView(it) }
        listOf("all" to "All values (13)", "range" to "Only speed, odometer, battery %", "none" to "None (only writes)").forEach { (k, label) ->
            rg.addView(RadioButton(this).apply {
                text = label; setTextColor(fg); id = View.generateViewId(); isChecked = s.questions == k
                setOnCheckedChangeListener { _, on -> if (on) { s.questions = k; Log.ev("Setting: questions = $k") } }
            })
        }
        number("Pause between commands (ms)", s.gapMs) { s.gapMs = it.coerceAtLeast(50) }

        header("CONNECTION")
        number("Reconnect if no answer for (s, 0 = never)", s.silenceSec) { s.silenceSec = it }
        number("Stop after no connection for (min, 0 = never)", s.giveUpMin) { s.giveUpMin = it }
        switch("Accept the MID's pairing request automatically", s.autoPair) { s.autoPair = it }
        val pr = row()
        text(pr, "Pairing PIN (if the MID asks for one)", 15f).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        EditText(this).apply {
            setText(s.pairPin); inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(fg); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, has -> if (!has) { s.pairPin = text.toString(); Log.ev("Setting: pairing PIN = ${s.pairPin}") } }
        }.also { pr.addView(it) }

        header("LEARNED RANGE")
        button(row(), "Forget learned range") {
            AlertDialog.Builder(this).setMessage("Start learning km per 1% again?").setPositiveButton("Forget") { _, _ ->
                synchronized(Bike.range) { Bike.range.reset(); s.rangeState = Bike.range.save() }; Log.ev("Learned range forgotten")
            }.setNegativeButton("Cancel", null).show()
        }

        header("CONSOLE (any command, e.g. AT+SOC? or AT+NAV=\"HELLO\")")
        val cr = row()
        val input = EditText(this).apply {
            hint = "AT+..."; setTextColor(fg); setHintTextColor(muted); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        }.also { cr.addView(it) }
        button(cr, "Send") {
            val c = input.text.toString().trim()
            when {
                c.isEmpty() -> {}
                c.uppercase(Locale.US).startsWith("AT+ODO=") -> toast("Writing the odometer is not allowed")
                !Bike.connected -> toast("Not connected")
                else -> { Bike.outbox.add(c + "\r\n"); Log.ev("Console: $c"); input.setText("") }
            }
        }

        header("LOG (everything sent and received, kept when the app is closed)")
        val lr = row()
        button(lr, "Copy events") { copy(Log.tail(this, 5000, eventsOnly = true), "events") }
        button(lr, "Copy all (last 3000)") { copy(Log.tail(this, 3000, eventsOnly = false), "log") }
        val lr2 = row()
        button(lr2, "Save full log to Downloads") { saveLog() }
        button(lr2, "Clear log") {
            AlertDialog.Builder(this).setMessage("Delete the whole log?").setPositiveButton("Delete") { _, _ -> Log.clear(this); Log.init(this) }
                .setNegativeButton("Cancel", null).show()
        }
        logView = text(col, "", 10f, muted, mono = true)
    }

    private fun copy(t: String, what: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Gesits MID $what", t))
        toast("Copied ${t.lines().size} lines")
    }

    private fun saveLog() {
        if (Build.VERSION.SDK_INT < 29) { toast("Needs Android 10 or newer - use Copy instead"); return }
        try {
            val name = "gesits-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GesitsMID")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("no file")
            contentResolver.openOutputStream(uri)?.use { it.write(Log.all(this).toByteArray()) }
            toast("Saved: Download/GesitsMID/$name")
            Log.ev("Log saved to Download/GesitsMID/$name")
        } catch (e: Exception) { toast("Could not save: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun chooseBike() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devs = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        if (devs.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No paired devices. Pair \"Gesits\" in the phone's Bluetooth settings first.")
                .setPositiveButton("Bluetooth settings") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }.show()
            return
        }
        val names = devs.map { (it.name ?: "?") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose the bike").setItems(names) { _, w ->
            s.address = devs[w].address; s.name = devs[w].name; s.method = null
            Log.ev("Bike chosen: ${devs[w].name} ${devs[w].address}")
        }.show()
    }

    @SuppressLint("MissingPermission")
    private fun pairBike() {
        val addr = s.address ?: run { chooseBike(); return }
        val dev = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(addr)
        val r = try { dev.createBond() } catch (e: Exception) { Log.ev("Pair: ${e.message}"); false }
        Log.ev("Pair bike tapped: bond ${MidService.bondName(dev.bondState)}, createBond() = $r")
        toast(if (r) "Pairing started - accept the request" else "Pairing not started (already paired? see log)")
    }

    @SuppressLint("BatteryLife")
    private fun batteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already exempt"); return }
        try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        rangeView.text = (Bike.rangeKm?.let { String.format(Locale.US, "%.0f", it) } ?: "--") + " km"
        rangeInfo.text = synchronized(Bike.range) { Bike.range.describe() }
        fun v(k: String) = Bike.value(k) ?: "--"
        values.text = "Battery ${Bike.soc?.toInt() ?: "--"}%   Speed ${Bike.spd?.toInt() ?: "--"} km/h\nOdometer ${Bike.odo ?: "--"} km   MID range ${v("RANGE")}\n" +
            "Current ${v("IBAT")} A   Voltage ${v("VBAT")} V   Mode ${v("MODE")}   Park ${v("PARK")}\n" +
            "Temps: battery ${v("TBAT")}  motor ${v("TMOT")}  inverter ${v("TINV")}   ERR ${v("ERR")}"
        val heard = if (Bike.lastRxAt == 0L) "never" else "${(System.currentTimeMillis() - Bike.lastRxAt) / 1000} s ago"
        statusView.text = (if (Bike.connected) "● Connected" else if (Bike.running) "○ " + Bike.status else "○ Not connected") +
            "\n" + (s.name ?: "no bike chosen") + (s.address?.let { " $it" } ?: "") + "   last answer $heard   TX ${Bike.tx} RX ${Bike.rx}"
        statusView.setTextColor(if (Bike.connected) green else muted)
        connectBtn.text = if (Bike.running) "Disconnect" else "Connect"
        val l = Log.recent().takeLast(120).reversed().joinToString("\n")
        if (l != lastLogShown) { lastLogShown = l; logView.text = l }
    }
}
