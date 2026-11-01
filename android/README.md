# Gesits MID - Android app

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../docs/img/cakra_dark.png">
  <img src="../docs/img/cakra_light.png" alt="CAKRA Labs" height="48">
</picture>

Made by **CAKRA Labs** · Universitas Pertamina · [cakra-labs.id](https://cakra-labs.id/). A community tool, not an official Gesits app.

Your own small app for the Gesits G1 dashboard (MID). Every line is in this folder, and
GitHub Actions builds it, so you know exactly what you install.

## Layout
One screen with four tabs at the bottom:
* **Home:** connect button and status, live values, alerts, Speedometer and Diagnosis buttons, current trip.
* **MID:** preview of what the MID shows; what to show (live info, your picture or nothing), dashboard
  pages, text line, adaptive range, what the MID accepted, turn-by-turn directions, speed warning, clock.
  Position and calibration are under *Advanced*.
* **Trips:** this week vs your target, totals, savings vs petrol, battery health and cycles, last 7 days,
  records, history, CSV export.
* **Tools:** maps and parking, alarms, keep-connected settings, language (English / Bahasa Indonesia),
  home-screen widget, privacy and permissions, and
  (under *Advanced*) the console and log.

## What it does
* Connects to the bike over **Bluetooth Classic** (paired, the usual way) **or Bluetooth LE** (`GESITS`).
* Shows live data: speed, battery, health, power/regen, voltage and current, mode, gear, odometer,
  temperatures and errors.
* Sends a picture to the MID map area every few seconds: **your own live dashboard** (same design as
  `docs/img/custom_screen.png`) or **a picture you choose**.
* **Adaptive range on the MID:** learns your real km per 1% battery from the odometer while you
  ride (most recent ~60% of battery used). It starts from a value you set (default 0.5 km per 1%,
  about 50 km for a full G1 battery), which fades out after 20% of battery has been learned. **Only
  once learned** does it send battery % × that to the MID's range display (`AT+TRIP`), every 0.25 s
  and right after each picture: the MID keeps recalculating its own figure, so it may still briefly
  show that one. Until then the MID keeps its own range. Charging, gaps over 30 min without the app, and impossible
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
* **Battery health and cycles:** the MID doesn't report the battery's lifetime cycle count (the app
  asks for it anyway and shows it if your bike answers). So the app counts from the day it's installed:
  **equivalent full cycles** (total % used ÷ 100, so four 25 % rides = 1 cycle), **charging sessions**,
  **usable capacity** (energy used ÷ % used, after about 20 % of riding) and the **health (SOH) trend**
  the battery reports, one point a day.
* **Charging:** while the bike is charging (current flowing in while stopped), Home shows % per hour
  and time to 80/90/100 %, and a notification tells you when your chosen level is reached.
* **Turn-by-turn directions:** start navigation in Google Maps; the MID picture switches to a big
  turn arrow, distance, street and arrival time, and the text line shows the next turn. Needs
  *notification access* (MID tab → Directions). The app reads only Google Maps' notifications, keeps
  only the latest instruction in memory, and has no internet permission to send it anywhere.
* **Speed warning:** pick 50/60/70/80 km/h; after 3 s over it the MID text line shows a warning.
* **MID accepted:** the MID answers each write it takes; the MID tab shows which ones it confirmed, and
  a picture that wasn't confirmed is sent again.
* **Home-screen widget:** battery % and range, updated about once a minute while connected.
* **Bahasa Indonesia** throughout (follows the phone, or pick it in Tools).
* **Speedometer:** full-screen landscape dashboard on the phone (speed, battery, range, power or regen,
  trip, temperatures).
* **Maps hand-off:** "Navigate" and "SPKLU near me" open Google Maps, so this app itself stays offline.
  Optional **"Remember where I parked"** saves the phone's GPS position when you park or switch off.
* Keeps running with the screen off, shown by a "Gesits MID" notification. Stop it with **Disconnect**.

## Why it's "safe"
| | This app | GESITS Dashboard (community) |
|---|---|---|
| Internet | **none** (no INTERNET permission) | yes |
| Permissions | Bluetooth, notifications, foreground service; location only if you switch on "Remember where I parked" (Android 8–11 also need it for BLE scanning); notification access only if you use directions, and then only Google Maps' notifications are read | + camera, microphone, screen capture, overlay, **install other apps**, storage |
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

**Signing (one-time setup, so updates always install over the old version):**
1. On your laptop, in the repo folder: `pip install cryptography`, then `python scripts/make_signing_key.py`.
2. It creates `gesits-mid-release.p12` (your key: **keep a backup**, never share or commit it) and
   `gesits-mid-github-secrets.txt`.
3. On GitHub: **GESITS_MID_pub → Settings → Secrets and variables → Actions → New repository secret**,
   and add the two values from that text file: `GESITS_KEYSTORE_PASSWORD` and `GESITS_KEYSTORE_B64`.
   Then delete the text file.
4. Every build from then on is signed with that key; the release notes say "permanent CAKRA Labs key".
   Uninstall the old app **once** before installing the first permanently-signed version.

Without the secrets, builds use a temporary test key and may need the old app uninstalled first.

## Build it yourself
`gradle -p android assembleRelease` with JDK 17 and the Android SDK (compileSdk 34). Or push a
change and let the "Android APK" workflow build it.
