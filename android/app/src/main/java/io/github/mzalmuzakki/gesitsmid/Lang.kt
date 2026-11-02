package io.github.mzalmuzakki.gesitsmid

import java.util.Locale

/**
 * English / Bahasa Indonesia. English text is the key; [t] returns the Indonesian version when the
 * app language is Indonesian (phone language, or chosen in Tools). Unknown keys stay English.
 */
object L {
    @Volatile var indo = false

    /** "auto" follows the phone, "en" or "id" force a language. */
    fun init(setting: String) {
        indo = when (setting) { "id" -> true; "en" -> false; else -> Locale.getDefault().language in setOf("in", "id") }
    }

    fun t(s: String): String = if (indo) ID[s] ?: s else s

    /** The connection status line (set in English by the service, with names/numbers in it). */
    fun status(s: String): String {
        if (!indo) return s
        ID[s]?.let { return it }
        val pre = listOf(
            "Connecting (" to "Menghubungkan (", "Link open (" to "Tautan terbuka (", "Connected to " to "Terhubung ke ",
            "Connect failed: " to "Gagal terhubung: ", "Connection error: " to "Kesalahan koneksi: ",
            "Bike off for " to "Motor mati selama ",
        )
        for ((en, id) in pre) if (s.startsWith(en)) return id + s.substring(en.length)
            .replace("waiting for the MID to answer", "menunggu jawaban MID").replace(" - reconnecting", " - menghubungkan ulang")
            .replace(" min - stopped to save battery", " menit - dihentikan untuk menghemat baterai")
        return s
    }

    private val ID = mapOf(
        // navigation / shell
        "Home" to "Beranda", "Trips" to "Perjalanan", "Tools" to "Alat",
        "Connected" to "Terhubung", "Connecting" to "Menghubungkan", "Offline" to "Terputus",
        "Connecting…" to "Menghubungkan…", "Not connected" to "Tidak terhubung",
        // home
        "SET UP YOUR BIKE" to "ATUR MOTOR ANDA",
        "1.  Ignition ON, then press the Bluetooth button on the left handlebar.\n2.  Choose how the phone connects. Classic is the usual way (pair the bike in Android settings first)." to
            "1.  Nyalakan kontak, lalu tekan tombol Bluetooth di setang kiri.\n2.  Pilih cara HP terhubung. Classic adalah cara biasa (pasangkan motor di pengaturan Android dulu).",
        "Bluetooth Classic (paired)" to "Bluetooth Classic (sudah dipasangkan)",
        "Bluetooth LE (scan for GESITS)" to "Bluetooth LE (cari GESITS)",
        "Bluetooth Classic (paired, recommended)" to "Bluetooth Classic (sudah dipasangkan, disarankan)",
        "This app only talks Bluetooth: it has no internet access." to "Aplikasi ini hanya memakai Bluetooth: tidak punya akses internet.",
        "Connect" to "Hubungkan", "Disconnect" to "Putuskan", "Change bike" to "Ganti motor",
        "No bike chosen yet" to "Belum ada motor dipilih",
        "ATTENTION" to "PERHATIAN", "LIVE" to "LANGSUNG",
        "BATTERY" to "BATERAI", "RANGE" to "JARAK TEMPUH", "POWER" to "DAYA", "SPEED" to "KECEPATAN",
        "MODE" to "MODE", "GEAR" to "POSISI", "HEALTH" to "KESEHATAN",
        "BATTERY °C" to "BATERAI °C", "MOTOR °C" to "MOTOR °C", "INVERTER °C" to "INVERTER °C",
        "Park" to "Parkir", "Drive" to "Jalan",
        "Speedometer" to "Spidometer", "Diagnosis" to "Diagnosis",
        "CURRENT TRIP" to "PERJALANAN SAAT INI", "Reset trip" to "Reset perjalanan", "Trip reset" to "Perjalanan direset",
        "CHARGING" to "MENGISI DAYA", "Notify me at" to "Beri tahu saya di",
        "The phone may stop the connection in the background. Tools → Keep connected." to
            "HP bisa memutus koneksi di latar belakang. Alat → Tetap terhubung.",
        // MID tab
        "ON THE MID NOW" to "DI MID SEKARANG", "SHOW ON THE MID" to "TAMPILKAN DI MID",
        "Live info" to "Info langsung", "My picture" to "Gambar saya", "Nothing" to "Tidak ada",
        "Pages" to "Halaman", "Auto" to "Otomatis", "Details" to "Detail", "Trip" to "Perjalanan", "Big" to "Besar", "Rotate" to "Bergantian",
        "Auto: big numbers while riding; details and trip when stopped." to "Otomatis: angka besar saat berkendara; detail dan perjalanan saat berhenti.",
        "Choose picture…" to "Pilih gambar…",
        "TEXT LINE (20 CHARACTERS PER STEP)" to "BARIS TEKS (20 KARAKTER PER LANGKAH)",
        "e.g. Halo Gesits! Hati-hati di jalan" to "mis. Halo Gesits! Hati-hati di jalan",
        "Save text" to "Simpan teks", "Text cleared" to "Teks dihapus",
        "Pair the bike again: phone Bluetooth settings → Gesits." to "Pasangkan ulang motor: pengaturan Bluetooth HP → Gesits.",
        "SLOW DOWN" to "PELAN",
        "✓ Battery restriction is off" to "✓ Pembatasan baterai mati",
        "⚠ Battery restriction is ON - the phone may cut the connection" to "⚠ Pembatasan baterai AKTIF - HP bisa memutus koneksi",
        "The MID wasn't answering last time" to "MID tidak merespons terakhir kali",
        "After the MID stopped answering, the app only asks questions: no text, clock or pictures. Check the MID: if speed, turn signals and clock work normally, tap below." to
            "Setelah MID berhenti merespons, aplikasi hanya bertanya: tanpa teks, jam, atau gambar. Periksa MID: jika kecepatan, lampu sein, dan jam normal, ketuk di bawah.",
        "The MID works normally again" to "MID sudah normal lagi",
        "MID values look frozen" to "Nilai MID tampak macet",
        "MID values look frozen - stop safely, switch the bike off and on" to "Nilai MID tampak macet - berhenti dengan aman, matikan lalu nyalakan motor",
        "The MID's values looked frozen, so the app shows none of them and only asks questions: no text, clock or pictures. Check the MID: if speed, turn signals and clock work normally, tap below." to
            "Nilai MID tampak macet, jadi aplikasi tidak menampilkannya dan hanya bertanya: tanpa teks, jam, atau gambar. Periksa MID: jika kecepatan, lampu sein, dan jam normal, ketuk di bawah.",
        "Send text and clock again?" to "Kirim teks dan jam lagi?",
        "Only if the MID's speed and turn signals work normally. If it ever freezes again, stop safely and switch the bike off and on." to
            "Hanya jika kecepatan dan lampu sein MID normal. Jika macet lagi, berhenti dengan aman lalu matikan dan nyalakan motor.",
        "Yes, it works" to "Ya, normal",
        "This Bluetooth LE link is too slow for pictures - not sent" to "Tautan Bluetooth LE ini terlalu lambat untuk gambar - tidak dikirim",
        "The MID answers again - questions only until you confirm its display works" to "MID menjawab lagi - hanya pertanyaan sampai Anda memastikan layarnya normal",
        "The MID doesn't answer on an open link - treated as a hang" to "MID tidak menjawab pada tautan terbuka - dianggap macet",
        "No data yet" to "Belum ada data", "Picture error" to "Kesalahan gambar", "Unknown error bit" to "Bit error tidak dikenal",
        "The MID's values look frozen (speed shown but the odometer doesn't move). When it is safe, stop and switch the bike off and on." to
            "Nilai MID tampak macet (kecepatan tampil tapi odometer tidak bergerak). Jika aman, berhenti lalu matikan dan nyalakan motor.",
        "Could not stop the connection - nothing was deleted. Try again." to "Koneksi tidak bisa dihentikan - tidak ada yang dihapus. Coba lagi.",
        "Wait for the last question to be sent" to "Tunggu pertanyaan sebelumnya terkirim",
        "Link open - waiting for the MID to answer" to "Tautan terbuka - menunggu jawaban MID",
        "On connect and when the minute changes" to "Saat terhubung dan saat menit berganti",
        "No picture chosen" to "Belum ada gambar dipilih",
        "The MID stopped answering. When it is safe, stop and switch the bike off and on. Until it answers again the app only asks one question every 15 s." to
            "MID berhenti merespons. Jika aman, berhenti lalu matikan dan nyalakan motor. Sampai MID menjawab lagi, aplikasi hanya mengirim satu pertanyaan setiap 15 detik.",
        "No answer from the bike (switched off?) - waiting" to "Tidak ada jawaban dari motor (dimatikan?) - menunggu",
        "The MID answers again - questions only until the bike is switched off and on" to
            "MID menjawab lagi - hanya pertanyaan sampai motor dimatikan lalu dinyalakan",
        "Ask the MID a question, e.g. AT+SPD?. Only questions can be sent from here: writes (odometer, range...) are refused, because an unknown write froze a G1's MID." to
            "Ajukan pertanyaan ke MID, mis. AT+SPD?. Dari sini hanya pertanyaan yang bisa dikirim: penulisan (odometer, jarak...) ditolak, karena penulisan tak dikenal membuat MID G1 macet.",
        "Only questions like AT+SPD? can be sent" to "Hanya pertanyaan seperti AT+SPD? yang bisa dikirim",
        "Copy log" to "Salin log", "Log copied" to "Log disalin",
        "Alarms (phone notifications)" to "Alarm (notifikasi HP)",
        "Notify me about low battery, hot battery (> 45°C) or motor (> 85°C), battery health below 75%, and MID errors" to
            "Beri tahu saya saat baterai lemah, baterai panas (> 45°C) atau motor panas (> 85°C), kesehatan baterai di bawah 75%, dan error MID",
        "Low battery alarm below (%)" to "Alarm baterai lemah di bawah (%)",
        "Alarms work while the app is connected (also with the screen off)." to "Alarm berfungsi selama aplikasi terhubung (juga saat layar mati).",
        "Unknown code - note it and ask a workshop." to "Kode tidak dikenal - catat dan tanyakan ke bengkel.",
        "Inverter over temperature" to "Inverter terlalu panas", "Motor over temperature" to "Motor terlalu panas",
        "Motor fault" to "Gangguan motor", "Hall sensor fault" to "Gangguan sensor hall", "Throttle fault" to "Gangguan gas",
        "Controller fault" to "Gangguan kontroler", "Over voltage" to "Tegangan berlebih", "Under voltage" to "Tegangan kurang",
        "Throttle signal abnormal (unofficial)" to "Sinyal gas tidak normal (tidak resmi)",
        "Petrol" to "Bensin", "minus electricity" to "dikurangi listrik", "Longest ride" to "Perjalanan terjauh",
        "Most efficient" to "Paling hemat", "Top speed" to "Kecepatan tertinggi", "Export failed" to "Ekspor gagal",
        "No rides yet. Rides are saved automatically while the app is connected: a ride starts when you move and ends after 5 minutes stopped." to
            "Belum ada perjalanan. Perjalanan disimpan otomatis selama aplikasi terhubung: dimulai saat Anda bergerak dan berakhir setelah 5 menit berhenti.",
        "Let the bike rest until the inverter cools down; avoid long heavy load." to "Istirahatkan motor sampai inverter dingin; hindari beban berat lama.",
        "Rest the motor until its temperature is back to normal." to "Istirahatkan motor sampai suhunya normal lagi.",
        "Check motor cables and connectors; visit a Gesits workshop if it repeats." to "Periksa kabel dan konektor motor; ke bengkel Gesits jika berulang.",
        "Check the motor's hall-sensor connector (loose or dirty)." to "Periksa konektor sensor hall motor (longgar atau kotor).",
        "Check throttle position and cable; make sure nothing is stuck." to "Periksa posisi dan kabel gas; pastikan tidak ada yang macet.",
        "Restart the system (off/on). If it stays, visit a Gesits workshop." to "Nyalakan ulang sistem (mati/hidup). Jika tetap, ke bengkel Gesits.",
        "Check the charger and mains voltage." to "Periksa charger dan tegangan listrik rumah.",
        "Charge the battery: its voltage is too low." to "Isi baterai: tegangannya terlalu rendah.",
        "Seen together with real throttle problems (ERR=0800). Check throttle cable/connector; report the code to a workshop." to
            "Muncul bersama masalah gas sungguhan (ERR=0800). Periksa kabel/konektor gas; laporkan kodenya ke bengkel.",
        "OK - no errors" to "OK - tidak ada error", "Waiting for data..." to "Menunggu data...", "Speed" to "Kecepatan",
        "Battery" to "Baterai", "health" to "kesehatan", "Temps" to "Suhu",
        "Connection lost - retrying" to "Koneksi terputus - mencoba lagi",
        "The MID stopped answering - app silent (stop safely, switch the bike off and on)" to
            "MID berhenti merespons - aplikasi diam (berhenti dengan aman, matikan lalu nyalakan motor)",
        "The bike stopped answering (switched off?) - retrying slowly" to "Motor berhenti merespons (dimatikan?) - mencoba lagi perlahan",
        "The MID doesn't answer - retrying slowly" to "MID tidak menjawab - mencoba lagi perlahan",
        "The MID answers again - connected" to "MID menjawab lagi - terhubung",
        "Bike not paired with this phone - pair it again in Bluetooth settings, then tap Connect" to
            "Motor belum dipasangkan dengan HP ini - pasangkan lagi di pengaturan Bluetooth, lalu ketuk Hubungkan",
        "Bluetooth is off" to "Bluetooth mati", "Choose the bike first" to "Pilih motor dulu", "Not connected" to "Tidak terhubung",
        "Demo data (no bike connected)" to "Data demo (tidak ada motor terhubung)",
        "MID not answering" to "MID tidak merespons",
        "MID not answering - stop safely, switch the bike off and on" to "MID tidak merespons - berhenti dengan aman, matikan lalu nyalakan motor",
        "The MID stopped answering. When it is safe, stop and switch the bike off and on. The app sends nothing until then." to
            "MID berhenti merespons. Jika aman, berhenti lalu matikan dan nyalakan motor. Aplikasi tidak mengirim apa pun sampai saat itu.",
        "NO DATA - don't rely on this speed" to "TIDAK ADA DATA - jangan andalkan kecepatan ini",
        "Picture too big for the MID - not sent" to "Gambar terlalu besar untuk MID - tidak dikirim",
        "Demo is not available while connected to a bike" to "Demo tidak tersedia saat terhubung ke motor",
        "Grid goes to the MID for 2 minutes" to "Grid tampil di MID selama 2 menit",
        "Large grid goes to the MID for 2 minutes" to "Grid besar tampil di MID selama 2 menit",
        "Save position" to "Simpan posisi", "inverter" to "inverter",
        "RANGE writes are blocked: they made a G1's MID stop answering" to "Penulisan RANGE diblokir: membuat MID G1 berhenti merespons",
        "Send your own AT command, e.g. AT+SPD?. Queries ending in ? only read. Odometer and RANGE writes are blocked (RANGE froze a G1's MID)." to
            "Kirim perintah AT sendiri, mis. AT+SPD?. Pertanyaan berakhiran ? hanya membaca. Penulisan odometer dan RANGE diblokir (RANGE membuat MID G1 macet).",
        "Pictures are heavy for the MID: on a G1 they made it freeze (speed, turn signals) until the bike was switched off and on. Off is safest." to
            "Gambar berat untuk MID: di G1 gambar membuatnya macet (kecepatan, lampu sein) sampai motor dimatikan lalu dinyalakan lagi. Mati paling aman.",
        "The MID stopped answering. When it is safe, stop and switch the bike off and on. The app stays quiet for 2 minutes." to
            "MID berhenti merespons. Jika aman, berhenti lalu matikan dan nyalakan motor. Aplikasi diam selama 2 menit.", "measured" to "terukur", "learning" to "belajar",
        "Adaptive range in the MID text line" to "Jarak tempuh adaptif di baris teks MID",
        "Learns your battery size and how much you use, by itself. Shown when you have no text of your own." to
            "Mempelajari ukuran baterai dan pemakaian Anda secara otomatis. Tampil saat Anda tidak mengisi teks sendiri.",
        "Also try to replace the MID's range figure" to "Coba juga ganti angka jarak tempuh MID",
        "On the G1 the MID puts its own number back instantly, so this only flickers." to "Di G1, MID langsung mengembalikan angkanya sendiri, jadi hanya berkedip.",
        "The MID's own range figure: %.0f km (it assumes the original battery and can't be changed over Bluetooth)." to
            "Angka jarak tempuh MID sendiri: %.0f km (mengasumsikan baterai asli dan tidak bisa diubah lewat Bluetooth).",
        "The MID's own range figure assumes the original battery and can't be changed over Bluetooth." to
            "Angka jarak tempuh MID sendiri mengasumsikan baterai asli dan tidak bisa diubah lewat Bluetooth.",
        "Show today's date when empty" to "Tampilkan tanggal hari ini jika kosong",
        "Adaptive range on the MID" to "Jarak tempuh adaptif di MID",
        "The MID shows its own range." to "MID menampilkan jarak tempuhnya sendiri.",
        "The MID shows this range. The MID also keeps recalculating its own, so it may briefly show that one." to
            "MID menampilkan jarak tempuh ini. MID juga terus menghitung miliknya sendiri, jadi sesekali angka itu muncul sebentar.",
        "Still learning (%.0f of 20%% battery ridden): until then the MID keeps its own range." to
            "Masih belajar (%.0f dari 20%% baterai terpakai): sampai saat itu MID memakai jarak tempuhnya sendiri.",
        "Learned from your riding: km per 1% battery" to "Dipelajari dari cara berkendara Anda: km per 1% baterai",
        "Learns your battery size and how much you use, by itself" to "Mempelajari ukuran baterai dan pemakaian Anda secara otomatis",
        "The MID shows this range. It also keeps recalculating its own (for the original battery), so it may briefly show that one." to
            "MID menampilkan jarak tempuh ini. MID juga terus menghitung miliknya sendiri (untuk baterai asli), jadi angka itu sesekali muncul sebentar.",
        "sent" to "terkirim", "no reply" to "tak dibalas", "picture" to "gambar", "clock" to "jam", "text" to "teks", "range" to "jarak",
        "This MID doesn't confirm writes; check its screen." to "MID ini tidak membalas kiriman; lihat layarnya.",
        "Full-battery range" to "Jarak baterai penuh", "km on a full battery" to "km dengan baterai penuh",
        "How far you get on a full battery. Original G1 battery: about 50 km. With a bigger battery, enter its range; the app sends it to the MID right away and keeps refining it from your rides." to
            "Seberapa jauh dengan baterai penuh. Baterai asli G1: sekitar 50 km. Dengan baterai lebih besar, isi jaraknya; aplikasi langsung mengirimnya ke MID dan terus menyesuaikannya dari perjalanan Anda.",
        "Enter a number between 5 and 500" to "Isi angka antara 5 dan 500",
                "Connect to the bike first" to "Hubungkan ke motor dulu", "Only while stopped" to "Hanya saat berhenti",
                        "Starting value" to "Nilai awal", "Forget learned" to "Lupakan hasil belajar",
        "Forget learned range?" to "Lupakan jarak tempuh yang dipelajari?",
        "Learning starts again. Useful after a battery change." to "Belajar dimulai lagi. Berguna setelah ganti baterai.",
        "Forget" to "Lupakan", "Cancel" to "Batal", "Save" to "Simpan", "Saved" to "Tersimpan", "Delete" to "Hapus",
        "CLOCK" to "JAM", "Set the MID clock from this phone" to "Atur jam MID dari HP ini",
        "On connect and every minute" to "Saat terhubung dan setiap menit",
        "Advanced: position & calibration" to "Lanjutan: posisi & kalibrasi",
        "Grid" to "Grid", "Large grid" to "Grid besar", "Left" to "Kiri", "Top" to "Atas", "Right" to "Kanan", "Bottom" to "Bawah",
        "Save position & show live info" to "Simpan posisi & tampilkan info langsung",
        "Grid goes to the MID in a few seconds" to "Grid dikirim ke MID dalam beberapa detik",
        "Large grid goes to the MID in a few seconds" to "Grid besar dikirim ke MID dalam beberapa detik",
        "Send a grid, photograph the MID, and adjust the margins (pixels of the 320×128 picture) if the MID covers an edge." to
            "Kirim grid, foto MID, lalu atur margin (piksel gambar 320×128) bila MID menutupi tepinya.",
        "Starting value until enough is learned" to "Nilai awal sampai cukup dipelajari",
        "km per 1% battery until enough is learned" to "km per 1% baterai sampai cukup dipelajari",
        "Connect to the bike to send pictures" to "Hubungkan ke motor untuk mengirim gambar",
        "Waiting for the first picture…" to "Menunggu gambar pertama…",
        "Dashboard picture too large" to "Gambar dasbor terlalu besar",
        "km per week" to "km per minggu", "km per day" to "km per hari", "throttle" to "gas",
        "battery" to "baterai", "inverter" to "inverter",
        "Picture ready - it will be shown on the MID" to "Gambar siap - akan ditampilkan di MID",
        "Picture too detailed for the MID; try a simpler one" to "Gambar terlalu detail untuk MID; coba yang lebih sederhana",
        "That file is not a picture" to "File itu bukan gambar", "Could not read that picture" to "Gambar tidak bisa dibaca",
        "MID ACCEPTED" to "DITERIMA MID", "SENT TO THE MID" to "DIKIRIM KE MID",
        "TURN-BY-TURN DIRECTIONS" to "PETUNJUK ARAH",
        "Show Google Maps directions on the MID" to "Tampilkan petunjuk Google Maps di MID",
        "Reads only Google Maps navigation notifications. Nothing else, nothing leaves the phone." to
            "Hanya membaca notifikasi navigasi Google Maps. Tidak ada yang lain, tidak ada yang keluar dari HP.",
        "Allow notification access" to "Izinkan akses notifikasi",
        "Off: tap the button, then turn on \"Gesits MID directions\"." to "Belum aktif: ketuk tombol, lalu aktifkan \"Gesits MID directions\".",
        "Ready: start navigation in Google Maps" to "Siap: mulai navigasi di Google Maps",
        "MID version" to "Versi MID",
        "Not connected - retrying" to "Tidak terhubung - mencoba lagi", "Stopped" to "Berhenti",
        "SPEED WARNING" to "PERINGATAN KECEPATAN", "Off" to "Mati",
        "Warn on the MID above this speed" to "Peringatan di MID di atas kecepatan ini",
        // trips
        "THIS WEEK" to "MINGGU INI", "ALL RIDES" to "SEMUA PERJALANAN", "DISTANCE" to "JARAK", "RIDES" to "PERJALANAN",
        "ENERGY" to "ENERGI", "EFFICIENCY" to "EFISIENSI", "REGEN" to "REGEN", "KM PER 1%" to "KM PER 1%",
        "SAVED VS A PETROL MOTORCYCLE" to "HEMAT DIBANDING MOTOR BENSIN", "PETROL NOT BURNED" to "BENSIN TIDAK TERBAKAR",
        "CO₂ AVOIDED" to "CO₂ DIHINDARI", "LAST 7 DAYS" to "7 HARI TERAKHIR", "RECORDS" to "REKOR",
        "RIDE HISTORY" to "RIWAYAT PERJALANAN", "Export CSV" to "Ekspor CSV", "Delete all" to "Hapus semua",
        "Delete all rides?" to "Hapus semua perjalanan?",
        "This cannot be undone. Export first to keep a copy." to "Tidak bisa dibatalkan. Ekspor dulu untuk menyimpan salinan.",
        "Target & prices" to "Target & harga", "Weekly target (km)" to "Target mingguan (km)",
        "Petrol motorcycle: km per litre" to "Motor bensin: km per liter", "Petrol price (Rp per litre)" to "Harga bensin (Rp per liter)",
        "Electricity price (Rp per kWh)" to "Harga listrik (Rp per kWh)",
        "BATTERY HEALTH" to "KESEHATAN BATERAI", "HEALTH (SOH)" to "KESEHATAN (SOH)", "USABLE CAPACITY" to "KAPASITAS TERPAKAI",
        "CYCLES (COUNTED)" to "SIKLUS (DIHITUNG)", "CHARGES" to "PENGISIAN",
        // tools
        "MAPS (OPENS GOOGLE MAPS)" to "PETA (MEMBUKA GOOGLE MAPS)", "Destination, e.g. Monas Jakarta" to "Tujuan, mis. Monas Jakarta",
        "Navigate" to "Navigasi", "SPKLU near me" to "SPKLU terdekat", "Type a destination first" to "Ketik tujuan dulu",
        "PARKING" to "PARKIR", "Remember where I parked" to "Ingat lokasi parkir",
        "Uses phone GPS when you park or switch off. Stays on this phone." to "Memakai GPS HP saat parkir atau mematikan motor. Tetap di HP ini.",
        "Show parked bike in Maps" to "Tampilkan motor di Maps", "No parked location saved" to "Belum ada lokasi parkir",
        "No parked location saved yet" to "Belum ada lokasi parkir tersimpan",
        "ALARMS" to "ALARM", "Phone notifications" to "Notifikasi HP",
        "Low battery, hot battery (>45°C) or motor (>85°C), battery health <75%, MID errors" to
            "Baterai lemah, baterai panas (>45°C) atau motor (>85°C), kesehatan baterai <75%, error MID",
        "Low battery below" to "Baterai lemah di bawah",
        "KEEP CONNECTED" to "TETAP TERHUBUNG", "Connect automatically" to "Hubungkan otomatis",
        "As soon as the app is opened (once a bike is chosen)" to "Begitu aplikasi dibuka (setelah motor dipilih)",
        "Allow background" to "Izinkan latar belakang", "App info" to "Info aplikasi",
        "Stop automatically when the bike has been off for" to "Berhenti otomatis bila motor mati selama",
        "Never" to "Tidak pernah",
        "Some phones (Xiaomi, OPPO, vivo, Realme, Samsung) close Bluetooth apps in the background. Allow this app to run without battery restrictions; on Xiaomi/OPPO/vivo also turn on Autostart in App info." to
            "Beberapa HP (Xiaomi, OPPO, vivo, Realme, Samsung) menutup aplikasi Bluetooth di latar belakang. Izinkan aplikasi ini berjalan tanpa batasan baterai; di Xiaomi/OPPO/vivo nyalakan juga Mulai otomatis di Info aplikasi.",
        "LANGUAGE" to "BAHASA", "Phone" to "Ikuti HP",
        "PRIVACY & PERMISSIONS" to "PRIVASI & IZIN",
        "• No internet access: nothing ever leaves this phone.\n• Bluetooth: to talk to the bike.\n• Notifications: connection status and alarms.\n• Location: only if you turn on parking (and for BLE scans on Android 8–11).\n• Notification access: only if you turn on directions; only Google Maps is read.\n• Your rides, learned range and settings stay in the app's private storage and are not backed up." to
            "• Tanpa akses internet: tidak ada yang keluar dari HP ini.\n• Bluetooth: untuk berkomunikasi dengan motor.\n• Notifikasi: status koneksi dan alarm.\n• Lokasi: hanya bila parkir dinyalakan (dan untuk pencarian BLE di Android 8–11).\n• Akses notifikasi: hanya bila petunjuk arah dinyalakan; hanya Google Maps yang dibaca.\n• Perjalanan, jarak tempuh yang dipelajari, dan pengaturan tersimpan di penyimpanan pribadi aplikasi dan tidak dicadangkan.",
        "Delete all my data" to "Hapus semua data saya", "Delete all data?" to "Hapus semua data?",
        "Rides, learned range, trip, parking location, chosen bike and settings will be erased." to
            "Perjalanan, jarak tempuh yang dipelajari, perjalanan saat ini, lokasi parkir, motor yang dipilih, dan pengaturan akan dihapus.",
        "Advanced: console & log" to "Lanjutan: konsol & log",
        "Send your own AT command, e.g. AT+SPD?. Queries ending in ? only read. Odometer writes are blocked." to
            "Kirim perintah AT sendiri, mis. AT+SPD?. Perintah berakhiran ? hanya membaca. Mengubah odometer diblokir.",
        "Send" to "Kirim", "Probe" to "Selidiki", "Copy log" to "Salin log", "Log copied" to "Log disalin",
        "Connect to the bike first" to "Hubungkan ke motor dulu", "Odometer writes are blocked in this app" to "Mengubah odometer diblokir di aplikasi ini",
        "ABOUT" to "TENTANG",
        "Made by CAKRA Labs · Universitas Pertamina\nAn open, community tool - not an official Gesits app." to
            "Dibuat oleh CAKRA Labs · Universitas Pertamina\nAlat komunitas terbuka - bukan aplikasi resmi Gesits.",
        "HOME-SCREEN WIDGET" to "WIDGET LAYAR UTAMA",
        "Long-press your home screen → Widgets → Gesits MID to see battery and range without opening the app." to
            "Tekan lama layar utama → Widget → Gesits MID untuk melihat baterai dan jarak tempuh tanpa membuka aplikasi.",
        // dialogs / toasts
        "Allow Bluetooth access, then tap again" to "Izinkan akses Bluetooth, lalu ketuk lagi",
        "Turn Bluetooth on first" to "Nyalakan Bluetooth dulu", "Turn Bluetooth on to connect to the bike" to "Nyalakan Bluetooth untuk terhubung ke motor",
        "No paired devices" to "Tidak ada perangkat yang dipasangkan",
        "Pair the bike first: ignition ON, press the Bluetooth button on the left handlebar, then pair it in Android Bluetooth settings." to
            "Pasangkan motor dulu: nyalakan kontak, tekan tombol Bluetooth di setang kiri, lalu pasangkan di pengaturan Bluetooth Android.",
        "Open Bluetooth settings" to "Buka pengaturan Bluetooth", "Choose your bike" to "Pilih motor Anda",
        "Searching…" to "Mencari…", "Looking for GESITS for 8 seconds" to "Mencari GESITS selama 8 detik",
        "Nothing found. Ignition on? Press the MID Bluetooth button." to "Tidak ditemukan. Kontak sudah menyala? Tekan tombol Bluetooth MID.",
        "Choose GESITS" to "Pilih GESITS", "How to connect?" to "Cara menghubungkan?", "BLE not available" to "BLE tidak tersedia",
        "No maps app found" to "Aplikasi peta tidak ditemukan", "No browser found" to "Browser tidak ditemukan",
        // diagnosis / speedometer
        "Errors" to "Error", "Now" to "Sekarang", "OK - no errors" to "OK - tidak ada error", "Waiting for data..." to "Menunggu data...",
        "Speed (last 10 min)" to "Kecepatan (10 menit terakhir)", "Power (kW, below 0 = regen)" to "Daya (kW, di bawah 0 = regen)",
        "Throttle" to "Gas", "Temperatures (°C)" to "Suhu (°C)",
        "tap to close" to "ketuk untuk menutup", "BATTERY " to "BATERAI ",
        // MID picture
        "POWER " to "DAYA ", "km left" to "km lagi", "ENERGY USED" to "ENERGI TERPAKAI", "kW POWER" to "kW DAYA",
        "kW REGEN" to "kW REGEN", "km RANGE" to "km JARAK", "ERROR" to "ERROR",
        // notifications
        "Bike alarms" to "Alarm motor", "Bike connection" to "Koneksi motor", "Connected to the bike's dashboard" to "Terhubung ke dasbor motor",
        "Battery low" to "Baterai lemah", "Battery hot" to "Baterai panas", "Motor hot" to "Motor panas",
        "Battery health low" to "Kesehatan baterai rendah", "MID error" to "Error MID",
        "Charging started" to "Mulai mengisi daya", "Battery reached" to "Baterai mencapai", "Charging stopped" to "Pengisian berhenti",
        "TRIP" to "PERJALANAN", "connected" to "terhubung", "not connected" to "tidak terhubung", "English" to "English", "Indonesia" to "Indonesia",
        // turns (MID text line)
        "LEFT" to "KIRI", "RIGHT" to "KANAN", "KEEP LEFT" to "AGAK KIRI", "KEEP RIGHT" to "AGAK KANAN", "U-TURN" to "PUTAR BALIK",
        "ROUNDABOUT" to "BUNDARAN", "STRAIGHT" to "LURUS", "ARRIVED" to "TIBA",
    )
}
