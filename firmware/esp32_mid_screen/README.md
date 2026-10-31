# ESP32 custom MID screen (Bluetooth LE)

A small ESP32 that rides on the bike and turns the stock MID's map area into your own
dashboard. No phone needed. It talks to the MID over **Bluetooth LE**: device `GESITS`,
characteristic `FFF2`. That is the "other" connection; the phone apps normally use
Bluetooth Classic.

What it does, in a loop:
1. Reads `SPD IBAT VBAT SOC ODO MODE PARK ERR TBAT TMOT TINV`, one key every 200 ms.
2. Every 3 s, draws a 350×220 dashboard, JPEG-encodes it, and sends it with `AT+MAPS`.
3. Every 3 s, sends the range estimate (`AT+TRIP`). Every 5 s, rotates your text lines (`AT+NAV`).

The layout matches `python -m gesits screen --preview screen.png`, so you can design on a PC first.

> **Status:** written from the protocol in `docs/PROTOCOL.md` and syntax-checked, but **not yet
> compiled for, or run on, a real ESP32 or bike** (the toolchain wasn't available where it was
> written). Expect to fix small things on the first build. Please report what the serial monitor prints.

## Parts
* Any ESP32 with BLE: ESP32 DevKit, ESP32-S3 or ESP32-C3. Classic Bluetooth isn't needed.
* 5 V power: the bike's USB port if it has one, or a 12 V→5 V buck converter on switched
  (ignition) power, with an inline fuse. Keep it dry and fixed in place.

## Build (Arduino IDE)
1. Boards Manager: install **esp32 by Espressif** (2.x or 3.x).
2. Library Manager: install **Adafruit GFX Library** and **JPEGENC** (by Larry Bank).
3. Open `esp32_mid_screen.ino` and adjust `config.h` (text lines, optional Wi-Fi for the clock).
4. Tools → Partition Scheme: **Huge APP (3MB No OTA)**. BLE (plus Wi-Fi) needs the space.
5. Upload, then open the Serial Monitor at 115200 baud.

## First test (parked, stand down)
1. Ignition ON. **Disconnect phone apps from the bike** first; the MID may accept only one link.
2. Serial should print `Connected. MTU …`, then `frame: … b64 (q…)` every few seconds.
3. The MID map area should show the dashboard.

| Serial says | Try |
|---|---|
| `MID not found` | Check that the MID's Bluetooth is on (handlebar button), and look for `GESITS` in the nRF Connect app. If it isn't there, the MID's BLE side may only appear in some situations (GESITS Dashboard says the module "sometimes gets stuck in LE mode"); tell me what you see. |
| `FFF2 not found` | In nRF Connect, note the characteristic UUIDs under the `GESITS` device and set `MID_CHAR_UUID16`. |
| `frame too big` | Lower `FRAME_W`/`FRAME_H` (e.g. 300×190) or simplify `drawScene()`. |
| Connected but nothing on the MID | Run the Python tool from a laptop over BLE (`python -m gesits image ble://GESITS pic.jpg`) to check that `AT+MAPS` works over LE on your bike. |

## Changing the design
Edit `drawScene()`. Coordinates are for the 350×220 picture; always subtract `yo` from y, because
the picture is drawn and encoded 16 rows at a time to save memory (the `rrect`/`txt` helpers do
this for you). Keep flat colours and large text: the JPEG must stay under 7,000 base64 characters.
