# Changelog - Gesits MID Android app

Versions are **v\<major\>.\<xx\>.\<yy\>** (see `android/version.properties`):
* **major**: 0 during development, 1 once tested on the bike and stable
* **xx**: feature milestone (new or changed things you notice); resets yy
* **yy**: fixes and polish only

## v0.13.01
"Sent to the MID" card (was "MID accepted"): this G1's MID answers questions but doesn't confirm
writes, so the card now shows when each item was last sent instead of "✗ no reply".

## v0.13.00
**Fully adaptive range, from energy:** range = battery % × battery capacity ÷ your consumption.
* Consumption (Wh/km) is measured from voltage × current over your last 15 km, so it follows your
  riding style, mode and load within the first kilometre.
* Battery capacity is learned from energy against battery %, while riding and while charging, so a
  bigger (custom) battery is recognised by itself, typically after one charge or ~15% of riding.
* Nothing to enter: the "Full-battery range" setting from v0.12.05 is gone. Starts at the original
  battery's ~50 km and adapts from there. The range is sent to the MID from the start.
* MID tab → Range shows what has been learned: battery kWh, Wh/km and the range at 100%.
* Less flicker between our range and the MID's own (original-battery) figure: our range is sent every
  0.25 s, right after every other command the app sends (anything may make the MID redraw), and once
  more after each picture has been drawn.

## v0.12.05
For bikes with a bigger (custom) battery, where the MID's own range assumes the original one:
* **Full-battery range** (MID tab → Range): enter how far you get on a full battery. The app sends
  that range to the MID right away, and keeps refining it from your rides.
* **Range test** (40 s, while stopped): sends 123 km once, then sends nothing for 20 s, then only reads
  values for 20 s. When the MID goes back to its own number shows what makes it do that.

## v0.12.04
Range on the MID:
* The starting guess is now 0.5 km per 1% (about 50 km on a full G1 battery). It was 1.0, which
  showed 100 km against the MID's 50.
* The app's range is sent to the MID only once it has learned from about 20% of battery ridden;
  until then the MID keeps its own figure, instead of the two switching back and forth.
* Once learned it is re-sent every 0.25 s and once more 0.4 s after each picture, because the MID keeps
  recalculating its own range. The MID tab explains which range the MID is showing.

## v0.12.03
Range re-sent every 0.5 s and after each picture (superseded by v0.12.04).

## v0.12.02
Background connection fixes:
* The notification shows the real state (Connected · battery · range, or "Not connected - retrying")
  instead of always saying connected, and has a Disconnect button.
* A Bluetooth Classic link that drops silently is now noticed and reconnected (before, Android still
  reported it as open, so the app never retried).
* Watchdog: if the MID doesn't answer for 15 s, the app reconnects.
* The log now records when the app goes to the background / comes back, and when Android restarted
  the service, so a drop can be traced.

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
