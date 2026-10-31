// ---- Settings for the ESP32 custom MID screen ----------------------------------
#pragma once

// MID's BLE name and characteristic (from GESITS Dashboard, checked with nRF Connect).
#define MID_BLE_NAME        "GESITS"
#define MID_CHAR_UUID16     0xFFF2

// Picture sent with AT+MAPS. MID limits: width <= 350, height <= ~500,
// base64 text <= 7000 chars (bigger frames freeze the MID).
#define FRAME_W             350
#define FRAME_H             220
#define MAPS_MAX_B64        7000

// Timing
#define FRAME_INTERVAL_MS   3000   // new picture every 3 s (GESITS Dashboard: 3 s when not navigating)
#define POLL_INTERVAL_MS    200    // one AT+<KEY>? every 200 ms, round robin
#define BLE_PIECE           100    // AT+MAPS body is written in pieces of this many chars ...
#define BLE_PIECE_GAP_MS    30     // ... with this pause between pieces

// Range shown on the MID via AT+TRIP (simple estimate: km per % of charge).
#define KM_PER_PERCENT      1.0f
#define SEND_RANGE          1

// Optional rotating text line (AT+NAV, max 20 chars each). Leave the list empty to disable.
static const char *NAV_TEXTS[] = { "Halo Gesits!", "Hati-hati di jalan" };
#define NAV_ROTATE_MS       5000

// Optional clock: fill in Wi-Fi (e.g. your phone's hotspot) to get time by NTP.
#define WIFI_SSID           ""
#define WIFI_PASS           ""
#define TZ_INFO             "WIB-7"   // Western Indonesia Time, UTC+7

// Warning levels (same as GESITS Dashboard)
#define TBAT_WARN 45
#define TBAT_CRIT 55
#define TMOT_WARN 70
#define TMOT_CRIT 85
#define MAX_POWER_W 5000.0f
