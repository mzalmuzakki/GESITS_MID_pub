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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen with a bottom navigation bar: Home, MID, Trips, Tools.
 * Pages are built once, on first use, and only the visible page is refreshed (twice a second).
 */
@SuppressLint("MissingPermission", "SetTextI18n")
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var content: FrameLayout
    private lateinit var statusChip: TextView
    private val tabs = LinkedHashMap<String, TextView>()
    private val pages = HashMap<String, ScrollView>()
    private var current = "home"
    private var tripsPage: TripsPage? = null
    private val pickPicture = 42
    private val enableBt = 43

    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val demo = intent?.getBooleanExtra("demo", false) == true &&
            android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
        L.init(if (demo) intent.getStringExtra("lang") ?: settings.language else settings.language)
        Bike.loadRange(settings); Bike.loadTrip(settings); Bike.loadBattery(settings)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.CARD
        setContentView(shell())

        // Demo data for screenshots: only with USB debugging on, and kept in memory only (nothing is saved).
        if (demo) {
            Demo.load()
            val page = if (intent.getBooleanExtra("nav", false)) {
                Bike.nav = NavParser.parse("200 m", if (L.indo) "Belok kiri ke Jl. Sudirman" else "Turn left onto Jl. Sudirman", "ETA 14:32")
                Bike.navAt = System.currentTimeMillis(); ScreenRenderer.Page.NAV
            } else ScreenRenderer.Page.MAIN
            Bike.lastFrame = synchronized(Bike.trip) { ScreenRenderer.render(Bike.telemetry, Bike.rangeKm(), settings.margins, page, Bike.trip) }
            when (val o = intent.getStringExtra("open")) {
                "diagnosis" -> startActivity(Intent(this, DiagnosisActivity::class.java))
                "speedo" -> startActivity(Intent(this, SpeedoActivity::class.java))
                null -> show("home")
                else -> show(o)
            }
            if (current != intent.getStringExtra("open")) show("home")
        } else {
            show("home")
            if (savedInstanceState == null) autoConnect()
        }
    }

    /**
     * Connect to the saved bike when the app opens. Never asks for permissions by itself (that happens when
     * you tap Connect the first time); if Bluetooth is off, Android's own "turn on Bluetooth?" prompt is shown.
     */
    private fun autoConnect() {
        if (!settings.autoConnect || Bike.running || settings.address == null || !hasPermissions()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (adapter.isEnabled) { MidService.start(this); return }
        try { startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), enableBt) }
        catch (_: Exception) { toast("Turn Bluetooth on to connect to the bike") }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onStart() { super.onStart(); if (Bike.running) Bike.log("App opened (" + (if (Bike.connected) "connected" else "not connected") + ")") }
    override fun onStop() { super.onStop(); if (Bike.running) Bike.log("App in background (" + (if (Bike.connected) "connected" else "not connected") + ")") }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    // ================================================================ shell + navigation
    private fun shell(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(8))
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "Gesits MID"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.TEXT)
        })
        val by = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        by.addView(TextView(this).apply { text = "by "; textSize = 11f; setTextColor(Ui.MUTED) })
        by.addView(ImageView(this).apply {
            setImageResource(R.drawable.cakra_horizontal); adjustViewBounds = true; contentDescription = "CAKRA Labs"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)))
        titleBox.addView(by)
        top.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusChip = TextView(this).apply { textSize = 13f; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        top.addView(statusChip)
        root.addView(top)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.CARD); setPadding(0, dp(4), 0, dp(4)) }
        listOf("home" to "🏠\nHome", "mid" to "🖥️\nMID", "trips" to "📊\nTrips", "tools" to "🛠️\nTools")
            .forEach { (key, label) ->
                tabs[key] = TextView(this).apply {
                    text = label.substringBefore('\n') + "\n" + L.t(label.substringAfter('\n')); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener { show(key) }
                }.also { nav.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            }
        root.addView(nav)
        return root
    }

    private fun show(key: String) {
        if (key !in tabs) return
        current = key
        tabs.forEach { (k, v) ->
            v.setTextColor(if (k == key) Ui.GREEN else Ui.MUTED)
            v.setTypeface(null, if (k == key) Typeface.BOLD else Typeface.NORMAL)
        }
        val page = pages.getOrPut(key) {
            when (key) { "home" -> homePage(); "mid" -> midPage(); "trips" -> tripsPageView(); else -> toolsPage() }
        }
        if (key == "trips") tripsPage?.build(page.getChildAt(0) as LinearLayout)   // fresh numbers each visit
        content.removeAllViews(); content.addView(page)
        refresh()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, L.t(s), Toast.LENGTH_LONG).show()

    // ================================================================ permissions + bike selection
    private fun neededPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    private fun hasPermissions() = neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Asks only when needed (first time you choose or connect the bike), never on app start. */
    private fun requireReady(): Boolean {
        if (!hasPermissions()) {
            requestPermissions(neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray(), 1)
            toast("Allow Bluetooth access, then tap again"); return false
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { toast("Turn Bluetooth on first"); return false }
        return true
    }

    private fun chooseClassic() {
        if (!requireReady()) return
        val devices = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
            .sortedByDescending { (it.name ?: "").contains("GESITS", ignoreCase = true) }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(L.t("No paired devices"))
                .setMessage(L.t("Pair the bike first: ignition ON, press the Bluetooth button on the left handlebar, then pair it in Android Bluetooth settings."))
                .setPositiveButton(L.t("Open Bluetooth settings")) { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(L.t("Cancel"), null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(L.t("Choose your bike"))
            .setItems(devices.map { "${it.name ?: "(no name)"}\n${it.address}" }.toTypedArray()) { _, i -> pick(devices[i], "classic") }.show()
    }

    private fun chooseBle() {
        if (!requireReady()) return
        val scanner = getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: run { toast("BLE not available"); return }
        val found = LinkedHashMap<String, BluetoothDevice>(); val names = HashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName ?: result.device.name ?: return
                found[result.device.address] = result.device; names[result.device.address] = n
            }
        }
        val wait = AlertDialog.Builder(this).setTitle(L.t("Searching…")).setMessage(L.t("Looking for GESITS for 8 seconds")).setCancelable(false).show()
        scanner.startScan(cb)
        ui.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            wait.dismiss()
            val list = found.values.sortedByDescending { names[it.address] == Mid.BLE_NAME }
            if (list.isEmpty()) { toast("Nothing found. Ignition on? Press the MID Bluetooth button."); return@postDelayed }
            AlertDialog.Builder(this).setTitle(L.t("Choose GESITS"))
                .setItems(list.map { "${names[it.address]}\n${it.address}" }.toTypedArray()) { _, i -> pick(list[i], "ble", names[list[i].address]) }.show()
        }, 8000)
    }

    private fun pick(d: BluetoothDevice, type: String, name: String? = null) {
        settings.address = d.address
        settings.deviceName = name ?: d.name ?: d.address
        settings.linkType = type
        pages.remove("home"); show("home")
        if (Bike.running) { MidService.stop(this); ui.postDelayed({ MidService.start(this) }, 800) } else MidService.start(this)
    }

    private fun chooseBikeDialog() = AlertDialog.Builder(this).setTitle(L.t("How to connect?"))
        .setItems(arrayOf(L.t("Bluetooth Classic (paired, recommended)"), L.t("Bluetooth LE (scan for GESITS)"))) { _, i -> if (i == 0) chooseClassic() else chooseBle() }
        .show()

    // ================================================================ HOME
    private lateinit var connTitle: TextView
    private lateinit var connSub: TextView
    private lateinit var connBtn: android.widget.Button
    private lateinit var alertCard: LinearLayout
    private lateinit var alertText: TextView
    private val live = HashMap<String, TextView>()
    private lateinit var tripText: TextView
    private lateinit var chargeCard: LinearLayout
    private lateinit var chargeText: TextView

    private fun homePage(): ScrollView {
        val (sv, col) = Ui.page(this)
        if (settings.address == null) {
            val c = Ui.card(this, col, "SET UP YOUR BIKE", Ui.GREEN)
            Ui.text(this, c, "1.  Ignition ON, then press the Bluetooth button on the left handlebar.\n" +
                "2.  Choose how the phone connects. Classic is the usual way (pair the bike in Android settings first).", 14f)
            Ui.primary(this, c, "Bluetooth Classic (paired)") { chooseClassic() }
            Ui.secondary(this, c, "Bluetooth LE (scan for GESITS)") { chooseBle() }
            Ui.text(this, c, "This app only talks Bluetooth: it has no internet access.", 12f, Ui.MUTED)
        }
        val cc = Ui.card(this, col)
        connTitle = Ui.text(this, cc, "", 20f, bold = true)
        connSub = Ui.text(this, cc, "", 13f, Ui.MUTED)
        val r = Ui.row(this, cc)
        connBtn = Ui.primary(this, r, "Connect", 2f) {
            if (Bike.running) MidService.stop(this) else if (settings.address == null) chooseBikeDialog() else if (requireReady()) MidService.start(this)
        }
        Ui.secondary(this, r, "Change bike", 1f) { chooseBikeDialog() }

        alertCard = Ui.card(this, col, "ATTENTION", Ui.RED)
        alertText = Ui.text(this, alertCard, "", 14f)
        alertCard.visibility = View.GONE

        chargeCard = Ui.card(this, col, "CHARGING", Ui.GREEN)
        chargeText = Ui.text(this, chargeCard, "", 15f)
        Ui.text(this, chargeCard, "Notify me at", 12f, Ui.MUTED)
        Ui.segmented(this, chargeCard, listOf("80" to "80%", "90" to "90%", "100" to "100%"), settings.chargeTarget.toString()) {
            settings.chargeTarget = it.toInt()
        }
        chargeCard.visibility = View.GONE

        val lc = Ui.card(this, col, "LIVE")
        var row = Ui.row(this, lc)
        live["SOC"] = Ui.tile(this, row, "BATTERY", 26f); live["RANGE"] = Ui.tile(this, row, "RANGE", 26f)
        row = Ui.row(this, lc)
        live["PWR"] = Ui.tile(this, row, "POWER"); live["SPD"] = Ui.tile(this, row, "SPEED")
        row = Ui.row(this, lc)
        live["MODE"] = Ui.tile(this, row, "MODE", 18f); live["GEAR"] = Ui.tile(this, row, "GEAR", 18f); live["HEALTH"] = Ui.tile(this, row, "HEALTH", 18f)
        row = Ui.row(this, lc)
        live["TBAT"] = Ui.tile(this, row, "BATTERY °C", 18f); live["TMOT"] = Ui.tile(this, row, "MOTOR °C", 18f); live["TINV"] = Ui.tile(this, row, "INVERTER °C", 18f)

        val q = Ui.row(this, col)
        Ui.primary(this, q, "Speedometer", 1f) { startActivity(Intent(this, SpeedoActivity::class.java)) }
        Ui.secondary(this, q, "Diagnosis", 1f) { startActivity(Intent(this, DiagnosisActivity::class.java)) }

        val tc = Ui.card(this, col, "CURRENT TRIP")
        tripText = Ui.text(this, tc, "", 15f)
        Ui.secondary(this, tc, "Reset trip") {
            synchronized(Bike.trip) { Bike.trip.reset(); settings.tripState = Bike.trip.save() }; toast("Trip reset")
        }
        return sv
    }

    private fun refreshHome() {
        if (!::connTitle.isInitialized) return
        val name = settings.deviceName?.let { "$it · ${if (settings.linkType == "ble") "Bluetooth LE" else "Bluetooth Classic"}" } ?: L.t("No bike chosen yet")
        connTitle.text = when {
            Bike.connected -> "● " + L.t("Connected")
            Bike.running -> "○ " + L.t("Connecting…")
            else -> "○ " + L.t("Not connected")
        }
        connTitle.setTextColor(if (Bike.connected) Ui.GREEN else if (Bike.running) Ui.AMBER else Ui.MUTED)
        connSub.text = name + (Bike.telemetry.raw["VER"]?.let { "\n" + L.t("MID version") + " $it" } ?: "") +
            (if (Bike.running && !Bike.connected) "\n" + Bike.status else "")
        connBtn.text = L.t(if (Bike.running) "Disconnect" else "Connect")

        val t = Bike.telemetry
        val alerts = mutableListOf<String>()
        if (t.errors.isNotEmpty()) alerts += L.t("MID error") + ": " + t.errors.joinToString()
        t.f("SOC")?.let { if (it <= settings.socLow) alerts += L.t("Battery low") + ": ${it.toInt()}%" }
        if (Bike.running && !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            alerts += L.t("The phone may stop the connection in the background. Tools → Keep connected.")
        alertCard.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        alertText.text = alerts.joinToString("\n")

        val charging = synchronized(Bike.battery) { Bike.battery.charging }
        chargeCard.visibility = if (charging) View.VISIBLE else View.GONE
        if (charging) {
            val cap = estimateCapacityKwh(RideStore.all(this))
            val now = System.currentTimeMillis()
            val rate = synchronized(Bike.battery) { Bike.battery.chargeRatePctPerHour(now, t.powerW, cap) }
            val mins = synchronized(Bike.battery) { Bike.battery.minutesTo(settings.chargeTarget.toFloat(), rate) }
            val full = synchronized(Bike.battery) { Bike.battery.minutesTo(100f, rate) }
            fun hm(m: Int?) = m?.let { String.format(Locale.US, "%d:%02d", it / 60, it % 60) } ?: "--"
            chargeText.text = String.format(Locale.US, "%s%%  ·  %.1f kW  ·  %s %%/h\n%d%%: %s   ·   100%%: %s",
                t.raw["SOC"] ?: "--", -(t.powerW ?: 0f) / 1000f, rate?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                settings.chargeTarget, hm(mins), hm(full))
        }

        fun v(k: String) = t.raw[k] ?: "--"
        live["SOC"]!!.text = v("SOC") + "%"
        live["RANGE"]!!.text = (Bike.rangeKm()?.let { String.format(Locale.US, "%.0f", it) } ?: "--") + " km"
        val p = t.powerW
        live["PWR"]!!.text = (p?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "--") + " kW"
        live["PWR"]!!.setTextColor(if (p != null && p < 0) Ui.GREEN else Ui.TEXT)
        live["SPD"]!!.text = v("SPD") + " km/h"
        live["MODE"]!!.text = t.mode ?: "--"
        live["GEAR"]!!.text = when (t.gear) { "P" -> L.t("Park"); "D" -> L.t("Drive"); else -> "--" }
        live["HEALTH"]!!.text = v("SOH") + "%"
        live["TBAT"]!!.text = v("TBAT"); live["TMOT"]!!.text = v("TMOT"); live["TINV"]!!.text = v("TINV")
        tripText.text = synchronized(Bike.trip) {
            val tr = Bike.trip
            String.format(Locale.US, "%.1f km · %.0f Wh · %s · %s", tr.km, tr.energyWh,
                tr.whPerKm?.let { String.format(Locale.US, "%.0f Wh/km", it) } ?: "-- Wh/km",
                tr.kmPerPercent?.let { String.format(Locale.US, "%.2f km per 1%%", it) } ?: "-- km per 1%")
        }
    }

    // ================================================================ MID
    private lateinit var preview: ImageView
    private lateinit var previewInfo: TextView
    private lateinit var rangeInfo: TextView
    private lateinit var ackText: TextView
    private lateinit var navStatus: TextView

    private fun notificationAccessOn(): Boolean =
        (android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun midPage(): ScrollView {
        val (sv, col) = Ui.page(this)
        val pc = Ui.card(this, col, "ON THE MID NOW")
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        pc.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        previewInfo = Ui.text(this, pc, "", 12f, Ui.MUTED)

        val sc = Ui.card(this, col, "SHOW ON THE MID")
        lateinit var pagesBox: LinearLayout
        lateinit var pictureBox: LinearLayout
        val mainMode = if (settings.screenMode in listOf("dashboard", "picture", "off")) settings.screenMode else "dashboard"
        Ui.segmented(this, sc, listOf("dashboard" to "Live info", "picture" to "My picture", "off" to "Nothing"), mainMode) { m ->
            settings.screenMode = m
            pagesBox.visibility = if (m == "dashboard") View.VISIBLE else View.GONE
            pictureBox.visibility = if (m == "picture") View.VISIBLE else View.GONE
        }
        pagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { sc.addView(it) }
        Ui.text(this, pagesBox, "Pages", 12f, Ui.MUTED)
        Ui.segmented(this, pagesBox, listOf("auto" to "Auto", "main" to "Details", "trip" to "Trip", "big" to "Big", "rotate" to "Rotate"),
            settings.dashStyle) { settings.dashStyle = it }
        Ui.text(this, pagesBox, "Auto: big numbers while riding; details and trip when stopped.", 12f, Ui.MUTED)
        pictureBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { sc.addView(it) }
        Ui.secondary(this, pictureBox, "Choose picture…") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), pickPicture)
        }
        pagesBox.visibility = if (mainMode == "dashboard") View.VISIBLE else View.GONE
        pictureBox.visibility = if (mainMode == "picture") View.VISIBLE else View.GONE

        val tc = Ui.card(this, col, "TEXT LINE (20 CHARACTERS PER STEP)")
        val nav = Ui.input(this, tc, "e.g. Halo Gesits! Hati-hati di jalan", settings.navText)
        Ui.secondary(this, tc, "Save text") {
            settings.navText = nav.text.toString()
            toast(Mid.splitNav(settings.navText).joinToString(" | ").ifEmpty { "Text cleared" })
        }
        Ui.switchRow(this, tc, "Show today's date when empty", "e.g. 31 Oktober 2026", settings.navDate) { settings.navDate = it }

        val rc = Ui.card(this, col, "RANGE")
        Ui.switchRow(this, rc, "Adaptive range in the MID text line", "Learns your battery size and how much you use, by itself. Shown when you have no text of your own.", settings.rangeLine) { settings.rangeLine = it }
        rangeInfo = Ui.text(this, rc, "", 13f, Ui.MUTED)
        val rr = Ui.row(this, rc)
        Ui.secondary(this, rr, "Forget learned", 1f, Ui.RED) {
            AlertDialog.Builder(this).setTitle(L.t("Forget learned range?")).setMessage(L.t("Learning starts again. Useful after a battery change."))
                .setPositiveButton(L.t("Forget")) { _, _ -> synchronized(Bike.range) { Bike.range.reset(); Bike.energy.reset(); settings.rangeState = Bike.range.save(); settings.energyState = Bike.energy.save() } }
                .setNegativeButton(L.t("Cancel"), null).show()
        }
        Ui.switchRow(this, rc, "Also try to replace the MID's range figure", "On the G1 the MID puts its own number back instantly, so this only flickers.", settings.rangeOverride) { settings.rangeOverride = it }

        val ac = Ui.card(this, col, "SENT TO THE MID")
        ackText = Ui.text(this, ac, "", 13f, mono = true)

        val dc = Ui.card(this, col, "TURN-BY-TURN DIRECTIONS")
        Ui.switchRow(this, dc, "Show Google Maps directions on the MID",
            "Reads only Google Maps navigation notifications. Nothing else, nothing leaves the phone.", settings.navOn) { settings.navOn = it }
        navStatus = Ui.text(this, dc, "", 13f, Ui.MUTED)
        Ui.secondary(this, dc, "Allow notification access") {
            try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
        }

        val sw = Ui.card(this, col, "SPEED WARNING")
        Ui.text(this, sw, "Warn on the MID above this speed", 12f, Ui.MUTED)
        Ui.segmented(this, sw, listOf("0" to "Off", "50" to "50", "60" to "60", "70" to "70", "80" to "80"), settings.speedLimit.toString()) {
            settings.speedLimit = it.toInt()
        }

        val cc = Ui.card(this, col, "CLOCK")
        Ui.switchRow(this, cc, "Set the MID clock from this phone", "On connect and every minute", settings.syncTime) { settings.syncTime = it }

        val adv = Ui.collapsible(this, col, "Advanced: position & calibration")
        Ui.text(this, adv, "Send a grid, photograph the MID, and adjust the margins (pixels of the 320×128 picture) if the MID covers an edge.", 12f, Ui.MUTED)
        val g = Ui.row(this, adv)
        Ui.secondary(this, g, "Grid", 1f) { settings.screenMode = "grid"; toast("Grid goes to the MID in a few seconds") }
        Ui.secondary(this, g, "Large grid", 1f) { settings.screenMode = "gridLarge"; toast("Large grid goes to the MID in a few seconds") }
        val m = settings.margins
        val mr = Ui.row(this, adv)
        fun num(label: String, v: Int): android.widget.EditText {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            mr.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            return Ui.number(this, box, label, v.toFloat())
        }
        val eL = num("Left", m.left); val eT = num("Top", m.top); val eR = num("Right", m.right); val eB = num("Bottom", m.bottom)
        Ui.primary(this, adv, "Save position & show live info") {
            fun v(e: android.widget.EditText, max: Int) = (e.text.toString().toFloatOrNull()?.toInt() ?: 0).coerceIn(0, max)
            settings.margins = ScreenRenderer.Margins(v(eL, 280), v(eT, 100), v(eR, 280), v(eB, 100))
            settings.screenMode = "dashboard"; pages.remove("mid"); show("mid"); toast("Saved")
        }
        return sv
    }


    private fun refreshMid() {
        if (!::preview.isInitialized) return
        Bike.lastFrame?.let { if (preview.tag !== it) { preview.setImageBitmap(it); preview.tag = it } }
        previewInfo.text = Bike.lastFrameInfo.ifEmpty { L.t(if (Bike.connected) "Waiting for the first picture…" else "Connect to the bike to send pictures") }
        val now = System.currentTimeMillis()
        // Some MIDs answer writes with the command's name; others (seen on a G1) answer only questions.
        val confirms = Bike.ackAt.isNotEmpty()
        ackText.text = listOf("MAPS" to "picture", "TIME" to "clock", "NAV" to "text", "TRIP" to "range").joinToString("\n") { (k, label) ->
            val sent = Bike.sentAt[k]; val ack = Bike.ackAt[k]
            val state = when {
                sent == null -> "-"
                !confirms -> L.t("sent") + " " + ((now - sent) / 1000) + " s"
                ack != null && ack >= sent -> "✓ " + ((now - ack) / 1000) + " s"
                ack != null -> "…"
                now - sent < 6000 -> "…"
                else -> "✗ " + L.t("no reply")
            }
            String.format(Locale.US, "%-8s %s", L.t(label), state)
        } + if (!confirms && Bike.sentAt.isNotEmpty()) "\n" + L.t("This MID doesn't confirm writes; check its screen.") else ""
        navStatus.text = when {
            !notificationAccessOn() -> "⚠ " + L.t("Off: tap the button, then turn on \"Gesits MID directions\".")
            Bike.navFresh() -> Bike.nav!!.let { "→ ${it.distance ?: ""} ${it.street}" }
            else -> "✓ " + L.t("Ready: start navigation in Google Maps")
        }
        val desc = synchronized(Bike.range) {
            val r = Bike.range
            String.format(Locale.US, if (L.indo) "%.2f km per 1%% baterai (%s: %.1f km dari %.0f%% baterai)" else "%.2f km per 1%% battery (%s: %.1f km over %.0f%% of battery)",
                r.kmPerPercent, if (r.fullyLearned) L.t("measured") else L.t("learning"), r.learnedKm, r.learnedPercent) +
                "\n" + Bike.energy.describe(L.indo)
        }
        rangeInfo.text = desc + (Bike.rangeKm()?.let { String.format(Locale.US, if (L.indo) " → %.0f km sekarang" else " → %.0f km now", it) } ?: "") + "\n" +
            (Bike.telemetry.f("RANGE")?.let { String.format(Locale.US, L.t("The MID's own range figure: %.0f km (it assumes the original battery and can't be changed over Bluetooth)."), it) }
                ?: L.t("The MID's own range figure assumes the original battery and can't be changed over Bluetooth."))
    }

    // ================================================================ TRIPS
    private fun tripsPageView(): ScrollView {
        val (sv, _) = Ui.page(this)
        tripsPage = TripsPage(this, settings)
        return sv
    }

    // ================================================================ TOOLS
    private lateinit var parkInfo: TextView
    private lateinit var bgInfo: TextView
    private lateinit var logView: TextView

    private fun toolsPage(): ScrollView {
        val (sv, col) = Ui.page(this)

        val mc = Ui.card(this, col, "MAPS (OPENS GOOGLE MAPS)")
        val dest = Ui.input(this, mc, "Destination, e.g. Monas Jakarta")
        val mr = Ui.row(this, mc)
        Ui.primary(this, mr, "Navigate", 1f) {
            val q = Uri.encode(dest.text.toString().trim())
            if (q.isEmpty()) toast("Type a destination first")
            else openMaps(Uri.parse("google.navigation:q=$q&mode=l"), Uri.parse("geo:0,0?q=$q"))   // mode=l: two-wheeler
        }
        Ui.secondary(this, mr, "SPKLU near me", 1f) { openMaps(Uri.parse("geo:0,0?q=SPKLU"), null) }

        val pk = Ui.card(this, col, "PARKING")
        Ui.switchRow(this, pk, "Remember where I parked", "Uses phone GPS when you park or switch off. Stays on this phone.", settings.rememberParking) { b ->
            settings.rememberParking = b
            if (b && !Parking.hasPermission(this))
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        }
        parkInfo = Ui.text(this, pk, "", 13f, Ui.MUTED)
        Ui.secondary(this, pk, "Show parked bike in Maps") {
            if (settings.parkTime == 0L) toast("No parked location saved yet")
            else { val ll = "${settings.parkLat},${settings.parkLon}"; openMaps(Uri.parse("geo:$ll?q=$ll(Gesits)"), null) }
        }

        val al = Ui.card(this, col, "ALARMS")
        Ui.switchRow(this, al, "Phone notifications", "Low battery, hot battery (>45°C) or motor (>85°C), battery health <75%, MID errors",
            settings.alarmsOn) { settings.alarmsOn = it }
        Ui.text(this, al, "Low battery below", 12f, Ui.MUTED)
        Ui.segmented(this, al, listOf("10" to "10%", "15" to "15%", "20" to "20%", "30" to "30%"), settings.socLow.toInt().toString()) {
            settings.socLow = it.toFloat()
        }

        val bg = Ui.card(this, col, "KEEP CONNECTED")
        Ui.switchRow(this, bg, "Connect automatically", "As soon as the app is opened (once a bike is chosen)", settings.autoConnect) {
            settings.autoConnect = it
        }
        bgInfo = Ui.text(this, bg, "", 14f)
        Ui.text(this, bg, "Some phones (Xiaomi, OPPO, vivo, Realme, Samsung) close Bluetooth apps in the background. " +
            "Allow this app to run without battery restrictions; on Xiaomi/OPPO/vivo also turn on Autostart in App info.", 12f, Ui.MUTED)
        val br = Ui.row(this, bg)
        Ui.primary(this, br, "Allow background", 1f) {
            try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        Ui.secondary(this, br, "App info", 1f) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        Ui.text(this, bg, "Stop automatically when the bike has been off for", 12f, Ui.MUTED)
        Ui.segmented(this, bg, listOf("30" to "30 min", "60" to "1 h", "120" to "2 h", "0" to "Never"), settings.autoStopMin.toString()) {
            settings.autoStopMin = it.toInt()
        }

        val lg = Ui.card(this, col, "LANGUAGE")
        Ui.segmented(this, lg, listOf("auto" to "Phone", "en" to "English", "id" to "Indonesia"), settings.language) {
            settings.language = it; recreate()
        }

        val wd = Ui.card(this, col, "HOME-SCREEN WIDGET")
        Ui.text(this, wd, "Long-press your home screen → Widgets → Gesits MID to see battery and range without opening the app.", 13f)

        val pv = Ui.card(this, col, "PRIVACY & PERMISSIONS")
        Ui.text(this, pv, "• No internet access: nothing ever leaves this phone.\n" +
            "• Bluetooth: to talk to the bike.\n" +
            "• Notifications: connection status and alarms.\n" +
            "• Location: only if you turn on parking (and for BLE scans on Android 8–11).\n" +
            "• Notification access: only if you turn on directions; only Google Maps is read.\n" +
            "• Your rides, learned range and settings stay in the app's private storage and are not backed up.", 13f)
        Ui.secondary(this, pv, "Delete all my data", color = Ui.RED) {
            AlertDialog.Builder(this).setTitle(L.t("Delete all data?"))
                .setMessage(L.t("Rides, learned range, trip, parking location, chosen bike and settings will be erased."))
                .setPositiveButton(L.t("Delete")) { _, _ ->
                    MidService.stop(this); RideStore.clear(this)
                    getSharedPreferences("settings", MODE_PRIVATE).edit().clear().apply()
                    deleteFile("picture.b64")
                    synchronized(Bike.range) { Bike.range.reset(); Bike.energy.reset() }; synchronized(Bike.trip) { Bike.trip.clear() }
                    synchronized(Bike.battery) { Bike.battery.clear() }
                    recreate()
                }.setNegativeButton(L.t("Cancel"), null).show()
        }

        val adv = Ui.collapsible(this, col, "Advanced: console & log")
        Ui.text(this, adv, "Send your own AT command, e.g. AT+SPD?. Queries ending in ? only read. Odometer writes are blocked.", 12f, Ui.MUTED)
        val cmd = Ui.input(this, adv, "AT+VER?")
        val cr = Ui.row(this, adv)
        Ui.secondary(this, cr, "Send", 1f) {
            val c = cmd.text.toString().trim()
            when {
                !Bike.connected -> toast("Connect to the bike first")
                c.isEmpty() -> {}
                c.uppercase().startsWith("AT+ODO=") -> toast("Odometer writes are blocked in this app")
                else -> Bike.outbox.add(c + "\r\n")
            }
        }
        Ui.secondary(this, cr, "Probe", 1f) {
            if (!Bike.connected) toast("Connect to the bike first")
            else { Mid.PROBE_KEYS.forEach { Bike.outbox.add(Mid.query(it)) }; toast("Sent ${Mid.PROBE_KEYS.size} read-only queries") }
        }
        logView = Ui.text(this, adv, "", 11f, Ui.MUTED, mono = true)
        Ui.secondary(this, adv, "Copy log") {
            val text = synchronized(Bike.log) { Bike.log.joinToString("\n") }
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Gesits MID log", text))
            toast("Log copied")
        }
        val ab = Ui.card(this, col, "ABOUT")
        ab.addView(ImageView(this).apply {
            setImageResource(R.drawable.cakra_stacked); adjustViewBounds = true; contentDescription = "CAKRA Labs logo"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(110)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(4), 0, dp(10))
        })
        Ui.text(this, ab, "Gesits MID v" + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" },
            16f, bold = true).gravity = Gravity.CENTER
        Ui.text(this, ab, "Made by CAKRA Labs · Universitas Pertamina\nAn open, community tool - not an official Gesits app.",
            13f, Ui.MUTED).gravity = Gravity.CENTER
        Ui.secondary(this, ab, "cakra-labs.id") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cakra-labs.id/"))) } catch (_: Exception) { toast("No browser found") }
        }
        return sv
    }

    private fun refreshTools() {
        if (!::parkInfo.isInitialized) return
        parkInfo.text = if (settings.parkTime == 0L) "No parked location saved" else
            "Saved " + SimpleDateFormat("EEE d MMM, HH:mm", Locale("id", "ID")).format(Date(settings.parkTime))
        val ok = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        bgInfo.text = if (ok) "✓ Battery restriction is off" else "⚠ Battery restriction is ON - the phone may cut the connection"
        bgInfo.setTextColor(if (ok) Ui.GREEN else Ui.AMBER)
        logView.text = synchronized(Bike.log) { Bike.log.toList().takeLast(40).joinToString("\n") }
    }

    private fun openMaps(primary: Uri, fallback: Uri?) {
        try { startActivity(Intent(Intent.ACTION_VIEW, primary)) }
        catch (_: Exception) {
            try { if (fallback != null) startActivity(Intent(Intent.ACTION_VIEW, fallback)) else toast("No maps app found") }
            catch (_: Exception) { toast("No maps app found") }
        }
    }

    // ================================================================ refresh, results
    private fun refresh() {
        statusChip.text = if (Bike.connected) "● " + L.t("Connected") else if (Bike.running) "○ " + L.t("Connecting") else "○ " + L.t("Offline")
        statusChip.setTextColor(if (Bike.connected) Ui.GREEN else if (Bike.running) Ui.AMBER else Ui.MUTED)
        statusChip.background = Ui.rounded(Ui.CARD2, dp(14).toFloat())
        when (current) { "home" -> refreshHome(); "mid" -> refreshMid(); "tools" -> refreshTools() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == enableBt) { if (resultCode == RESULT_OK && !Bike.running) MidService.start(this); return }
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            pickPicture -> loadPicture(uri)
            TripsPage.EXPORT_REQ -> tripsPage?.export(uri)
        }
    }

    private fun loadPicture(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { toast("That file is not a picture"); return }
            var sample = 1   // decode small: never load a full-size photo into memory
            while (bounds.outWidth / (sample * 2) >= Mid.FRAME_W * 2 && bounds.outHeight / (sample * 2) >= Mid.FRAME_H * 2) sample *= 2
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: run { toast("Could not read that picture"); return }
            val frame = ScreenRenderer.cropToFrame(src)
            src.recycle()
            val b64 = ScreenRenderer.toMapsBase64(frame) ?: run { toast("Picture too detailed for the MID; try a simpler one"); return }
            openFileOutput("picture.b64", MODE_PRIVATE).use { it.write(b64.toByteArray()) }
            Bike.lastFrame = frame
            settings.screenMode = "picture"
            pages.remove("mid"); show("mid")
            toast("Picture ready - it will be shown on the MID")
        } catch (e: Exception) {
            toast("Picture error: ${e.message}")
        }
    }
}
