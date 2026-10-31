#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the APK, opens each screen with demo data,
# saves screenshots and memory use to ./shots.
set -x
APK=$(ls apk/*.apk | head -1)
PKG=io.github.mzalmuzakki.gesitsmid
adb install -r "$APK"
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done
mkdir -p shots
# Skip Android's one-time "Viewing full screen" tip (a real phone shows it once)
adb shell settings put secure immersive_mode_confirmations confirmed
shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }
swipe() { adb shell input swipe 540 1900 540 500 400; }
open() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity --ez demo true ${1:+--es open $1}; }

open "";          shot 01-home 5
swipe;            shot 02-home-2 2
open mid;         shot 03-mid 5
swipe;            shot 04-mid-2 2
open trips;       shot 05-trips 5
swipe;            shot 06-trips-2 2
swipe; swipe;     shot 07-trips-3 2
open tools;       shot 08-tools 5
swipe;            shot 09-tools-2 2
open diagnosis;   shot 10-diagnosis 5
swipe;            shot 11-diagnosis-2 2
open speedo;      shot 12-speedometer 6

{
  echo "== Foreground (Speedometer open) =="
  adb shell dumpsys meminfo $PKG | sed -n '/App Summary/,/TOTAL SWAP/p'
  adb shell input keyevent HOME; sleep 8
  echo "== Background (after HOME) =="
  adb shell dumpsys meminfo $PKG | sed -n '/App Summary/,/TOTAL SWAP/p'
  echo "== APK size =="; ls -l "$APK"
} > shots/memory.txt 2>&1
cat shots/memory.txt
adb shell am force-stop $PKG
