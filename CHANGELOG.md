# Changelog - Gesits MID Android app

Versions are **v\<major\>.\<xx\>.\<yy\>** (see `android/version.properties`):
* **major**: 0 during development, 1 once tested on the bike and stable
* **xx**: feature milestone (new or changed things you notice); resets yy
* **yy**: fixes and polish only

## v0.12.01
Translation fixes found in the emulator screenshots (picture status, ETA on the MID, weekly target,
chart labels, MID version) and clearer notification-access status.

## v0.12.00
* **Battery health and cycles** (Trips tab): equivalent full cycles and charging sessions counted
  from the day the app is installed, usable capacity worked out from your rides, and a battery-health
  (SOH) trend. The app also asks the MID for a lifetime cycle count; if your bike reports one, it is shown too.
* **Charging card** (Home): shown while charging, with % per hour, time to 80/90/100 % and a
  notification when your target is reached.
* **Turn-by-turn directions on the MID**: while Google Maps is navigating, the MID picture shows the
  turn arrow, distance, street and arrival time, and the text line shows the next turn. Needs
  notification access (only Google Maps' notifications are read; nothing leaves the phone).
* **Speed warning**: optional limit (50/60/70/80 km/h); after 3 s over it the MID text line warns you.
* **Bahasa Indonesia**: the whole app, notifications and the MID picture (Tools → Language).
* **Home-screen widget**: battery % and range at a glance.
* **MID accepted**: shows which writes (picture, clock, text, range) the MID confirmed; a picture
  that wasn't confirmed is re-sent.
* Build check: every screen is opened in an Android emulator, and the APK is published only if
  nothing crashed.

## v0.11.00
Auto-connect: the app connects to the saved bike as soon as it is opened (turns Bluetooth on via
Android's prompt if needed). Switch it off in Tools → Keep connected.

## v0.10.00
CAKRA Labs name and logo (top bar and Tools → About). New UI with Home / MID / Trips / Tools tabs, guided setup, cards and switches. Security fixes:
demo mode can't be triggered by other apps, parking coordinates aren't logged, "Delete all my data".

## Earlier milestones (before version numbers were introduced)
| Version | Milestone |
|---|---|
| v0.09 | Battery and RAM efficiency: event-driven loop, tiered polling, send pictures only on change, auto-stop |
| v0.08 | Stats and ride history, savings vs petrol, diagnosis charts, alarms, phone speedometer, maps hand-off |
| v0.07 | Large calibration grid |
| v0.06 | Dashboard pages (details / trip / big) and trip tracking |
| v0.05 | 320×128 MID picture size, MID firmware version, date on the text line |
| v0.04 | Console and read-only probe |
| v0.03 | Position margins, calibration grid, background reliability |
| v0.02 | Adaptive range, clock sync |
| v0.01 | First app: connect (Classic/BLE), live data, dashboard on the MID |
