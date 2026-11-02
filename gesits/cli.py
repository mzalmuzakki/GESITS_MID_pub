"""Command line: python -m gesits <command> ...  (run with -h for help)."""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import mapimage, protocol
from .client import GesitsClient
from .transport import open_transport

SCREENS = {"home": protocol.HOME_KEYS, "battery": protocol.BATTERY_KEYS,
           "diag": protocol.DIAG_KEYS, "all": protocol.ALL_KEYS}


def _connect(args) -> GesitsClient:
    return GesitsClient(open_transport(args.uri), reply_timeout=args.reply_timeout)


def _summary(tel) -> str:
    errs = ", ".join(tel.errors) or "none"
    return (f"mode={tel.drive_mode}  gear={tel.park_state}  power={tel.power_w} W  "
            f"errors={errs if tel.error_mask is not None else '-'}")


def cmd_monitor(args) -> None:
    keys = SCREENS[args.screen]
    with _connect(args) as c:
        next_time = 0.0
        while True:
            if args.sync_time and time.monotonic() >= next_time:
                c.sync_time()
                next_time = time.monotonic() + 1.0
            tel = c.poll(keys)
            rows = [f"{k:<6}{tel.raw.get(k, '-'):>9}   {protocol.KEY_DESCRIPTIONS.get(k, '')}" for k in keys]
            if args.once:
                print("\n".join(rows + [_summary(tel)]))
                return
            sys.stdout.write("\x1b[2J\x1b[H" + "\n".join(rows + ["", _summary(tel)]) + "\n")
            sys.stdout.flush()
            time.sleep(args.interval)


def cmd_raw(args) -> None:
    with _connect(args) as c:
        data = args.command.encode() + (b"" if args.no_crlf else b"\r\n")
        c.send(data)
        print(f">> {data!r}")
        end = time.monotonic() + args.wait
        while time.monotonic() < end:
            raw = c.t.read(0.2)
            if raw:
                print(f"<< {raw!r}")


def _watch(c, keys, seconds: float, interval: float = 1.0) -> None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        t0 = time.monotonic()
        vals = [f"{k}={c.request(k) or '(no reply)'}" for k in keys]
        print(time.strftime("%H:%M:%S"), "  ".join(vals), flush=True)
        time.sleep(max(0.0, interval - (time.monotonic() - t0)))


def cmd_watch(args) -> None:
    """Read some keys once a second (read-only)."""
    with _connect(args) as c:
        _watch(c, [k.upper() for k in args.keys], args.seconds)


# Never written: the odometer; RANGE (on a G1, AT+RANGE="..." made the MID stop answering until restarted).
BLOCKED_SET_KEYS = {"ODO", "RANGE"}


def cmd_set(args) -> None:
    """Write AT+KEY="value" once, then read the key back for a while to see whether the MID keeps it."""
    key = args.key.upper()
    if key in BLOCKED_SET_KEYS:
        raise SystemExit(f"Refusing to write {key} (odometer, or known to upset the MID).")
    with _connect(args) as c:
        before = c.request(key) or c.request(key)   # the first question after connecting is sometimes missed
        print(f"{key} before: {before or '(no reply)'}")
        if before is not None:
            print(f"To undo: python -m gesits set {args.uri} {key} {before}")
        cmd = f'AT+{key}="{args.value}"\r\n'.encode("ascii")
        c.send(cmd)
        print(f">> {cmd!r}")
        end = time.monotonic() + 1.0
        while time.monotonic() < end:                      # anything the MID says right after the write
            raw = c.t.read(0.2)
            if raw:
                print(f"<< {raw!r}")
        _watch(c, [key] + [k.upper() for k in args.also], args.seconds)


def cmd_time(args) -> None:
    with _connect(args) as c:
        c.sync_time()
    print("time sent")


def cmd_text(args) -> None:
    parts = protocol.split_nav_text(args.text)
    if not parts:
        raise SystemExit("nothing to send")
    with _connect(args) as c:
        for _ in range(max(1, args.repeat)):
            for part in parts:
                c.send(protocol.nav_text(part))
                print(f"sent: {part!r}")
                if len(parts) > 1 or args.repeat > 1:
                    time.sleep(args.rotate)


def cmd_range(args) -> None:
    with _connect(args) as c:
        c.show_range(args.km)
    print("range sent")


def _jpeg(args):
    b64, q, img = mapimage.jpeg_to_base64(args.image, args.width, args.height, args.max_chars)
    if getattr(args, "preview", None):
        img.save(args.preview, "JPEG", quality=q)
    print(f"{img.size[0]}x{img.size[1]} JPEG, quality {q}, {len(b64)} base64 chars")
    return b64


def cmd_image(args) -> None:
    if args.legacy:
        codes = mapimage.image_to_codes(args.image)
        with _connect(args) as c:
            c.show_map(codes, args.text)
            if args.hold > 0:
                time.sleep(args.hold)
                c.hide_map()
    else:
        b64 = _jpeg(args)
        with _connect(args) as c:
            if args.text:
                c.show_text(args.text)
            c.show_jpeg(b64)
    print("image sent")


def cmd_encode(args) -> None:
    if args.legacy:
        codes = mapimage.image_to_codes(args.image)
        payload = mapimage.encode_codes(codes)
        if args.preview:
            mapimage.codes_to_image(codes).save(args.preview)
        print(f"legacy bitmap: {len(payload)} hex chars")
    else:
        payload = _jpeg(args)
    if args.out:
        with open(args.out, "w") as f:
            f.write(payload)


SCREEN_KEYS = ["SOC", "MODE", "PARK", "VBAT", "IBAT", "ODO", "TBAT", "TMOT", "TINV", "ERR"]


def cmd_screen(args) -> None:
    from . import screen

    if args.uri is None:  # offline preview with sample data
        img = screen.render(screen.sample_telemetry())
        b64, q, _ = mapimage.jpeg_to_base64(img, max_chars=args.max_chars)
        img.save(args.preview or "screen_preview.png")
        print(f"preview -> {args.preview or 'screen_preview.png'} ({len(b64)} base64 chars at quality {q})")
        return
    from .rangeest import RangeEstimator

    est = RangeEstimator(prior_km_per_percent=args.km_per_percent)
    est.load(args.range_file)
    est.prior = args.km_per_percent
    with _connect(args) as c:
        last_minute = None
        while True:
            t0 = time.monotonic()
            now = time.localtime()
            if not args.no_clock and (now.tm_hour, now.tm_min) != last_minute:
                c.sync_time()                       # on connect and whenever the minute changes
                last_minute = (now.tm_hour, now.tm_min)
            tel = c.poll(SCREEN_KEYS)
            odo, soc = tel.get_float("ODO"), tel.get_float("SOC")
            if odo is not None and soc is not None and est.update(odo, soc):
                print("range learned:", est.describe(), flush=True)
            est.save(args.range_file)
            range_km = est.range_km(soc) if soc is not None else None
            img = screen.render(tel, range_km=range_km)
            b64, q, _ = mapimage.jpeg_to_base64(img, max_chars=args.max_chars)
            c.show_jpeg(b64)
            if range_km is not None:
                c.show_range(range_km)
            if args.text:
                c.show_text(args.text)
            if args.preview:
                img.save(args.preview)
            print(f"frame sent: {len(b64)} chars, q{q}, SOC={tel.raw.get('SOC')} P={tel.power_w} W", flush=True)
            if args.once:
                return
            time.sleep(max(0.0, args.interval - (time.monotonic() - t0)))


PROBE_KEYS = ["VER", "SOC2", "NUMBAT", "FWD", "CBAT", "TRIP", "TIME", "NAV", "ARROW", "MAPON", "RPM", "PWR",
              "SN", "ID", "NAME", "MODEL", "VIN", "DATE", "BT", "MAC", "LIGHT", "BRIGHT", "THEME", "LANG",
              "UNIT", "MENU", "PAGE", "SCREEN", "LAYOUT", "LOGO", "RANGE", "AVG", "KM", "TEMP", "CAP", "CYCLE",
              "CHG", "HELP", "LIST", "ALL"]


def cmd_selftest(args) -> None:
    """Read-only check of the connection and every known/guessed query. Writes a report file."""
    lines = []

    def out(msg=""):
        print(msg, flush=True)
        lines.append(msg)

    out(f"gesits selftest  {time.strftime('%Y-%m-%d %H:%M:%S')}  uri={args.uri}")
    t0 = time.monotonic()
    with _connect(args) as c:
        out(f"connected in {time.monotonic() - t0:.1f} s")
        out("\n== Known values ==")
        for key in ["VER"] + protocol.ALL_KEYS:
            t1 = time.monotonic()
            v = c.request(key)
            ms = (time.monotonic() - t1) * 1000
            out(f"{key:<7}{(v if v is not None else '(no reply)'):>12}  {ms:5.0f} ms   {protocol.KEY_DESCRIPTIONS.get(key, '')}")
        if args.probe:
            out("\n== Probe (read-only AT+NAME? queries; anything that answers is interesting) ==")
        for key in (PROBE_KEYS if args.probe else []):
            c.send(protocol.query(key))
            end = time.monotonic() + args.reply_timeout
            got = []
            while time.monotonic() < end:
                raw = c.t.read(0.1)
                if raw:
                    got.append(raw.decode("ascii", "replace").strip())
            out(f"{key:<7} -> {' | '.join(g for g in got if g) or '(nothing)'}")
        out("\n== Summary ==")
        tel = c.telemetry
        out(f"mode={tel.drive_mode} gear={tel.park_state} power={tel.power_w} W errors={tel.errors or 'none'}")
    report = Path(args.report)
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nReport saved to {report.resolve()} - send this file.")


def cmd_map_off(args) -> None:
    with _connect(args) as c:
        c.hide_map()
    print("map mode off (legacy commands)")


def cmd_sim(args) -> None:
    from .simulator import serve
    serve(args.host, args.port, args.preview_dir)


def main(argv=None) -> None:
    p = argparse.ArgumentParser(prog="gesits", description="Gesits G1 MID toolkit")
    sub = p.add_subparsers(dest="cmd", required=True)

    def add(name, fn, help_, uri=True):
        sp = sub.add_parser(name, help=help_)
        if uri:
            sp.add_argument("uri", help="bt://AA:BB:CC:DD:EE:FF[/ch] | serial:///dev/rfcomm0 | serial://COM5 | tcp://host:port")
            sp.add_argument("--reply-timeout", type=float, default=0.5)
        sp.set_defaults(fn=fn)
        return sp

    def picture_opts(sp):
        sp.add_argument("image")
        sp.add_argument("--width", type=int, default=mapimage.JPEG_W)
        sp.add_argument("--height", type=int, default=mapimage.JPEG_H)
        sp.add_argument("--max-chars", type=int, default=mapimage.MAPS_MAX_CHARS)
        sp.add_argument("--legacy", action="store_true", help="old app's 164x88 4-colour hex format")

    sp = add("monitor", cmd_monitor, "show live data")
    sp.add_argument("--screen", choices=SCREENS, default="all")
    sp.add_argument("--interval", type=float, default=0.2)
    sp.add_argument("--once", action="store_true", help="print one pass and exit")
    sp.add_argument("--sync-time", action="store_true", help="also send AT+TIME every second")

    sp = add("raw", cmd_raw, "send any command and print what comes back")
    sp.add_argument("command", help='e.g. "AT+SPD?"')
    sp.add_argument("--wait", type=float, default=2.0)
    sp.add_argument("--no-crlf", action="store_true")

    add("time", cmd_time, "set the MID clock to this computer's time")

    sp = add("text", cmd_text, "show text on the MID (AT+NAV, 20 chars per part)")
    sp.add_argument("text")
    sp.add_argument("--rotate", type=float, default=3.0, help="seconds per part")
    sp.add_argument("--repeat", type=int, default=1)

    sp = add("watch", cmd_watch, "read some values once a second, e.g. watch serial://COM8 RANGE SOC (read-only)")
    sp.add_argument("keys", nargs="+")
    sp.add_argument("--seconds", type=float, default=30)

    sp = add("set", cmd_set, 'write AT+KEY="value" once, then read KEY back to see if the MID keeps it')
    sp.add_argument("key")
    sp.add_argument("value")
    sp.add_argument("--also", nargs="*", default=[], help="other keys to read alongside")
    sp.add_argument("--seconds", type=float, default=20)

    sp = add("range", cmd_range, "set the range figure on the MID (AT+TRIP)")
    sp.add_argument("km", type=float)

    sp = add("image", cmd_image, "show a picture in the MID map area (AT+MAPS)")
    picture_opts(sp)
    sp.add_argument("--text", default="", help="also show this text (AT+NAV)")
    sp.add_argument("--preview", help="save the exact JPEG that is sent")
    sp.add_argument("--hold", type=float, default=0, help="legacy only: seconds, then map off")

    sp = add("screen", cmd_screen, "custom MID screen: live data drawn as a picture", uri=False)
    sp.add_argument("uri", nargs="?", help="omit for an offline preview with sample data")
    sp.add_argument("--reply-timeout", type=float, default=0.5)
    sp.add_argument("--interval", type=float, default=3.0, help="seconds between frames")
    sp.add_argument("--max-chars", type=int, default=mapimage.MAPS_MAX_CHARS)
    sp.add_argument("--text", default="", help="also show this text line (AT+NAV)")
    sp.add_argument("--preview", help="save the last rendered frame here")
    sp.add_argument("--once", action="store_true")
    sp.add_argument("--km-per-percent", type=float, default=1.0, help="starting value for adaptive range")
    sp.add_argument("--range-file", default=str(Path.home() / ".gesits_range.json"),
                    help="where the learned range is kept")
    sp.add_argument("--no-clock", action="store_true", help="don't set the MID clock")

    sp = add("selftest", cmd_selftest, "read-only check of the bike connection; writes a report")
    sp.add_argument("--report", default="gesits_report.txt")
    sp.add_argument("--probe", action="store_true",
                    help="also ask ~40 unknown names (read-only, but extra traffic for a fragile MID)")

    add("map-off", cmd_map_off, "legacy: AT+MAPON=\"0\" + AT+MAPEND")

    sp = add("encode", cmd_encode, "convert a picture offline (no bike needed)", uri=False)
    picture_opts(sp)
    sp.add_argument("--preview", help="write a preview of what the MID gets")
    sp.add_argument("--out", help="write the AT+MAPS payload to a file")

    sp = add("sim", cmd_sim, "run a fake bike on TCP for testing", uri=False)
    sp.add_argument("--host", default="127.0.0.1")
    sp.add_argument("--port", type=int, default=3333)
    sp.add_argument("--preview-dir", help="save every received picture here")

    args = p.parse_args(argv)
    try:
        args.fn(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
