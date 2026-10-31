import time
import unittest

from gesits import mapimage, screen
from gesits.ble import split_for_ble


class BlePlan(unittest.TestCase):
    def test_maps_is_sent_in_pieces(self):
        b64 = "A" * 650
        cmd = f'AT+MAPS="{b64}"\r\n'.encode()
        plan = split_for_ble(cmd, piece=100)
        self.assertEqual(plan[0], (b'AT+MAPS="', 0.05))
        self.assertEqual(plan[-1], (b'"\r\n', 0.0))
        body = plan[1:-1]
        self.assertEqual(len(body), 7)
        self.assertTrue(all(len(c) <= 100 and pause == 0.03 for c, pause in body))
        self.assertEqual(b"".join(c for c, _ in plan), cmd)

    def test_short_commands_respect_mtu(self):
        cmd = b'AT+NAV="Hello               "\r\n'
        plan = split_for_ble(cmd, piece=20)
        self.assertTrue(all(len(c) <= 20 for c, _ in plan))
        self.assertEqual(b"".join(c for c, _ in plan), cmd)


class Screen(unittest.TestCase):
    def test_render_fits_mid(self):
        tel = screen.sample_telemetry()
        for extra in ([], [("ERR", "0800"), ("IBAT", "-12"), ("SOC", "15")]):
            tel.update(extra)
            img = screen.render(tel, time.strptime("09:41", "%H:%M"))
            self.assertEqual(img.size, (350, 220))
            b64, q, _ = mapimage.jpeg_to_base64(img)
            self.assertLessEqual(len(b64), 7000)

    def test_render_with_no_data(self):
        img = screen.render(screen.Telemetry())
        self.assertEqual(img.size, (350, 220))


if __name__ == "__main__":
    unittest.main()


class FakeBleakTransport(unittest.TestCase):
    """Run BleTransport against an in-memory fake of the bleak API (a pretend MID)."""

    def setUp(self):
        import sys
        import types

        test = self
        test.writes = []

        class Char:
            uuid = "0000FFF2-0000-1000-8000-00805F9B34FB"
            properties = ["read", "write", "notify"]

        class Service:
            uuid = "0000fff1-0000-1000-8000-00805f9b34fb"
            characteristics = [Char()]

        class Client:
            mtu_size = 247

            def __init__(self, device):
                self.services = [Service()]
                self.buf = b""

            async def connect(self):
                return True

            async def disconnect(self):
                return True

            async def start_notify(self, char, cb):
                self.cb = cb

            async def write_gatt_char(self, char, data, response=None):
                test.writes.append((bytes(data), response))
                self.buf += bytes(data)
                while b"\r\n" in self.buf:
                    line, self.buf = self.buf.split(b"\r\n", 1)
                    if line == b"AT+SOC?":
                        self.cb(char, bytearray(b"SOC=7"))      # reply split over two notifications
                        self.cb(char, bytearray(b"7\r\n"))

        class Scanner:
            @staticmethod
            async def find_device_by_name(name, timeout=10):
                return object() if name == "GESITS" else None

            @staticmethod
            async def find_device_by_address(addr, timeout=10):
                return object()

        fake = types.ModuleType("bleak")
        fake.BleakClient, fake.BleakScanner = Client, Scanner
        self._saved = sys.modules.get("bleak")
        sys.modules["bleak"] = fake

    def tearDown(self):
        import sys
        if self._saved is None:
            sys.modules.pop("bleak", None)
        else:
            sys.modules["bleak"] = self._saved

    def test_query_and_picture(self):
        from gesits.ble import BleTransport
        from gesits.client import GesitsClient

        with GesitsClient(BleTransport("GESITS")) as c:
            self.assertEqual(c.request("SOC"), "77")
            c.show_jpeg("Q" * 1000)
        self.assertTrue(all(resp is True for _, resp in self.writes))   # always write-with-response
        maps = [w for w, _ in self.writes if w != b"AT+SOC?\r\n"]
        self.assertEqual(maps[0], b'AT+MAPS="')
        self.assertEqual(b"".join(maps), b'AT+MAPS="' + b"Q" * 1000 + b'"\r\n')

    def test_missing_device(self):
        from gesits.ble import BleTransport
        with self.assertRaises(ConnectionError):
            BleTransport("NOPE", timeout=0.1)
