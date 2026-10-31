# Gesits MID - Android app

Your own small app for the Gesits G1 dashboard (MID). Every line is in this folder, and
GitHub Actions builds it, so you know exactly what you install.

## What it does
* Connects to the bike over **Bluetooth Classic** (paired, the usual way) **or Bluetooth LE** (`GESITS`).
* Shows live data: speed, battery, health, power/regen, voltage and current, mode, gear, odometer,
  temperatures and errors.
* Sends a picture to the MID map area every few seconds: **your own live dashboard** (same design as
  `docs/img/custom_screen.png`) or **a picture you choose**.
* **Adaptive range on the MID:** learns your real km per 1% battery from the odometer while you
  ride (most recent ~60% of battery used) and sends battery % × that to the MID's range display
  (`AT+TRIP`) every 3 s. It starts from a value you set (default 1.0 km per 1%), which fades out after
  20% of battery has been learned. Charging, gaps over 30 min without the app, and impossible
  readings are ignored. **Forget learned range** starts over (e.g. after a battery change).
* **Clock sync:** sets the MID clock from your phone on connect, at every minute change, and every 30 s.
* Optionally shows your own text line (rotating, 20 characters per step).
* **Stats:** rides are recorded automatically (start when moving, end after 5 min stopped). Shows
  totals, a weekly target, 8-week and 7-day charts, records, average Wh/km, regen recovered,
  **savings vs a petrol motorcycle** (Rp after electricity cost, litres, CO₂; prices adjustable),
  ride history, and CSV export.
* **Diagnosis:** live charts (speed/power, throttle, temperatures, last 10 min) and MID errors with
  plain-language advice. **Alarms** (phone notifications): low battery, hot battery or motor, battery
  health below 75%, MID errors.
* **Speedometer:** full-screen landscape dashboard on the phone (speed, battery, range, power or regen,
  trip, temperatures).
* **Maps hand-off:** "Navigate" and "SPKLU near me" open Google Maps, so this app itself stays offline.
  Optional **"Remember where I parked"** saves the phone's GPS position when you park or switch off.
* Keeps running with the screen off, shown by a "Gesits MID" notification. Stop it with **Disconnect**.

## Why it's "safe"
| | This app | GESITS Dashboard (community) |
|---|---|---|
| Internet | **none** (no INTERNET permission) | yes |
| Permissions | Bluetooth, notifications, foreground service; location only if you switch on "Remember where I parked" (Android 8–11 also need it for BLE scanning) | + camera, microphone, screen capture, overlay, **install other apps**, storage |
| Third-party libraries | **none** | Mapbox, BouncyCastle, Play Services, ... |
| Writes odometer | **never** | yes (`AT+ODO=`) |
| Source | this repo, built by GitHub Actions | closed APK |

## Battery and memory
* The background loop sleeps until the next job is due, instead of waking 50 times a second.
* Values are polled at different rates: power and speed every 1 s, battery % every 5 s, temperatures
  every 15 s, battery health every 60 s. Three times slower while parked; faster only while the
  Speedometer or Diagnosis screen is open.
* A MID picture is sent only when what it shows changes (re-sent once a minute). The JPEG search
  starts from the last quality that fitted, so it usually takes 1–2 encodes.
* The wake lock is held **only while the bike is connected**. With the bike off, reconnect attempts
  back off from 2 s to 60 s, and the app **stops itself after 60 minutes** without the bike.
* State is saved every 60 s rather than constantly. There are no libraries, and R8 shrinks the APK.

## Install
1. On your phone, open the repo on GitHub → **Releases** → **android-latest** → download `GesitsMid-N.apk`.
2. Open it and allow "install unknown apps" for your browser when Android asks (only needed once).
3. Open **Gesits MID**, allow Bluetooth (and notifications).
4. Pair the bike in Android Bluetooth settings (ignition ON, handlebar Bluetooth button), then in the
   app tap **Choose bike - Bluetooth Classic**. Or use **Bluetooth LE** to scan for `GESITS`.
5. **Connect**. Close other Gesits apps first; the MID may accept only one connection.

**Signing.** No signing key is stored in this repo. By default GitHub signs with an Android debug key
that it keeps in its build cache, so new builds install over old ones. If that cache expires (after 7 days
without builds), uninstall the old app before installing a new build. For a permanent key, add two
repository secrets, `GESITS_KEYSTORE_B64` (a `.jks` file as base64) and `GESITS_KEYSTORE_PASSWORD`;
the workflow then signs with your key.

## Build it yourself
`gradle -p android assembleRelease` with JDK 17 and the Android SDK (compileSdk 34). Or push a
change and let the "Android APK" workflow build it.
