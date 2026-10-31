"""Command line: python -m gesits <command> ...  (run with -h for help)."""
from __future__ import annotations

import argparse
import sys
import time

from . import mapimage, protocol
from .client import GesitsClient
from .transport import open_transport

SCREENS = {"home": protocol.HOME_KEYS, "battery": protocol.BATTERY_KEYS,
           "diag": protocol.DIAG_KEYS, "all": protocol.ALL_KEYS}


def _connect(args) -> GesitsClient:
    return GesitsClient(open_transport(args.uri), reply_timeout=args.reply_timeout)


def cmd_monitor(args) -> None:
    keys = SCREENS[args.screen]
    with _connect(args) as c:
        next_time = 0.0
        while True:
            if args.sync_time and time.monotonic() >= next_time:
                c.sync_time()
                next_time = time.monotonic() + 1.0
            tel = c.poll(keys)
            rows = [f"{k:<7}{tel.raw.get(k, '-'):>8}   {protocol.KEY_DESCRIPTIONS.get(k, '')}" for k in keys]
            extra = f"mode={tel.drive_mode}  est.range={tel.range_km} km  error={tel.has_error}"
            if args.once:
                print("\n".join(rows + [extra]))
                return
            sys.stdout.write("\x1b[2J\x1b[H" + "\n".join(rows + ["", extra]) + "\n")
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


def cmd_time(args) -> None:
    with _connect(args) as c:
        c.sync_time()
    print("time sent")


def _load_codes(path: str):
    return mapimage.image_to_codes(path)


def cmd_encode(args) -> None:
    codes = _load_codes(args.image)
    payload = mapimage.encode_codes(codes)
    if args.hex:
        with open(args.hex, "w") as f:
            f.write(payload)
    if args.preview:
        mapimage.codes_to_image(codes).save(args.preview)
    print(f"{len(payload)} hex chars" + (f", preview -> {args.preview}" if args.preview else ""))


def cmd_image(args) -> None:
    codes = _load_codes(args.image)
    with _connect(args) as c:
        c.show_map(codes, args.text)
        if args.hold > 0:
            time.sleep(args.hold)
            c.hide_map()
    print("image sent")


def cmd_nav(args) -> None:
    with _connect(args) as c:
        c.send(protocol.nav_text(args.text))
    print("nav text sent")


def cmd_map_off(args) -> None:
    with _connect(args) as c:
        c.hide_map()
    print("map mode off")


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

    sp = add("monitor", cmd_monitor, "poll live data like the app's screens")
    sp.add_argument("--screen", choices=SCREENS, default="all")
    sp.add_argument("--interval", type=float, default=0.2)
    sp.add_argument("--once", action="store_true", help="print one pass and exit")
    sp.add_argument("--sync-time", action="store_true", help="also send AT+TIME every second")

    sp = add("raw", cmd_raw, "send any command and print what comes back")
    sp.add_argument("command", help='e.g. "AT+SPD?"')
    sp.add_argument("--wait", type=float, default=2.0)
    sp.add_argument("--no-crlf", action="store_true")

    add("time", cmd_time, "set the MID clock to this computer's time")

    sp = add("nav", cmd_nav, "send a navigation text line (AT+NAV)")
    sp.add_argument("text")

    sp = add("image", cmd_image, "show a picture on the MID map screen")
    sp.add_argument("image")
    sp.add_argument("--text", default="", help="text line sent with the picture")
    sp.add_argument("--hold", type=float, default=0, help="seconds to show, then turn map mode off")

    add("map-off", cmd_map_off, "leave map mode (AT+MAPON=\"0\", AT+MAPEND)")

    sp = add("encode", cmd_encode, "convert a picture offline and preview it", uri=False)
    sp.add_argument("image")
    sp.add_argument("--preview", help="write a PNG preview of what the MID gets")
    sp.add_argument("--hex", help="write the AT+MAPS hex payload to a file")

    sp = add("sim", cmd_sim, "run a fake bike on TCP for testing", uri=False)
    sp.add_argument("--host", default="127.0.0.1")
    sp.add_argument("--port", type=int, default=3333)
    sp.add_argument("--preview-dir", help="save every received map frame as PNG here")

    args = p.parse_args(argv)
    try:
        args.fn(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
