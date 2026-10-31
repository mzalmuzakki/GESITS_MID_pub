package io.github.mzalmuzakki.gesitsmid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

@SuppressLint("MissingPermission", "SetTextI18n")
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var frameInfo: TextView
    private lateinit var preview: ImageView
    private lateinit var logView: TextView
    private lateinit var rangeInfo: TextView
    private val pickPicture = 42

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        Bike.loadRange(settings)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        askPermissions()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    // ------------------------------------------------------------------ permissions
    private fun neededPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermissions() = neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun askPermissions() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun requireReady(): Boolean {
        if (!hasPermissions()) { askPermissions(); toast("Allow Bluetooth permission first"); return false }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { toast("Turn Bluetooth on first"); return false }
        return true
    }

    // ------------------------------------------------------------------ choosing the bike
    private fun chooseClassic() {
        if (!requireReady()) return
        val devices = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
            .sortedByDescending { (it.name ?: "").contains("GESITS", ignoreCase = true) }
        if (devices.isEmpty()) { toast("No paired devices. Pair the bike in Android Bluetooth settings first."); return }
        val labels = devices.map { "${it.name ?: "(no name)"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Paired devices (Classic)")
            .setItems(labels) { _, i -> pick(devices[i], "classic") }.show()
    }

    private fun chooseBle() {
        if (!requireReady()) return
        val scanner = getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: run { toast("BLE not available"); return }
        val found = LinkedHashMap<String, BluetoothDevice>()
        val names = HashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val n = result.scanRecord?.deviceName ?: d.name ?: return
                found[d.address] = d; names[d.address] = n
            }
        }
        toast("Scanning BLE for 8 s...")
        scanner.startScan(cb)
        ui.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            val list = found.values.sortedByDescending { names[it.address] == Mid.BLE_NAME }
            if (list.isEmpty()) { toast("Nothing found. Ignition on? MID Bluetooth on?"); return@postDelayed }
            val labels = list.map { "${names[it.address]}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("BLE devices (pick GESITS)")
                .setItems(labels) { _, i -> pick(list[i], "ble", names[list[i].address]) }.show()
        }, 8000)
    }

    private fun pick(d: BluetoothDevice, type: String, name: String? = null) {
        settings.address = d.address
        settings.deviceName = name ?: d.name ?: d.address
        settings.linkType = type
        toast("Saved: ${settings.deviceName} (${if (type == "ble") "BLE" else "Classic"})")
        if (Bike.running) { MidService.stop(this); ui.postDelayed({ MidService.start(this) }, 800) }
    }

    // ------------------------------------------------------------------ picture
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPicture && resultCode == RESULT_OK) data?.data?.let { loadPicture(it) }
    }

    private fun loadPicture(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= Mid.FRAME_W * 2 && bounds.outHeight / (sample * 2) >= Mid.FRAME_H * 2) sample *= 2
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: run { toast("Could not read that picture"); return }
            val frame = ScreenRenderer.cropToFrame(src)
            val b64 = ScreenRenderer.toMapsBase64(frame) ?: run { toast("Picture too detailed for the MID; try a simpler one"); return }
            openFileOutput("picture.b64", MODE_PRIVATE).use { it.write(b64.toByteArray()) }
            Bike.lastFrame = frame
            settings.screenMode = "picture"
            toast("Picture ready (${b64.length} chars). It will be shown on the MID.")
            recreateUiState()
        } catch (e: Exception) {
            toast("Picture error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun header(root: LinearLayout, s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(60, 200, 110))
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun button(root: ViewGroup, s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setOnClickListener { onClick() }; root.addView(this)
    }

    private lateinit var modeGroup: RadioGroup

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            setBackgroundColor(Color.rgb(12, 14, 18))
        }
        root.addView(TextView(this).apply {
            text = "Gesits MID"; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Bluetooth only - this app has no internet permission."; setTextColor(Color.GRAY)
        })

        header(root, "Bike")
        status = TextView(this).apply { setTextColor(Color.WHITE) }
        root.addView(status)
        button(root, "Choose bike - Bluetooth Classic (paired)") { chooseClassic() }
        button(root, "Choose bike - Bluetooth LE (scan for GESITS)") { chooseBle() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        button(row, "Connect") { if (requireReady()) MidService.start(this) }
        button(row, "Disconnect") { MidService.stop(this) }
        root.addView(row)

        header(root, "Live data")
        live = TextView(this).apply { typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE); textSize = 15f }
        root.addView(live)

        header(root, "MID screen (map area)")
        modeGroup = RadioGroup(this)
        listOf("dashboard" to "My dashboard (live data)", "picture" to "My picture", "off" to "Off (don't send pictures)").forEachIndexed { i, (key, label) ->
            modeGroup.addView(RadioButton(this).apply { id = 100 + i; text = label; tag = key; setTextColor(Color.WHITE) })
        }
        modeGroup.setOnCheckedChangeListener { g, id -> settings.screenMode = g.findViewById<View>(id).tag as String }
        root.addView(modeGroup)
        button(root, "Choose picture...") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), pickPicture)
        }
        preview = ImageView(this).apply { adjustViewBounds = true; setPadding(0, dp(8), 0, dp(4)) }
        root.addView(preview)
        frameInfo = TextView(this).apply { setTextColor(Color.GRAY) }
        root.addView(frameInfo)

        header(root, "MID text line (20 characters per step)")
        val nav = EditText(this).apply {
            setText(settings.navText); hint = "e.g. Halo Gesits! Hati-hati di jalan"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(nav)
        button(root, "Save text (rotates every 5 s; empty = off)") {
            settings.navText = nav.text.toString(); toast("Saved: " + Mid.splitNav(settings.navText).joinToString(" | "))
        }

        header(root, "Options")
        root.addView(CheckBox(this).apply {
            text = "Set MID clock from phone (on connect + every minute)"; isChecked = settings.syncTime; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, b -> settings.syncTime = b }
        })

        header(root, "Adaptive range")
        root.addView(TextView(this).apply {
            text = "Learns your real km per 1% battery from the odometer while you ride (last ~60% of battery " +
                "used) and shows battery % x that on the MID's range."
            setTextColor(Color.GRAY)
        })
        rangeInfo = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6)) }
        root.addView(rangeInfo)
        root.addView(CheckBox(this).apply {
            text = "Show adaptive range on MID (AT+TRIP)"; isChecked = settings.sendRange; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, b -> settings.sendRange = b }
        })
        val kmpp = EditText(this).apply {
            setText(String.format(Locale.US, "%.2f", settings.kmPerPercent)); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(TextView(this).apply { text = "Starting value until enough is learned: km per 1% battery"; setTextColor(Color.GRAY) })
        root.addView(kmpp)
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        button(rangeRow, "Save starting value") {
            kmpp.text.toString().toFloatOrNull()?.takeIf { it > 0f }?.let {
                settings.kmPerPercent = it; synchronized(Bike.range) { Bike.range.priorKmPerPercent = it }; toast("Saved")
            }
        }
        button(rangeRow, "Forget learned range") {
            AlertDialog.Builder(this).setTitle("Forget learned range?")
                .setMessage("Starts learning again from the starting value. Useful after a battery change.")
                .setPositiveButton("Forget") { _, _ ->
                    synchronized(Bike.range) { Bike.range.reset(); settings.rangeState = Bike.range.save() }
                    toast("Range learning reset")
                }.setNegativeButton("Cancel", null).show()
        }
        root.addView(rangeRow)

        header(root, "Log")
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; setTextColor(Color.GRAY); textSize = 11f }
        root.addView(logView)

        recreateUiState()
        return ScrollView(this).apply { addView(root); setBackgroundColor(Color.rgb(12, 14, 18)) }
    }

    private fun recreateUiState() {
        val idx = listOf("dashboard", "picture", "off").indexOf(settings.screenMode).coerceAtLeast(0)
        modeGroup.check(100 + idx)
    }

    private fun refresh() {
        val name = settings.deviceName?.let { "$it (${if (settings.linkType == "ble") "BLE" else "Classic"})" } ?: "no bike chosen"
        status.text = "${Bike.status}\nBike: $name"
        val t = Bike.telemetry
        fun v(k: String, unit: String = "") = t.raw[k]?.let { "$it$unit" } ?: "--"
        val p = t.powerW?.let { String.format(Locale.US, "%.0f W", it) } ?: "--"
        live.text = buildString {
            appendLine("Speed     ${v("SPD", " km/h")}")
            appendLine("Battery   ${v("SOC", " %")}   health ${v("SOH", " %")}")
            appendLine("Power     $p   (${v("VBAT", " V")}, ${v("IBAT", " A")})")
            appendLine("Mode      ${t.mode ?: "--"}   gear ${t.gear ?: "--"}")
            appendLine("Range     ${Bike.rangeKm()?.let { String.format(Locale.US, "%.0f km", it) } ?: "--"}")
            appendLine("ODO       ${v("ODO", " km")}")
            appendLine("Temp      bat ${v("TBAT")}  motor ${v("TMOT")}  inv ${v("TINV")} °C")
            append("Errors    ${if (t.errorMask == null) "--" else t.errors.ifEmpty { listOf("none") }.joinToString()}")
        }
        Bike.lastFrame?.let { preview.setImageBitmap(it) }
        frameInfo.text = Bike.lastFrameInfo
        rangeInfo.text = synchronized(Bike.range) { Bike.range.describe() }
        logView.text = synchronized(Bike.log) { Bike.log.toList().takeLast(15).joinToString("\n") }
    }
}
