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
  pages, text line, adaptive range, what was sent to the MID, turn-by-turn directions, speed warning, clock.
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
* **Pictures on the MID map area are off by default** (they made a G1's MID freeze, see *Safety*). If you
  switch them on: **your own live dashboard** (same design as `docs/img/custom_screen.png`) or **a picture
  you choose**, at most one every 10 s and at most 4000 characters each (not over a Bluetooth LE link with
  small packets, where one picture would keep the link busy for 15-25 s).
* **Adaptive range in the MID text line:** range = battery % × km per 1%, measured from the odometer
  and battery % over your last 40% of riding. It starts at the original G1 battery's 0.5 km per 1%
  (~50 km) and settles after about 10% of battery ridden, so a bigger battery is picked up within a ride.
  The Range card also shows energy figures (battery kWh, Wh/km). The range is shown as "RANGE 112 km"
  in the MID's text line, because the MID's own range figure is its own calculation for the original
  battery and can't be replaced over Bluetooth on a G1. The MID draws the text line without erasing, so
  a new text goes out after a blank one (and is drawn again after each picture, if pictures are on).
  **Forget learned range** starts over.
* **Clock sync:** sets the MID clock from your phone on connect and whenever the minute changes.
* Optionally shows your own text line (rotating every 15 s, 20 characters per step).
* **Stats:** rides are recorded automatically (start when moving, end after 5 min stopped). Shows
  totals, a weekly target, 8-week and 7-day charts, records, average Wh/km, regen recovered,
  **savings vs a petrol motorcycle** (Rp after electricity cost, litres, CO₂; prices adjustable),
  ride history, and CSV export.
* **Diagnosis:** live charts (speed, power, temperatures, last 10 min) and MID errors with
  plain-language advice. **Alarms** (phone notifications): low battery, hot battery or motor, battery
  health below 75%, MID errors.
* **Battery health and cycles:** the MID doesn't report the battery's lifetime cycle count (that lives in
  the battery's BMS). So the app counts from the day it's installed:
  **equivalent full cycles** (total % used ÷ 100, so four 25 % rides = 1 cycle), **charging sessions**,
  **usable capacity** (energy used ÷ % used, after about 20 % of riding) and the **health (SOH) trend**
  the battery reports, one point a day.
* **Charging:** while the bike is charging (current flowing in while stopped), Home shows % per hour
  and time to 80/90/100 %, and a notification tells you when your chosen level is reached.
* **Turn-by-turn directions:** start navigation in Google Maps; the MID text line shows the next turn and
  street (and, if pictures are on, the picture shows a big arrow, distance and arrival time). Needs
  *notification access* (MID tab → Directions). The app reads only Google Maps' notifications, keeps
  only the latest instruction in memory, and has no internet permission to send it anywhere.
* **Speed warning:** pick 50/60/70/80 km/h; after 3 s over it the MID text line shows "! SLOW DOWN 70"
  for at least 10 s.
* **Sent to the MID:** when the picture, clock and text were last sent. A G1's MID doesn't
  confirm writes, so the app can't tell whether it took them: check the MID itself.
* **Home-screen widget:** battery % and range, updated about once a minute while connected.
* **Bahasa Indonesia** throughout (follows the phone, or pick it in Tools).
* **Speedometer:** full-screen landscape dashboard on the phone (speed, battery, range, power or regen,
  trip, temperatures). If the speed is more than 5 s old it shows **NO DATA** instead of a frozen number.
  Don't rely on the phone for your speed.
* **Maps hand-off:** "Navigate" and "SPKLU near me" open Google Maps, so this app itself stays offline.
  Optional **"Remember where I parked"** saves the phone's GPS position when you park or switch off.
* Keeps running with the screen off, shown by a "Gesits MID" notification. Stop it with **Disconnect**.

## Safety: the MID can freeze
On a G1 (MID `11-50-09-G1`) the MID froze after ~5 minutes of heavy traffic (pictures, text, many
questions): speed and turn signals stuck until the ignition was switched off and on. What the app does:
* **No pictures by default.** Turning them on (MID tab) is at your own risk.
* **Gentle polling:** about 1.6 questions a second while riding and 0.9 parked, with a hard limit of 2
  commands a second in total (questions and writes together), whatever screen is open. The clock is set
  when the minute changes and the text line only when it changes; nothing is re-sent "just in case".
  Your own long text rotates every 15 s; the speed warning, once shown, stays at least 10 s.
* **Only known commands**: questions about the values the MID was seen to answer, the clock, the text line
  and (if on) pictures. Never the odometer, the MID's range or any other write; the console can only ask
  those same questions, one at a time.
* **Writes only while the MID answers**, checked again right before each write; a write that was held
  back is tried again later, never counted as done. On a new connection only one question every 3 s goes
  out until the MID answers.
* **If the MID stops answering for 8 s:** the app stops asking (one question every 3 s). It counts as a
  hang - with a warning each time - if:
  * you were moving when the answers stopped and the link is still up 15 s later;
  * the link is still up after 30 s of silence (also when the MID never answers on a new connection);
  * a write gets stuck (the MID stops taking data);
  * the link dropped while you rode and, when it opens again (so the bike is on), the MID stays silent
    15 s; or the MID doesn't answer on 3 connections in a row.

  If the link drops while you're stopped, or you switch off right after pulling up, the bike was most likely
  switched off: no warning (no link can open while it's off). While hung, the app asks one question every 15 s
  - also across reconnects, Disconnect/Connect and app restarts - until the MID answers again.
* **After a hang (or frozen values) the MID only gets questions**, no text, clock or pictures - also after app
  restarts - until you look at the MID and tap **"The MID works normally again"** on Home.
* **Frozen but answering:** if the speed shows 15 km/h or more for 90 s while the odometer doesn't move, the
  app warns you, shows NO DATA on the phone speedometer and stops writing. A MID that keeps answering while its
  display is frozen can't otherwise be told apart - always watch the MID itself.
* Live values that stop coming in are cleared (all of them after 8 s of silence, single ones once they are
  well overdue); the phone speed shows NO DATA when it's more than 5 s old - never a frozen number.
* All timing uses a clock that can't be changed (changing the phone's date or time doesn't affect it).

These lower the risk; they can't remove it - the MID's firmware is undocumented. This version has not yet
been tested on a bike for long periods. **Test it parked first. If the MID ever freezes: stop safely and
switch the bike off and on.**

## Why it's "safe"
| | This app | GESITS Dashboard (community) |
|---|---|---|
| Internet | **none** (no INTERNET permission) | yes |
| Permissions | Bluetooth, foreground service; notifications (optional); location only if you switch on "Remember where I parked" (Android 8–11 also need it to scan for a Bluetooth LE bike, not for a paired Classic one); notification access only if you use directions, and then only Google Maps' notifications are read | + camera, microphone, screen capture, overlay, **install other apps**, storage |
| Third-party libraries | **none** | Mapbox, BouncyCastle, Play Services, ... |
| Writes odometer | **never** | yes (`AT+ODO=`) |
| Source | this repo, built by GitHub Actions | closed APK |

## Battery and memory
* The background loop sleeps until the next job is due, instead of waking 50 times a second.
* Values are asked at different rates: speed every 2 s, current, odometer and gear every 5 s, voltage,
  battery % and errors every 10 s, temperatures every 30 s, battery health every 2 minutes; three times
  slower while parked (except speed); never more than 2 commands a second in total.
* Pictures (if on) are sent when what they show changes (and once a minute), at most every 10 s; a text
  change never forces one.
* The wake lock is held **only while the MID is answering**. With the bike off, reconnect attempts
  back off from 2 s to 60 s, and the app **stops itself after 60 minutes** without an answer.
* State is saved every 60 s rather than constantly. There are no libraries, and R8 shrinks the APK.

## Install
1. On your phone, open the repo on GitHub → **Releases** → **android-latest** → download `GesitsMid-N.apk`.
2. Open it and allow "install unknown apps" for your browser when Android asks (only needed once).
3. Open **Gesits MID**, allow Bluetooth (notifications are recommended: the "MID stopped answering"
   warning uses them).
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
