// ESP32 custom MID screen for the Gesits G1 - over Bluetooth LE (no phone needed).
//
// Connects to the MID's BLE side (name "GESITS", characteristic FFF2), reads live data
// with AT+<KEY>? and pushes a self-drawn 350x220 dashboard picture with AT+MAPS every
// few seconds. Same layout as gesits/screen.py (python -m gesits screen --preview x.png).
//
// Boards: any ESP32 with BLE (ESP32, ESP32-S3, ESP32-C3), Arduino-ESP32 core 2.x or 3.x.
// Libraries (Arduino Library Manager): "Adafruit GFX Library", "JPEGENC" (Larry Bank).
// Protocol details: docs/PROTOCOL.md. Settings: config.h.

#include <Arduino.h>
#include <BLEDevice.h>
#include <Adafruit_GFX.h>
#include <Fonts/FreeSansBold9pt7b.h>
#include <Fonts/FreeSansBold12pt7b.h>
#include <Fonts/FreeSansBold24pt7b.h>
#include <JPEGENC.h>
#include <WiFi.h>
#include <time.h>
#include <cmath>
#include "mbedtls/base64.h"
#include "config.h"

#define RGB565(r, g, b) (uint16_t)((((r) & 0xF8) << 8) | (((g) & 0xFC) << 3) | ((b) >> 3))
static const uint16_t C_BG = RGB565(12, 14, 18), C_PANEL = RGB565(30, 34, 42), C_TEXT = RGB565(235, 238, 242),
                      C_MUTED = RGB565(140, 148, 160), C_GREEN = RGB565(60, 200, 110), C_AMBER = RGB565(255, 176, 32),
                      C_RED = RGB565(235, 60, 60), C_BLUE = RGB565(60, 140, 255);

// ---------------------------------------------------------------- live data --------
// Written by the BLE notify callback, read by loop(). 32-bit stores are atomic on ESP32.
struct Data {
  volatile float soc = NAN, vbat = NAN, ibat = NAN, odo = NAN, tbat = NAN, tmot = NAN, tinv = NAN, spd = NAN;
  volatile int mode = -1, park = -1;
  volatile uint32_t err = 0;
  volatile bool errKnown = false;
} D;

// Copy taken once per frame, so every 16-row strip of one picture shows the same values.
struct View { float soc, vbat, ibat, odo, tbat, tmot, tinv; int mode, park; uint32_t err; bool errKnown; } V;
static void takeSnapshot() {
  V = {D.soc, D.vbat, D.ibat, D.odo, D.tbat, D.tmot, D.tinv, D.mode, D.park, D.err, D.errKnown};
}

static const char *POLL_KEYS[] = {"SPD", "IBAT", "VBAT", "SOC", "ODO", "MODE", "PARK", "ERR", "TBAT", "TMOT", "TINV"};
static const int N_KEYS = sizeof(POLL_KEYS) / sizeof(POLL_KEYS[0]);

static void handleLine(char *line) {
  if (strncmp(line, "AT+", 3) == 0) line += 3;       // replies sometimes echo the AT+ prefix
  char *eq = strchr(line, '=');
  if (!eq) return;
  *eq = 0;
  char *val = eq + 1;
  while (*val == '"' || *val == ' ') val++;
  float f = atof(val);
  if (!strcmp(line, "SOC")) D.soc = f;
  else if (!strcmp(line, "VBAT")) D.vbat = f;
  else if (!strcmp(line, "IBAT")) D.ibat = f;
  else if (!strcmp(line, "ODO")) D.odo = f;
  else if (!strcmp(line, "SPD")) D.spd = f;
  else if (!strcmp(line, "TBAT")) D.tbat = f;
  else if (!strcmp(line, "TMOT")) D.tmot = f;
  else if (!strcmp(line, "TINV")) D.tinv = f;
  else if (!strcmp(line, "MODE")) D.mode = (int)f;               // 0 ECO, 1 URBAN, 2 SPORT
  else if (!strcmp(line, "PARK")) D.park = (int)f;               // 1 PARK, 0 DRIVE
  else if (!strcmp(line, "ERR")) { D.err = strtoul(val, nullptr, 16); D.errKnown = true; }  // "0800" -> bit 11
}

static char rxLine[96];
static size_t rxLen = 0;
static void onNotify(BLERemoteCharacteristic *, uint8_t *data, size_t len, bool) {
  for (size_t i = 0; i < len; i++) {
    char c = (char)data[i];
    if (c == '\r') continue;
    if (c == '\n') { rxLine[rxLen] = 0; if (rxLen) handleLine(rxLine); rxLen = 0; }
    else if (rxLen < sizeof(rxLine) - 1) rxLine[rxLen++] = c;
  }
}

// ---------------------------------------------------------------- BLE link ---------
static BLEAdvertisedDevice *midDevice = nullptr;
static BLEClient *client = nullptr;
static BLERemoteCharacteristic *fff2 = nullptr;
static size_t piece = BLE_PIECE;

class ScanCB : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice dev) override {
    if (dev.haveName() && dev.getName() == MID_BLE_NAME) {
      midDevice = new BLEAdvertisedDevice(dev);
      BLEDevice::getScan()->stop();
    }
  }
};

static ScanCB scanCB;

static bool connectMid() {
  if (midDevice) { delete midDevice; midDevice = nullptr; }
  Serial.println("Scanning for " MID_BLE_NAME " ...");
  BLEScan *scan = BLEDevice::getScan();
  scan->setAdvertisedDeviceCallbacks(&scanCB);
  scan->setActiveScan(true);
  scan->start(8, false);
  scan->clearResults();
  if (!midDevice) { Serial.println("MID not found (ignition on? BT on?)"); return false; }

  if (!client) client = BLEDevice::createClient();
  if (!client->connect(midDevice)) { Serial.println("connect failed"); return false; }

  fff2 = nullptr;
  BLEUUID want((uint16_t)MID_CHAR_UUID16);
  for (auto &svc : *client->getServices()) {
    for (auto &ch : *svc.second->getCharacteristics()) {
      if (ch.second->getUUID().equals(want)) fff2 = ch.second;
    }
  }
  if (!fff2) { Serial.println("FFF2 not found"); client->disconnect(); return false; }
  if (fff2->canNotify()) fff2->registerForNotify(onNotify);
  int mtu = client->getMTU();
  piece = (mtu > 23) ? min((int)BLE_PIECE, mtu - 3) : 20;
  Serial.printf("Connected. MTU %d, piece %u\n", mtu, (unsigned)piece);
  return true;
}

// FFF2 has no write-without-response: always write WITH response.
static bool bleWrite(const char *s, size_t n) {
  if (!client || !client->isConnected() || !fff2) return false;
  for (size_t i = 0; i < n; i += piece) {
    size_t k = min(piece, n - i);
    fff2->writeValue((uint8_t *)(s + i), k, true);
    if (i + k < n) delay(10);
  }
  return true;
}
static bool sendCmd(const char *cmd) { return bleWrite(cmd, strlen(cmd)); }

// AT+MAPS=" -> 50 ms -> base64 in pieces with gaps -> "\r\n (MID joins writes until CR LF)
static bool sendMaps(const char *b64, size_t n) {
  if (!sendCmd("AT+MAPS=\"")) return false;
  delay(50);
  for (size_t i = 0; i < n; i += piece) {
    if (!bleWrite(b64 + i, min(piece, n - i))) return false;
    delay(BLE_PIECE_GAP_MS);
  }
  return sendCmd("\"\r\n");
}

// ---------------------------------------------------------------- drawing ----------
// The picture is drawn and JPEG-encoded 16 rows at a time, so no full frame buffer is
// needed: draw the whole scene into a 352x16 canvas shifted up by `yo` (GFX clips the rest).
static const int STRIP_W = (FRAME_W + 15) & ~15, STRIP_H = 16;
static GFXcanvas16 strip(STRIP_W, STRIP_H);
static int yo = 0;

static void rrect(int x0, int y0, int x1, int y1, uint16_t c) { strip.fillRoundRect(x0, y0 - yo, x1 - x0, y1 - y0, 5, c); }
static void txt(int x, int baseline, const char *s, const GFXfont *f, uint16_t c) {
  strip.setFont(f); strip.setTextColor(c); strip.setCursor(x, baseline - yo); strip.print(s);
}
static int txtW(const char *s, const GFXfont *f) {
  int16_t x1, y1; uint16_t w, h; strip.setFont(f); strip.getTextBounds(s, 0, 50, &x1, &y1, &w, &h); return w;
}
static void degC(int x, int baseline, uint16_t c, const GFXfont *f) {   // fonts have no degree sign
  strip.drawCircle(x + 3, baseline - 14 - yo, 3, c);
  txt(x + 8, baseline, "C", f, c);
}
static uint16_t tempColour(float v, int warn, int crit) {
  if (std::isnan(v)) return C_MUTED;
  return v >= crit ? C_RED : v >= warn ? C_AMBER : C_TEXT;
}

static void drawScene() {
  char b[40];
  const GFXfont *S = &FreeSansBold9pt7b, *M = &FreeSansBold12pt7b, *L = &FreeSansBold24pt7b;
  strip.fillScreen(C_BG);

  // top bar: clock (if Wi-Fi time), gear, drive mode
  struct tm t;
  if (strlen(WIFI_SSID) && getLocalTime(&t, 0)) { strftime(b, sizeof b, "%H:%M", &t); txt(10, 26, b, M, C_TEXT); }
  else txt(10, 26, "GESITS", M, C_TEXT);
  const char *mode = V.mode == 0 ? "ECO" : V.mode == 1 ? "URBAN" : V.mode == 2 ? "SPORT" : "--";
  uint16_t mc = V.mode == 0 ? C_GREEN : V.mode == 1 ? C_BLUE : V.mode == 2 ? C_RED : C_MUTED;
  int mw = txtW(mode, S) + 16;
  rrect(FRAME_W - 10 - mw, 8, FRAME_W - 10, 30, mc);
  txt(FRAME_W - 10 - mw + 8, 25, mode, S, C_BG);
  const char *gear = V.park == 1 ? "P" : V.park == 0 ? "D" : "-";
  uint16_t gc = V.park == 1 ? C_AMBER : V.park == 0 ? C_GREEN : C_MUTED;
  strip.drawRoundRect(FRAME_W - 20 - mw - 26, 8 - yo, 26, 22, 5, gc);
  txt(FRAME_W - 20 - mw - 19, 25, gear, S, gc);

  // battery (left)
  float soc = V.soc;
  std::isnan(soc) ? snprintf(b, sizeof b, "--%%") : snprintf(b, sizeof b, "%.0f%%", soc);
  txt(12, 84, b, L, C_TEXT);
  rrect(12, 96, 162, 112, C_PANEL);
  if (!std::isnan(soc) && soc > 0) rrect(12, 96, 12 + (int)(150 * constrain(soc, 0, 100) / 100), 112, soc <= 20 ? C_RED : soc <= 35 ? C_AMBER : C_GREEN);
  std::isnan(soc) ? snprintf(b, sizeof b, "~-- km range") : snprintf(b, sizeof b, "~%.0f km range", soc * KM_PER_PERCENT);
  txt(12, 134, b, S, C_MUTED);

  // power (right)
  strip.fillRect(175, 44 - yo, 2, 92, C_PANEL);
  float p = (std::isnan(V.vbat) || std::isnan(V.ibat)) ? NAN : V.vbat * V.ibat;
  std::isnan(p) ? snprintf(b, sizeof b, "-- kW") : snprintf(b, sizeof b, "%.1f kW", p / 1000);
  txt(188, 64, b, M, C_TEXT);
  txt(188, 86, (!std::isnan(p) && p < 0) ? "REGEN" : "POWER", S, C_MUTED);
  rrect(188, 96, 338, 112, C_PANEL);
  if (!std::isnan(p) && p != 0) rrect(188, 96, 188 + (int)(150 * constrain(fabsf(p) / MAX_POWER_W, 0, 1)), 112, p < 0 ? C_GREEN : C_BLUE);
  std::isnan(V.odo) ? snprintf(b, sizeof b, "ODO -- km") : snprintf(b, sizeof b, "ODO %.1f km", V.odo);
  txt(188, 134, b, S, C_MUTED);

  // bottom: temperatures, or error banner
  if (V.errKnown && V.err) {
    rrect(8, 150, FRAME_W - 8, FRAME_H - 8, C_RED);
    txt(18, 176, "ERROR", M, C_TEXT);
    snprintf(b, sizeof b, "ERR=%04lX - see app", (unsigned long)V.err);
    txt(18, 202, b, S, C_TEXT);
    return;
  }
  const char *lbl[3] = {"BAT", "MOT", "INV"};
  float v[3] = {V.tbat, V.tmot, V.tinv};
  int warn[3] = {TBAT_WARN, TMOT_WARN, TMOT_WARN}, crit[3] = {TBAT_CRIT, TMOT_CRIT, TMOT_CRIT};
  int tw = (FRAME_W - 16 - 16) / 3;
  for (int i = 0; i < 3; i++) {
    int x0 = 8 + i * (tw + 8);
    rrect(x0, 150, x0 + tw, FRAME_H - 8, C_PANEL);
    txt(x0 + 10, 170, lbl[i], S, C_MUTED);
    uint16_t c = tempColour(v[i], warn[i], crit[i]);
    std::isnan(v[i]) ? snprintf(b, sizeof b, "--") : snprintf(b, sizeof b, "%.0f", v[i]);
    txt(x0 + 10, 200, b, M, c);
    degC(x0 + 12 + txtW(b, M), 200, c, M);
  }
}

// ---------------------------------------------------------------- JPEG + base64 ----
static JPEGENC jpg;
static uint8_t jpegBuf[(MAPS_MAX_B64 / 4) * 3 + 2048];
static char b64Buf[MAPS_MAX_B64 + 600];

// Returns JPEG size in bytes, or 0 if it did not fit the buffer.
static int encodeFrame(uint8_t q) {
  JPEGENCODE jpe;
  if (jpg.open(jpegBuf, sizeof jpegBuf) != JPEGE_SUCCESS) return 0;
  if (jpg.encodeBegin(&jpe, FRAME_W, FRAME_H, JPEGE_PIXEL_RGB565, JPEGE_SUBSAMPLE_420, q) != JPEGE_SUCCESS) return 0;
  int mcus = ((FRAME_W + jpe.cx - 1) / jpe.cx) * ((FRAME_H + jpe.cy - 1) / jpe.cy);
  int drawnRow = -1, rc = JPEGE_SUCCESS;
  for (int i = 0; i < mcus && rc == JPEGE_SUCCESS; i++) {
    if (jpe.y != drawnRow) { yo = drawnRow = jpe.y; drawScene(); }   // new MCU row: redraw strip
    rc = jpg.addMCU(&jpe, (uint8_t *)(strip.getBuffer() + jpe.x), STRIP_W * 2);
  }
  int size = jpg.close();
  return rc == JPEGE_SUCCESS ? size : 0;
}

static size_t buildFrameBase64() {
  takeSnapshot();
  const uint8_t qs[] = {JPEGE_Q_HIGH, JPEGE_Q_MED, JPEGE_Q_LOW};
  for (uint8_t q : qs) {
    int n = encodeFrame(q);
    if (n <= 0) continue;
    size_t out = 0;
    if (mbedtls_base64_encode((unsigned char *)b64Buf, sizeof b64Buf, &out, jpegBuf, n) != 0) continue;
    if (out <= MAPS_MAX_B64) { Serial.printf("frame: %d B jpeg, %u b64 (q%u)\n", n, (unsigned)out, q); return out; }
  }
  Serial.println("frame too big even at low quality - simplify the layout or lower FRAME_W/FRAME_H");
  return 0;
}

// ---------------------------------------------------------------- main loop --------
void setup() {
  Serial.begin(115200);
  if (strlen(WIFI_SSID)) {
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    configTzTime(TZ_INFO, "pool.ntp.org", "time.google.com");
  }
  BLEDevice::init("GESITS-SCREEN");
  BLEDevice::setMTU(247);
}

void loop() {
  static uint32_t lastPoll = 0, lastFrame = 0, lastNav = 0, lastTrip = 0;
  static int keyIdx = 0, navIdx = 0;

  if (!client || !client->isConnected()) {
    if (!connectMid()) { delay(3000); return; }
    lastFrame = millis() - FRAME_INTERVAL_MS + 1500;   // first frame after some data arrived
  }
  uint32_t now = millis();

  if (now - lastPoll >= POLL_INTERVAL_MS) {
    char cmd[16];
    snprintf(cmd, sizeof cmd, "AT+%s?\r\n", POLL_KEYS[keyIdx]);
    sendCmd(cmd);
    keyIdx = (keyIdx + 1) % N_KEYS;
    lastPoll = now;
  }

  if (now - lastFrame >= FRAME_INTERVAL_MS) {
    size_t n = buildFrameBase64();
    if (n) sendMaps(b64Buf, n);
    lastFrame = millis();
  }

#if SEND_RANGE
  if (now - lastTrip >= 3000 && !std::isnan(D.soc)) {
    char cmd[32];
    snprintf(cmd, sizeof cmd, "AT+TRIP=\"%.1f\"\r\n", D.soc * KM_PER_PERCENT);
    sendCmd(cmd);
    lastTrip = now;
  }
#endif

  const int nNav = sizeof(NAV_TEXTS) / sizeof(NAV_TEXTS[0]);
  if (nNav && now - lastNav >= NAV_ROTATE_MS) {
    char cmd[40];
    snprintf(cmd, sizeof cmd, "AT+NAV=\"%-20.20s\"\r\n", NAV_TEXTS[navIdx]);   // pad to 20: MID redraw bug
    sendCmd(cmd);
    navIdx = (navIdx + 1) % nNav;
    lastNav = now;
  }
  delay(5);
}
