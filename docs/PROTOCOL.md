# Gesits G1 MID Bluetooth protocol

Reconstructed by disassembling the Gesits Android app (`com.gesits.app.mobile`, file
`Gesits_Mod_1.5.2.apk`). **Nothing here has been checked against a real bike yet.** It is
what the app sends and what it expects back. Each section says how sure we are.

## 1. Transport

| Item | Value | Confidence |
|---|---|---|
| Radio | **Bluetooth Classic** (not BLE) | certain (app uses `BluetoothSocket`) |
| Profile | Serial Port Profile (RFCOMM) | certain |
| UUID | `00001101-0000-1000-8000-00805F9B34FB` (standard SPP) | certain |
| Pairing | Press the Bluetooth button on the left handlebar with ignition ON, then pair from the phone | from dealer description |
| Encoding | Plain ASCII text | certain |
| Line ending | `\r\n` (CR LF) on commands and on replies | certain |
| RFCOMM channel | Not in the app (it asks SDP); usually **1** for SPP modules | guess |

The app reads in 1024-byte chunks and joins them until it sees `\r\n`.

## 2. Reading values: `AT+<KEY>?`

Request:  `AT+SPD?\r\n`
Reply:    `SPD=<value>\r\n`

The app takes the text after `KEY=` and converts it with `toInt()`. It sends **one key at a
time** in round-robin. Each screen polls its own list:

| Screen in app | Keys polled (in order) |
|---|---|
| Home (Beranda) | `SOC2 SOC NUMBAT SOH MODE` |
| Battery | `SOC SOH TBAT SOC2 VBAT CBAT NUMBAT TRIP` |
| Diagnostic (speedometer/throttle) | `TMOT TINV ERR PARK FWD TRT SPD` |
| Error detail | `ERR` (repeatedly) |

| Key | Meaning (from the app's labels) | Notes |
|---|---|---|
| `SOC` | State of charge, battery 1, % | shown as `NN %`; red when ≤ 20 |
| `SOC2` | State of charge, battery 2, % | used when `NUMBAT` = 2 |
| `NUMBAT` | Number of batteries (1 or 2) | |
| `SOH` | State of health, % | |
| `MODE` | Drive mode | `0`=ECO, `1`=URBAN, `2`=SPORT |
| `TBAT` | Battery temperature | integer, unit text from app resources |
| `VBAT` | Battery voltage | integer |
| `CBAT` | Battery current | integer |
| `TRIP` | Trip | polled but not displayed |
| `TMOT` | Motor temperature | integer |
| `TINV` | Inverter (controller) temperature | integer |
| `ERR` | Error code | `0000` = no error |
| `PARK` | Parking status | shown as ON/OFF |
| `FWD` | Forward/reverse status | shown as ON/OFF |
| `TRT` | Throttle | integer |
| `SPD` | Speed | integer (km/h presumably) |

Numbers the app works out itself (not sent by the bike):

* **Range** = `SOC × 1.0` km
* **Energy left** = `(SOC + SOC) × 0.014` kWh (a bug: the app adds `SOC` to itself, not `SOC2`)

## 3. Writing to the MID

| Purpose | Exact bytes | When the app sends it |
|---|---|---|
| Set clock | `AT+TIME="HHmm"\r\n` (e.g. `AT+TIME="0705"`) | every 1 s while connected |
| Enter map mode | `AT+MAPON="1"\r\n` | before the first map frame |
| Navigation text | `AT+NAV="<text>"\r\n` | with every map frame |
| Map picture | `AT+MAPS="<7216 hex chars>"\r\n` | with every map frame |
| Leave map mode | `AT+MAPON="0"\r\n` then `AT+MAPEND\r\n` | navigation stopped |
| End session | `AT+END` (**no** CR LF) | on disconnect |
| Logout | `AT++END\r\n` (double `+`, sic) | on account logout |

Navigation text looks like `"<distance, 1 decimal> m <maneuver>"`, where maneuver is the
Google Directions name, e.g. `120.0 m turn-left`. On arrival the app sends `You has arrived!`.

### 3.1 Map picture format (`AT+MAPS`)

The **MID can show custom graphics**, and this is how:

1. Scale the picture to **164 × 88 px** (nearest neighbour).
2. Turn each pixel into a 2-bit code. The checks run in this order:
   * R, G, B all ≥ 210 → `1` (white)
   * R, G, B all ≤ 40 → `0` (black)
   * B ≥ 210 → `2` (blue)
   * R ≥ 210 → `3` (red)
   * anything else → `1` (white)
3. Go through the pixels row by row, left to right, and pack **4 pixels per byte with the
   first pixel in the lowest 2 bits** (pixel *n* of the byte → bits 2n…2n+1).
4. Write the 3608 bytes as **upper-case hex** → 7216 characters.

`gesits/mapimage.py` does exactly this. Its tests check it against a line-by-line port of the
app's own routine, which works on 64-bit words. We don't know yet which colour the MID
actually shows for each code.

The app also has a "simulate map" screen that sends `AT+MAPS="<base64 JPEG>"` (50×50 and
41×40 JPEGs). That may be left-over test code, or a format that only some MID firmware
accepts. **Use the hex format first.**

## 4. Things still to confirm on the real bike

* Which RFCOMM channel to use (try 1).
* Units and scaling of `TBAT VBAT CBAT TMOT TINV TRT SPD`.
* Meaning of `PARK`/`FWD` values and the `ERR` code table.
* Whether the MID replies to set-commands (`TIME`, `NAV`, `MAPS`, …) with `OK` or similar.
* Whether `AT+MAPON="1"` hides the speedometer. **Don't test map mode while riding.**
* Whether the MID accepts other commands the app never uses. Try them with `gesits raw` while
  parked.

To capture real traffic: Android → Developer options → **Enable Bluetooth HCI snoop log**,
use the app with the bike, then grab `btsnoop_hci.log` (via `adb bugreport`) and open it in
Wireshark (filter `btspp` or `btrfcomm`).
