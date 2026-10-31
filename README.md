# GESITS display

Tools for talking to the **Gesits G1** electric motorcycle's MID (dashboard) over Bluetooth:
read live data, set the clock, and put your own text and pictures on the stock MID screen.

* **Protocol write-up:** [docs/PROTOCOL.md](docs/PROTOCOL.md). It is Bluetooth Classic SPP with
  plain-text `AT+...` commands, recovered from the Gesits Android app.
* **Python toolkit:** `gesits/`. Runs on a laptop or a Raspberry Pi, so a Pi with a screen can
  serve as a replacement display.
* **Simulator:** a fake bike, so you can develop without the motorcycle.

> The protocol comes from the app's code and has **not yet been checked on a real bike**.
> Test while parked with the stand down. Don't send map/image commands while riding: map mode
> may replace the speedometer.

## Quick start

Needs Python 3.9+. Pillow is required for pictures (`pip install pillow`) and pyserial for
`serial://` (`pip install pyserial`).

```bash
# 1. Pair the bike: ignition ON, press the Bluetooth button on the left handlebar,
#    then pair from your computer. Note its MAC address.

# 2. Live data (Linux / Raspberry Pi / Windows):
python -m gesits monitor bt://AA:BB:CC:DD:EE:FF            # channel 1 by default
python -m gesits monitor bt://AA:BB:CC:DD:EE:FF/2 --screen diag
python -m gesits monitor serial://COM5                     # Windows Bluetooth COM port
python -m gesits monitor serial:///dev/rfcomm0             # after `sudo rfcomm bind 0 <MAC> 1`

# 3. Write to the MID
python -m gesits time  bt://AA:BB:CC:DD:EE:FF              # set the MID clock
python -m gesits image bt://AA:BB:CC:DD:EE:FF logo.png --text "Hello" --hold 10
python -m gesits map-off bt://AA:BB:CC:DD:EE:FF

# 4. Explore: send any command, see the raw reply
python -m gesits raw bt://AA:BB:CC:DD:EE:FF "AT+SPD?"

# Offline: see how a picture will look at 164x88 / 4 colours
python -m gesits encode logo.png --preview preview.png
```

### Without the bike

```bash
python -m gesits sim --preview-dir frames/      # terminal 1
python -m gesits monitor tcp://127.0.0.1:3333   # terminal 2
python -m gesits image tcp://127.0.0.1:3333 logo.png   # frame saved to frames/
```

### Tests

```bash
python -m unittest -v
```

## Drawing for the MID

The map area is **164 × 88 pixels** with **4 colours**: black, white, blue (B ≥ 210) and red
(R ≥ 210). Use big shapes and pure colours. Small text and anti-aliased edges are lost when
the picture is scaled down.

## Roadmap

1. ✅ Protocol recovered from the app, plus Python toolkit and simulator
2. ⏳ Confirm on the real bike (channel, units, error codes); fill in `docs/PROTOCOL.md`
3. Android dashboard app (phone or tablet as the MID)
4. ESP32 + TFT replacement display (ESP32 classic, since it needs Bluetooth Classic SPP)
5. Stock MID firmware / wiring work (needs hands-on hardware access)
