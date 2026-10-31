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
open() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity --ez demo true ${1:+--es open $1} $2; }
adb logcat -c

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
open mid "--ez nav true";            shot 13-mid-directions 5
open "" "--es lang id";              shot 14-home-indonesia 5
open mid "--es lang id --ez nav true"; shot 15-mid-indonesia 5
open trips "--es lang id";           shot 16-trips-indonesia 5

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

# Fail the job (and so block publishing) if the app crashed anywhere above.
adb logcat -d > shots/logcat.txt 2>&1
if grep -q "FATAL EXCEPTION" shots/logcat.txt; then
  echo "APP CRASHED:"; grep -A 25 "FATAL EXCEPTION" shots/logcat.txt
  exit 1
fi
rm -f shots/logcat.txt
echo "No crashes."
