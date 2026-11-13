# Changelog - Gesits MID Android app

Versions are **v\<major\>.\<xx\>.\<yy\>** (see `android/version.properties`):
* **major**: 0 during development, 1 once tested on the bike and stable
* **xx**: feature milestone (new or changed things you notice); resets yy
* **yy**: fixes and polish only

## v0.22.00 - gentler pictures, re-pairing after a drop
The v0.21 logs (11-12 Nov) showed a picture within seconds before every captured freeze or drop - twice the MID
never answered again right after one, and a photo shows the picture half drawn (stripes) on the frozen MID. The
drops ("Broken pipe", at a standstill) left the phone really unpaired (bond state 10, not in the paired list).
* **Pictures show only the range** ("Range only", the new default, set once for everyone; the other pages can
  still be chosen): one large number, the smallest picture, and it changes only when the range does.
* **Pictures far gentler:** at most one a minute (was 10 s), an unchanged one again only after 5 minutes (was
  1), nothing asked for 3 s after a picture (was 1 s) so the MID can draw it, and a picture goes out only
  right after the MID answered (3 answers in a row, the last under 1 s ago).
* **No writes into a MID that just went quiet:** clock, text and pictures need the last 2 questions answered
  (v0.21 sent a picture 3 s after the MID had stopped answering).
* **Re-pairing after a drop:** when the pairing is gone and the MID answered in the last 30 minutes, the app
  asks Android for the pairing itself, once per drop, with a notification - tap "Pair" when stopped.

## v0.21.00 - reconnecting, pictures without overlap, a log that stays
After riding v0.20.01: the MID froze about 3 times per commute (speed and turn signals stuck), even while the app
only asked questions; after a link drop the app kept saying "not paired" although tapping Connect worked at once;
pictures, clock and text were missing for days because of the hidden questions-only state; text overlapped.
* **Reconnecting after a drop:** right after the MID drops the link, Android sometimes reports the bike as not
  paired although it is. For 30 minutes after the MID last answered, the paired way that worked is now tried
  again anyway (no pairing request appears if the pairing exists). Android's answer is written to the log
  ("Pairing: bond state ..., in paired list ..."), and the "pair again" alarm comes once, not on every retry.
* **Questions-only state can't be missed any more:** the notification says "Connected - questions only: open
  the app and confirm the MID works", and Home shows it as an alert (also before connecting).
* **MID pictures without overlapping text:** with pictures on, the text line is no longer used (the range and
  directions are in the picture); only the speed warning uses it, and the next picture wipes it. The range is
  drawn large and green at the top of the Details and Trip pages (it was small grey text).
* **The log stays:** kept in a file (deleted by "Delete all my data"); Copy log copies it with dates. When the
  MID goes silent, the link drops or values freeze, the last 40 Bluetooth messages before it are logged, to
  find what triggers the freezes.
* **Test mode** (Tools → Advanced): only speed, odometer, battery % and errors, one question every 3 s or less,
  nothing written to the MID - to test whether the MID still freezes with almost no traffic.
* Fixes from the review of v0.20.01: directions starting with "At ..." ("At the roundabout ...", "At the fork,
  keep right ...") have their arrow again (H1); the instruction line wins over a street name in the title (L1);
  frozen-values detection works in questions-only mode too (freshness follows the poll period, M1).

## v0.20.01 - fixes from the fifth review
An independent review of v0.20.00 (one QA, one auditor) found no high-severity problems. Fixed now (the owner's
choice; the rest are listed in the review and still open):
* **Hang warning while riding stays at 15 s when one speed answer was lost:** "moving when the answers
  stopped" now allows 5 s (more than two speed questions) instead of 2.5 s, which a single garbled speed
  answer used to break, doubling the delay to 30 s.
* **Frozen values stay frozen across reconnects:** the odometer at the moment of detection is kept; the flag
  clears only when the odometer has moved 0.2 km past it (or you confirm on Home), not because a new link started.
* **Frozen values are no longer shown as live anywhere:** Home, Diagnosis, the speedometer, the widget and the
  notification show "MID values look frozen" and "--"; the Home box explains the frozen case.
* **No false "values frozen" from garbled odometer answers:** only fresh odometer answers (last 15 s) count,
  and the check restarts after silence or a hang.
* **Directions no longer take turn words from the street name:** "Turn right onto Jl. Pasar Kiri" is a right
  turn, "Belok kanan ke Jl. Tiban Raya" no longer reads as "arrive"; whole words only, and the first
  manoeuvre word wins.
* Docs: the README no longer claims all timing ignores phone clock changes.

Still open from that review (not changed): BLE switch-off seen as a hang (#1), switching off while rolling
(#2, needs the key-off timing measured on the bike), and the Low items #8-#20.

## v0.20.00 - safety, fourth review
The third recheck (two QA reviews and an audit of v0.19.00) found these. All fixed:
* **A stuck write is now really caught** (v0.19 lost the "stuck" flag before reading it).
* **No false alarm when you switch off right after pulling up:** "moving" now means the *latest* speed
  answer was above 3 km/h (by when it arrived); a link drop while riding is no longer a hang by itself - it
  becomes one only if the link opens again (so the bike is on) and the MID stays silent for 15 s, or if the
  MID doesn't answer on 3 connections in a row. A bike that was switched off can't open a link.
* **After any hang - and after frozen values - the MID only gets questions** (no text, clock or pictures),
  also across reconnects and app restarts, until you look at the MID and tap "The MID works normally
  again" on Home. (v0.19 resumed writes on a new connection, or after the app's own reconnect pauses.)
* **Frozen but answering** now also blanks the phone speedometer (NO DATA) and blocks writes until confirmed.
* **A clock that can't jump** for all timing (setting the phone's time no longer hides or fakes a hang).
* **Bluetooth LE:** no pictures over a link with small packets (one would keep it busy for 15-25 s); the
  picture interval counts from the end of the write; silence counts from the end of the app's own long writes.
* A lost "-" on even a small battery current needs confirming; charging needs more than 2 A (an idle
  custom pack's -1 A with a lost "-" no longer starts a "charge").
* Text line: what the MID shows is tracked exactly (a blank that went out without its text is retried).
* Change bike also resets the learned battery-current sign. The home-screen widget shows "not connected"
  after Disconnect. The wake lock is held while a new link waits for its first answer. An unexpected error in
  the service stops it cleanly. Several workers alive at once are counted (Delete waits for all).
* Translations (Tools page, charts, picture errors, unknown error bits); protocol notes and comments fixed.
* Pipeline: the published APK's checksum is checked against the build; a newer docs-only commit no longer
  blocks publishing; the release's target commit is updated; emulator-script changes trigger a build.
* 59 unit tests.

**Still true:** not yet tested on the bike for long periods. Bench test first, and measure how long after
switching off the app logs "Connection lost".

## v0.19.00 - safety, third review
The second recheck (two QA reviews and an audit of v0.18.00) found more gaps. All fixed:
* **Writes held back are never counted as sent:** `send()` reports whether a command went out, and "MID
  answering" is checked again right before each write, after the pacing waits. The text line, clock and
  picture are only marked done when they really went out (a dropped text is sent again, instead of leaving
  the MID blank or showing an old "! SLOW DOWN").
* **More hangs are caught:** a write that gets stuck, a link that drops while riding, and a MID that never
  answers on a fresh connection for 30 s (e.g. it froze before the app connected) now count as hangs, with
  a warning. The hang is also kept across app restarts (only a real answer ends it; Change bike and Delete
  all data no longer clear it). The app's own long picture writes (Bluetooth LE) no longer count as silence.
* **Fewer false alarms:** a hang while moving is declared only if the bike was moving when the answers
  stopped *and* the link is still up 15 s later (switching off right after pulling up drops the link first).
* **"Questions only" after a self-recovery** now lasts until the link has been down for 10 s or more (the
  bike switched off and on) - not just any reconnect - and polls at the parked rate meanwhile.
* **Frozen but answering:** speed of 15 km/h or more for 90 s with the odometer not moving gives a warning
  and stops writes.
* **Lighter and steadier:** a text change never forces a picture; own long text rotates every 15 s; the speed
  warning stays at least 10 s; the console sends one question per pass, at most 2 queued, cleared on a hang
  or a new link.
* **Alarms:** one per error code (a new error is reported while another is active); an alarm held back by
  the 10-minute rule fires as soon as the 10 minutes are up if the condition is still there.
* **Answer checks:** a lost "-" on the battery current needs confirming; the current's jump limit is 80 A;
  values expire by the current (riding/parked) schedule and the firmware version no longer expires; the
  current sign learns from each new reading once (20 readings per window); charging is only detected once
  the current sign is known (a custom pack's -1 A idle current no longer looks like charging).
* **Reliability:** Home shows a hang from the hang state itself (not the status text); start-up errors and
  unexpected errors in the worker can't leave the app "running" without a worker; the connect being aborted
  by Disconnect is registered before anything else; "Delete all my data" never deletes while the service could
  still write (it waits up to 60 s, otherwise nothing is deleted) and also forgets a trip kept aside by the demo.
* **Pipeline:** the APK's signing certificate is checked against the CAKRA Labs key's fingerprint before
  anything is published; only the newest `main` commit can publish (re-running an old run can't roll back the
  release); the release tag is moved through the API, with no write token left on disk during the emulator
  run; the cached debug key is gone; manual runs of the private-repo sync only from `main`.
* Docs corrected (NO DATA after 5 s, `AT+TRIP` no longer sent, toolkit's heavy commands marked bench-only).
* 58 unit tests.

**Still true:** not yet tested on the bike for long periods. Bench test first (P, then D on the centre stand),
and measure how long after switching off the app logs "Connection lost" - the hang rules assume under 15-30 s.

## v0.18.00 - safety, second review
A second round of two independent QA reviews and an audit (of v0.17.00) found gaps in v0.17's fixes. All fixed:
* **Hang handling that holds:** a hang is remembered across link drops, reconnects and Disconnect/Connect
  for as long as the app runs; only a real answer from the MID ends it. While hung, one question every
  15 s. The warning comes every time a hang happens (v0.17 warned only once). The wake lock is taken
  again when the MID answers. If the MID comes back by itself on the same connection, only questions are
  sent until the bike is switched off and on.
* **Fewer false alarms:** silence counts as a hang only if you were moving in the last 15 s, or if the
  Bluetooth link is still up after 30 s of silence (switching the ignition off drops it). Stopping and
  switching off in Drive no longer raises "MID stopped answering". A hang while parked (bench test) is
  now reported too.
* **No polling of a MID that doesn't answer:** on a new connection only one question every 3 s goes out
  until the MID answers (v0.17 asked everything at 2 a second for 20 s). Every link drop is followed by a
  growing pause (2 s ... 60 s) that only resets once the MID answers.
* **Lighter traffic:** about 1.6 questions a second riding, 0.9 parked (below the 2-a-second limit, which
  v0.17 was always at). The clock is set only when the minute changes and the text only when it changes
  (no 30 s / 60 s re-sends). Writes are checked against "MID answering" right before each one.
* **Answer checks without loopholes:** a value far from the last one needs the next answer to agree (the
  "three in a row" rule is gone: three garbled answers no longer count); speed and current are checked too
  (a garbled 145 km/h no longer becomes a top speed); the expected width is only fixed after two agreeing
  answers and is relearned after three in a row of a new width (a garbled first answer can no longer lock
  speed or battery % out for the whole session); an error code counts once it shows up twice in the last 4
  answers (scattered garble no longer adds up to a fault); the same alarm comes back at most every 10 min;
  single values that stop coming in are cleared.
* **Text line with pictures on:** if no picture can be made (none chosen, too big), the waiting text goes out
  anyway - the text line, speed warning and directions never stick. Rotating own text works with pictures.
* **Learning:** the battery-current sign needs two agreeing windows to decide and 8 disagreeing windows in a
  row (several minutes) to flip; a top-up charge while connected restarts the km-per-1% measurement.
* **Reliability/privacy:** Disconnect aborts a Bluetooth connect in progress; an old worker can no longer
  close the new one's link; file errors no longer drop the link; "Delete all my data" waits up to 30 s and
  also clears the ride in progress, alarm state, answer history and a parking location still being fixed;
  Change bike starts the answer checks afresh; the demo leaves your real trip as it was; turning Bluetooth on
  for auto-connect can't bring up a pairing request.
* **Console:** only questions about values the MID was seen to answer (no `AT+RESET?` and the like).
* **Pipeline:** manual runs publish only from `main`; only APKs signed with the permanent key are
  published; unit tests run before the key is on the runner; the release is updated in place (no moment
  without a download); the private-repo sync is pinned and read-only.
* More tests (55): width relearning, scattered error garble, disagreeing answers, hang verdicts, known
  questions, alarm repeat, top-up charge, current-sign windows.

**Corrections to v0.17.00's notes:** "a dropped link retries with a growing pause" and "waits until the bike is
switched off and on (or you tap Connect)" were not true; "the first value after connecting needs a second
answer" did not apply to speed and current; "only known commands" let any question name through.

**Still true:** tested here with unit tests and in the emulator, not on the bike for long periods. Test parked
first (in P and, on the centre stand, in D); if the MID ever freezes, stop safely and switch the bike off and on.

## v0.17.00 - safety, reviewed
Two independent QA reviews and an audit of v0.16.00 found that several of its safety promises could be
bypassed (corrected below). Everything they confirmed is fixed:
* **One hard traffic limit:** at most 2 commands a second in total (questions and writes together),
  whatever screen is open. The Speedometer and Diagnosis screens no longer speed up polling
  (v0.16 still went to ~5 a second with them open).
* **Only known commands can leave the phone**, checked where everything is sent: questions, the clock,
  the 20-character text line, pictures up to 4000 characters. The `AT+TRIP` range override is removed
  (v0.16's migration had left it on for anyone who had enabled it). The console only asks questions.
* **Pictures** (still off by default): at most one every 10 s with no exceptions (text changes used to
  force an immediate picture), at most 4000 characters, and the MID is left alone for 1-2 s afterwards.
  Calibration grids switch off by themselves after 2 minutes.
* **When the MID stops answering:** writes stop after 3 s without an answer. After 8 s of silence while
  riding the app sends nothing more, warns you, and waits until the bike is switched off and on (or you
  tap Connect), checking with one question a minute. Parked, it assumes the ignition was switched off
  and retries slowly. A dropped link or a connection error retries with a growing pause (2 s ... 60 s)
  that only resets once the MID answers. A Bluetooth write stuck for 5 s closes the link.
* **No frozen numbers:** live values that stop coming in are cleared; the phone Speedometer shows
  NO DATA when the speed is more than 3 s old.
* **Answers are checked better:** the first value of each key after connecting needs a second agreeing
  answer; real changes (rising temperatures, voltage sag) are followed; a reply with a lost or extra
  character (wrong width) is dropped; an error code that comes and goes is still reported.
* **Learning:** the battery-current sign no longer flips on a long regen descent; a 1-2 % battery
  bounce no longer halves the learned km per 1%.
* **Reliability:** Disconnect then Connect (or Change bike) no longer leaves the app doing nothing;
  an interrupted Bluetooth LE connect is closed; a crash elsewhere in the app no longer drops the link;
  pairing requests only after tapping Connect.
* **Privacy:** "Delete all my data" waits until the service has stopped (it could save a ride again) and
  also clears data in memory; demo mode is refused while connected to a bike and never reaches it.
* **Permissions:** notifications are optional; Android 8-11 ask for location only to scan for a
  Bluetooth LE bike, not for a paired Classic one.
* **Battery:** the service no longer wakes ~30 times a second with pictures off; no wake lock while the
  MID is silent.
* Missing Indonesian texts added (status line, Diagnosis, Trips, alarms, error names).
* **Build pipeline:** only `main` publishes; failed installs or screens, crashes and freezes (ANR) in the
  emulator block publishing; builds queue instead of cancelling a publish half-way; the build job can't
  write releases; third-party steps are pinned; the signing key file is removed after building; release
  notes say plainly that the bike connection is not tested by the build.
* Unit tests for the traffic rules, the command allowlist, silence handling and answer checking.

**Corrections to v0.16.00's notes:** it asked about 2.8 questions a second while riding (≈25 % fewer
than v0.15, not half) and ~5 with the Speedometer open; "at most one picture every 10 s" and "goes quiet
at once" were not true in all cases; the range override could still send.

**Still true:** this has been tested here with unit tests and in the emulator, not on the bike for long
periods. Test parked first; if the MID ever freezes, stop safely and switch the bike off and on.

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
