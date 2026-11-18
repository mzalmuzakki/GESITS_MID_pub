# Gesits MID - Android app (v0.23 and later)

Rewritten from the beginning in v0.23.00: one screen, few rules, everything logged. The goal is to find out
on the bike what works and what doesn't. The most important thing is the **range**.

## What it does
* Connects to the Gesits G1 MID over Bluetooth Classic (SPP). Tries the way that worked last time first, then
  insecure, secure (paired) and RFCOMM channel 1. Each try is logged with its time and error.
* Asks the MID for values (all 13, only speed/odometer/battery %, or none) with a pause you choose between commands.
* **Range** = battery % x km per 1 %, learned from the odometer and battery % (last 40 % of battery ridden).
  The learned range from older versions is kept. Shown large in the app and the notification.
* On the MID: the range as a **picture** (one big number, at most once a minute by default, again when the
  number changes and every 5 minutes), and/or in the **text line** ("RANGE 113 km"), and the **clock**.
  Each can be switched off.
* If the MID doesn't answer for 15 s (settable, 0 = never), the app closes the link and connects again.
* If the MID asks to pair, the app can try to accept it by itself (PIN 1234 by default, settable).
  "Pair bike" starts pairing from the app.
* Console: send any command (writing the odometer is refused).

## Log
Everything is logged: each command sent (TX), each line received (RX, control characters shown as \r \n
\xNN), connection tries, Bluetooth events from Android (link up/down, pairing state and pairing requests, phone
Bluetooth on/off), settings changes, app opened/closed, and a STATUS line every minute.
The log is a file in the app's storage (about 10 MB at most) and survives closing the app.
"Copy events" copies only the events (no traffic), "Copy all" the last 3000 lines, "Save full log to
Downloads" writes everything to Download/GesitsMID/.

## Not in v0.23
Rides, trips, charts, parking, alarms, directions, speed warning, widget, speedometer screen, Indonesian
texts, and the safety rules of v0.16-v0.22 (traffic limits, hang detection, questions-only lock).
