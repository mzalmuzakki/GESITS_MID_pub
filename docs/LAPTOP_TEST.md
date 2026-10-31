# Testing with a laptop (about 15 minutes)

Everything here only **reads** from the bike, except step 5, which writes the clock, the range number and one picture.
Park with the stand down and the ignition ON. **Close the Gesits apps on your phone first**: the MID may accept only one connection.

## 1. Install (once)
**Windows (easiest):**
1. On GitHub, **Code → Download ZIP**, then unzip it (e.g. to `Downloads\GESITS_MID_pub-main`).
2. In that folder, **double-click `setup_windows.bat`**. It installs Python if needed (via winget), installs
   the packages, and runs a quick test against a built-in fake bike. If it says Python was just installed,
   close the window and double-click it once more.

**Linux / macOS / manual:** install Python 3.9+, then in the unzipped folder run
```
pip install pillow pyserial bleak
```

## 2. Pair the bike
Ignition ON, then press the **Bluetooth button** on the left handlebar.
* **Windows:** Settings → Bluetooth & devices → Add device → Bluetooth, and pick the Gesits / MID entry.
  Then open *More Bluetooth settings → COM Ports* and note the **Outgoing** COM port (for example COM5).
* **Linux:** `bluetoothctl` → `scan on` → `pair AA:BB:..` → `trust AA:BB:..`. Note the address.
* **macOS:** System Settings → Bluetooth. Classic serial is unreliable on macOS, so use BLE (step 3b).

## 3. Self-test (read-only), then send me the report
Run whichever one fits:
```
# a) Bluetooth Classic
python -m gesits selftest serial://COM5                  # Windows (your COM port)
python -m gesits selftest bt://AA:BB:CC:DD:EE:FF         # Linux

# b) Bluetooth LE (works on Windows, Linux and macOS)
python -m gesits selftest ble://GESITS
```
It writes **`gesits_report.txt`**. Send me that file. If one way fails, try the other and send the error text too.

## 4. Live view
```
python -m gesits monitor serial://COM5          # or bt://... / ble://GESITS
```
Ride a few metres if you can, and watch SPD, power and the temperatures change. Ctrl+C stops it.

## 5. Writes (only if steps 3 and 4 worked)
```
python -m gesits time   serial://COM5           # MID clock = laptop clock
python -m gesits range  serial://COM5 77        # does the MID's Range show 77.0 km?
python -m gesits screen serial://COM5 --once    # our dashboard picture on the MID
```
Take a photo of the MID after each one.

## If something goes wrong
| Problem | Try |
|---|---|
| `could not open port` / timeout | Use the other COM port number. Make sure no phone app is connected. |
| `BLE device 'GESITS' not found` | Ignition ON? Press the MID's Bluetooth button. Scan with nRF Connect to see the exact name, then use `ble://<name>`. |
| Replies are all `(no reply)` | Run `python -m gesits raw <uri> "AT+SPD?"` and send me what it prints. |
