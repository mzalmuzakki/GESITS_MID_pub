# Changelog - Gesits MID Android app

Versions are **v\<major\>.\<xx\>.\<yy\>** (see `android/version.properties`):
* **major**: 0 during development, 1 once tested on the bike and stable
* **xx**: feature milestone (new or changed things you notice); resets yy
* **yy**: fixes and polish only

## v0.16.00 - safety
On a real G1 (MID 11-50-09-G1) the MID froze after about 5 minutes of riding with v0.15: speed and turn
signals stuck until the ignition was switched off and on, and the app kept reconnecting to the hung MID.
The MID is fragile under load (GESITS Dashboard's authors saw the same), so:
* **MID pictures are off by default** (switched off once for everyone). They are by far the heaviest
  load (7000 characters each). If switched on: at most one every 10 s. The range stays in the text line.
* **If the MID stops answering, the app goes quiet at once**, warns you ("stop and switch the bike off
  and on") and waits 2 minutes, instead of reconnecting every minute.
* After connecting, nothing but questions is sent until the MID has answered.
* On connect only the version is asked: no more guessed names (cycle count) or `AT+ARROW`.
* About half as many questions per second (≈2 while riding).
* The console's Probe button (52 unknown questions at once) is gone; `AT+RANGE=` is blocked there too.

## v0.15.01
* The km-per-1% range starts from what the app already learned about the bike (battery size and
  consumption) instead of the original battery's 0.5, so updating doesn't drop the range back to ~50 km.
  Seen on a G1 with a custom battery: 28.5 km at 16 Wh/km, 1.50 km per 1%, 111 km at 80%.
* Numbers without the MID's zero padding: 80% instead of 080%, 39 °C instead of 0039.

## v0.15.00
* **Range = battery % × km per 1% from your own riding** (odometer and battery % only), as before v0.13,
  but settling after ~10% of battery instead of 20% (last 40% of riding counts). The energy figures
  (battery kWh, Wh/km) stay on the Range card for information; they depend on the battery current
  reading, which custom batteries may report differently.
* **No more overlapping text on the MID** ("range 5/46 km"): the MID draws the text line over the
  picture without erasing, so the text now changes rarely, and each change is sent right after a fresh
  picture. The range line no longer alternates with the date; directions show the turn and street
  (the distance stays in the picture); the speed warning is a fixed "! SLOW DOWN 70".

## v0.14.03
**Battery current sign is learned per bike.** On a G1 with a custom battery the current drawn is
reported negative (rides saved as "-44 Wh", "charging started" while riding, -81 W while parked).
The app now learns the sign during about 40 s of riding and corrects it, so the energy-based range,
Wh/km, power, regen and charging detection work with such batteries too.

## v0.14.02
From a real ride log (the MID dropped characters: "AT+VDO=003791.6", "AT+TINV003796.0"):
* **One question at a time:** the app waits for each answer (max 0.3 s), keeps 80 ms between commands
  (as GESITS Dashboard does) and leaves the MID alone for 0.6 s after each picture while it draws it.
* **Garbled answers are ignored:** every value must match the MID's exact format and a plausible range,
  and big jumps (battery %, error codes, odometer...) count only when the next answer agrees. This
  stops false alarms ("Under voltage", "Motor fault", "Battery low 2%"), false "charging" and a broken
  learned range. Range learned from such jumps before is dropped.
* **Faster reconnects:** the connection method that worked (paired / unpaired) is remembered and tried
  first, instead of waiting ~30 s for a method this MID refuses.

## v0.14.01
Bluetooth Classic reconnects:
* Connects without pairing first (insecure RFCOMM), so the link survives the MID forgetting this phone,
  for example after a laptop paired with it. Falls back to the usual paired connection.
* Background reconnects never pop up a pairing request any more. If the bike isn't paired, the app
  says so once ("pair the bike again") and waits; tapping Connect may show one request.

## v0.14.00
Tested on a real G1 (MID `11-50-09-G1`) with the laptop toolkit: the MID's own range figure is its own
calculation for the original battery (`AT+RANGE?` = 50.0 at 100%). `AT+TRIP` is overwritten instantly
and writing `AT+RANGE` made the MID stop answering. So:
* **The adaptive range goes in the MID's text line** ("RANGE 112 km", alternating with the date),
  which the app fully controls. Your own text still takes priority.
* The range card shows the MID's own figure for comparison.
* Replacing the MID's range figure (`AT+TRIP`) is now an option, off by default (it only flickers on a G1).
* The range test is removed (it answered its question).
* Laptop toolkit: `set` refuses `RANGE`.

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
