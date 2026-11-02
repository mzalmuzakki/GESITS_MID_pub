# Gesits G1 MID Bluetooth protocol

Two sources:

| | App | What it gives us |
|---|---|---|
| **A** | Old official Gesits app `com.gesits.app.mobile` 1.5.2 (`Gesits_Mod_1.5.2.apk`) | What the factory app sends. Code only, not tested by us. |
| **C** | Community **GRID Mobile** `xyz.potasyyum.gridmobile` 1.2.0 (React Native / Hermes bytecode) | Widely used with real bikes. Adds `AT+VER?`, `AT+ARROW="1"`, date on `AT+NAV`, and 320×128 MID pictures. |
| **B** | Community **GESITS Dashboard** `com.gesits.dashboard` 1.0.1.17 (web app https://gesits.pages.dev in an Android wrapper) | Used on real bikes. Its code comments record fixes "verified directly on the motor". **Where A and B disagree, follow B.** |

Labels used below: ✅ = confirmed on a bike (according to B's comments) · 🟡 = used by a working app but no
explicit confirmation · ❓ = unknown or guessed.

## 1. Connection

The MID has **two** Bluetooth interfaces. Both carry the same text protocol.

### Bluetooth Classic (main path, used by both apps) ✅
* SPP / RFCOMM, UUID `00001101-0000-1000-8000-00805F9B34FB`.
* Pairing: ignition ON, press the Bluetooth button on the left handlebar, then pair.
* B tries the SPP UUID first, then falls back to `createRfcommSocket(1)`, i.e. **channel 1**.

### Bluetooth LE (alternative) 🟡
* Device name **`GESITS`**. Characteristic **`FFF2`** (`0000fff2-0000-1000-8000-00805f9b34fb`) does
  NOTIFY + READ + WRITE (seen in nRF Connect). B uses service UUID
  `0000fff1-0000-1000-8000-00805f9b34fb` as its default, and lets the user change both UUIDs.
* Write **with response**. FFF2 has no WRITE_NO_RESPONSE, and such writes are silently ignored.
* Replies arrive as notifications on FFF2.
* Long commands (AT+MAPS) don't fit in one write. Send `AT+MAPS="`, wait 50 ms, then the base64 text in
  **100-character pieces with 30 ms gaps**, then `"\r\n`. This is "exactly the protocol of the ESP32 sketch
  that successfully sent to the MID".
* B says the BT module sometimes "gets stuck" in LE mode, and then LE is the way in. LE is also the
  **only** route for a plain web page in phone Chrome (Web Bluetooth can't do Classic).

### Framing ✅
* Plain ASCII, one command per line, ending `\r\n`.
* Keep **≥ 30 ms between commands** and **one command in flight**. Don't flood the link: B holds back
  polling while a picture is being written, otherwise the MID channel backs up and the map lags.
* Replies are lines of `KEY=value\r\n`. Sometimes they come back with an `AT+` prefix, so strip it.

## 2. Reading values: `AT+<KEY>?`

Request: `AT+SPD?\r\n` → reply `SPD=42\r\n`. Values can be decimals (`VBAT=71.8`).

| Key | Meaning | Status |
|---|---|---|
| `SPD` | Speed, km/h | ✅ |
| `SOC` | Battery charge, % | ✅ |
| `ODO` | Odometer, km | ✅ |
| `MODE` | Drive mode: **0 = ECO, 1 = URBAN, 2 = SPORT** | ✅ |
| `PARK` | **1 = PARK, 0 = DRIVE**. A's reading was the wrong way round. | ✅ |
| `VBAT` | Battery voltage, V | 🟡 |
| `IBAT` | Battery current, A (negative = regen); power = VBAT × IBAT | 🟡 |
| `SOH` | Battery health, % (B: < 80 "declining", < 60 "bad") | 🟡 |
| `TBAT` | Battery temperature, °C (B warns at 45, critical at 55) | 🟡 |
| `TMOT` | Motor temperature, °C (B warns at 70, critical at 85) | 🟡 |
| `TINV` | Inverter/controller temperature, °C | 🟡 |
| `TRT` | Throttle | 🟡 (B: the "real throttle data") |
| `CBAT` | Battery value, meaning unclear | ❓ |
| `ERR` | Error bitmask, see below | 🟡 |
| `FWD` | **Not** the drive mode. Stays 1 whatever you do. | ✅ unclear |
| `SOC2`, `NUMBAT` | Second battery / battery count (A only) | ❓ |
| `PWR` | **Not supported**: the MID answers with an error | ✅ |

B's poll schedule: every 100–200 ms it sends the single most overdue key. Periods: SPD/IBAT 0.5 s ·
VBAT 1 s · TRT/ODO 2 s · SOC 4 s · ERR/MODE/PARK/CBAT 8 s · TINV/TMOT/TBAT 15 s · SOH 30 s.

This project's Android app (v0.17) is deliberately much slower: at most 2 commands a second in total,
because a G1's MID (`11-50-09-G1`) froze under heavier traffic. Its periods: SPD 1 s · IBAT 2 s · VBAT,
SOC, MODE, ODO, PARK 5 s · ERR 10 s · TINV/TMOT/TBAT 15 s · SOH/RANGE 60 s (x3 parked).

### ERR bits 🟡 (B: "adjust bit positions to the motor firmware"; not official)

| Bit | Meaning |
|---|---|
| 0 | Inverter over temperature |
| 1 | Motor over temperature |
| 2 | Motor fault |
| 3 | Hall sensor fault |
| 4 | Throttle fault |
| 5 | Controller fault |
| 6 | Over voltage |
| 7 | Under voltage |
| 11 | `ERR=0800` reported by a user together with a real throttle problem. Unofficial. |

`0`/`0000` = no error. The value is 4 digits, so it is read as **hex** (`0800` → bit 11). B's own code parses
it as decimal unless it starts with `0x`, which contradicts its own `0800` = bit 11 note.

## 3. Writing to the MID

| Command | Effect | Status |
|---|---|---|
| `AT+TIME="HHmm"` | Set the MID clock (sent every second by A) | 🟡 |
| `AT+NAV="<text>"` | Text line on the MID, **max 20 characters** | ✅ |
| `AT+TRIP="12.3"` | The **range** (remaining km) figure on the MID, sent every 3 s | 🟡 |
| `AT+MAPS="<base64 JPEG>"` | Picture in the map area | ✅ |
| `AT+ODO="<km>"` | **Writes the odometer** (B uses it to "sync" upward) | 🟡 not in our tools |
| `AT+END` | End of session (A sends it without CR LF) | 🟡 |
| `AT+VER?` | MID firmware version (C shows "MID ver …") | 🟡 |
| `AT+ARROW="1"` | Sent once on connect by C; effect unknown | ❓ |
| `AT+MAPON="1"/"0"`, `AT+MAPEND` | Map mode on/off (A only; B never sends these) | ❓ |

### GRID Mobile's connect sequence (C)
C keeps a command list, sends each entry with 10 ms between them, and **removes an entry once the
MID answers with that key**. That includes the writes: TIME, NAV, ARROW and MAPS get an answer
too. Initial list: `AT+TIME="HHmm"`, `AT+VER?`, `AT+MODE?`, `AT+MAPS="<logo>"`,
`AT+NAV="<date, e.g. 31 Oktober 2026>"`, `AT+ARROW="1"`, then the usual queries. Queries are
re-added after a few seconds to keep polling.

### AT+NAV text rules ✅
* Seen on a G1: the text is drawn over the picture **without erasing**, so a changed digit lands on
  the old one ("range 5/46 km"). A fresh `AT+MAPS` picture wipes the area; send the new text right after it.
* ASCII only. B strips accents and replaces other characters with spaces.
* Max 20 characters. Longer text is split at the space nearest the middle into two parts. B rotates
  between items every 2–5 s.
* **Pad every message to exactly 20 characters with spaces.** The MID only redraws characters that
  changed, so leftovers from a longer previous text stay on screen (confirmed MID bug).

### AT+MAPS picture ✅
* **Base64 JPEG**, default **350 × 220** px. MID limits noted by B: width ≤ 350, height ≤ ~500.
* Base64 length **≤ 7000 characters**. Bigger frames freeze the MID, and a cut-off frame is ignored. Lower
  the JPEG quality until it fits.
* Send the whole frame in one write on Classic. On LE, send it in pieces as described in §1.
* B streams a live map every 1 s while navigating and every 3 s otherwise. It can also mirror the phone
  screen (cropped JPEG) or show a fixed custom picture such as a logo or wallpaper.
* A instead sent a 164 × 88, 4-colour, 2-bit-packed hex bitmap (`gesits/mapimage.py`, `--legacy`). That
  is probably for old MID firmware. Use JPEG.

### Picture size: use 320 × 128
GRID Mobile's built-in MID pictures (GRID logos and others) are all **320 × 128** baseline JPEGs of
about 4,000 base64 chars, with content centred on black. That is the MID's visible map area. Our photo
of a 350 × 220 picture shows the extra area spilling under the MID's own widgets. The tools in this repo
now use 320 × 128.

### Seen on a real G1 (photo, 350×220 JPEG via the community-built app in this repo)
* The picture's top-left sits just inside the MID's red map frame, about 55 px below the frame's top edge.
  The space above is likely kept for the `AT+NAV` text line.
* Roughly the top **164 px** of a 220 px picture show inside the frame. Below that, the MID's frame line,
  range circle and battery gauge are drawn over the picture. The app's dashboard keeps a 56 px bottom
  margin by default; the calibration grid (app → "Calibration grid") measures this exactly.
* `AT+TRIP` changes the MID's "Range" figure, but the MID puts its own value back (when updates stop,
  and apparently when it redraws after a picture), so the app re-sends it every 0.25 s and 0.4 s after
  each picture. Seen on a G1: the display switches between the two values (MID 50, app 100), so the
  MID keeps recalculating; the app now sends only a learned value. GESITS Dashboard sent it every 0.5 s in older versions, every 3 s now. Still being checked on a real bike.

## 4. Other devices B connects to (not the MID)
* **JK-BMS** (battery management system) over BLE: service `FFE0`, notify `FFE1`, write `FFF2`.
* **ESP32 CAN sniffer** over BLE Nordic UART (`6E400001/2/3-…`). Adds `CRZ` (cruise on/off) and `REM` (brake),
  which "are not available from the MID".

### Found on a real G1 (MID firmware `11-50-09-G1`, laptop self-test over Classic COM port)
* Replies carry an `AT+` prefix: `AT+VER=11-50-09-G1`.
* `AT+RANGE?` → `AT+RANGE=050.0`: the MID's **own range figure** (separate from `AT+TRIP`).
* `AT+NUMBAT?` → `AT+NUMBAT=1` (number of batteries?), `AT+FWD?` → `AT+FWD=1`, `AT+CBAT?` → `AT+CBAT=00000`.
* `AT+TRIP="123"` shows 123 on the MID but it **reverts instantly** to its own 50: the MID refreshes
  its range many times a second, so overriding it with `AT+TRIP` can only flicker.
* No reply to `CYCLE`, `CAP`, `PWR`, `RPM` and the other probe names.
* **Don't write `AT+RANGE="…"`:** the MID read back 050.0, then stopped answering anything until the
  ignition was switched off and on. `AT+NUMBAT="1"` was accepted without effect.
* So the app shows its adaptive range in the `AT+NAV` text line instead.
* `IBAT` sign depends on the battery: with this bike's custom pack, current drawn is **negative**
  (`IBAT=-1` and -81 W while parked, rides adding up to negative energy). The app learns the sign while riding.

## 5. Still open
* Units/meaning of `CBAT` and `TRT`. Official ERR table.
* ~~Does the MID acknowledge writes?~~ Seen on a real G1 (app v0.13.00): **no**. It answers `AT+KEY?`
  questions, but sends nothing back for `AT+MAPS`, `AT+TIME`, `AT+NAV` or `AT+TRIP`.
* Exact LE service UUID on your bike (check with nRF Connect).
