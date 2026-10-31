# Changelog - Gesits MID Android app

Versions are **v\<major\>.\<xx\>.\<yy\>** (see `android/version.properties`):
* **major**: 0 during development, 1 once tested on the bike and stable
* **xx**: feature milestone (new or changed things you notice); resets yy
* **yy**: fixes and polish only

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
