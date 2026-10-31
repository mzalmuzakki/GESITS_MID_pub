import unittest
from datetime import datetime

from gesits import protocol
from gesits.protocol import LineParser, Telemetry


class CommandBytes(unittest.TestCase):
    """Byte-exact strings recovered from the app."""

    def test_query(self):
        self.assertEqual(protocol.query("SPD"), b"AT+SPD?\r\n")
        self.assertEqual(protocol.query("err"), b"AT+ERR?\r\n")
        with self.assertRaises(ValueError):
            protocol.query("SPD?\r\nAT+X")

    def test_time(self):
        self.assertEqual(protocol.set_time(datetime(2026, 1, 1, 7, 5)), b'AT+TIME="0705"\r\n')

    def test_nav(self):
        self.assertEqual(protocol.nav_text("120.0 m turn-left"), b'AT+NAV="120.0 m turn-left   "\r\n')
        self.assertEqual(protocol.nav_text("hi", pad=False), b'AT+NAV="hi"\r\n')
        with self.assertRaises(ValueError):
            protocol.nav_text('bad "quote"')
        with self.assertRaises(ValueError):
            protocol.nav_text("x" * 21)

    def test_split_nav(self):
        self.assertEqual(protocol.split_nav_text("cuaca cerah riding aman"), ["cuaca cerah", "riding aman"])
        self.assertEqual(protocol.split_nav_text("Jalan Médan Merdéka"), ["Jalan Medan Merdeka"])
        for part in protocol.split_nav_text("Belok kiri ke Jalan Jenderal Sudirman Raya Kebayoran"):
            self.assertLessEqual(len(part), 20)

    def test_trip_and_maps(self):
        self.assertEqual(protocol.trip_range(42), b'AT+TRIP="42.0"\r\n')
        self.assertTrue(protocol.map_image("/9j/4AA=").startswith(b'AT+MAPS="/9j/'))
        with self.assertRaises(ValueError):
            protocol.map_image('x"\r\nAT+ODO="1"')

    def test_fixed(self):
        self.assertEqual(protocol.MAP_ON, b'AT+MAPON="1"\r\n')
        self.assertEqual(protocol.MAP_OFF, b'AT+MAPON="0"\r\n')
        self.assertEqual(protocol.MAP_END, b"AT+MAPEND\r\n")


class Parsing(unittest.TestCase):
    def test_split_reads(self):
        p = LineParser()
        self.assertEqual(p.feed(b"SP"), [])
        self.assertEqual(p.feed(b"D=42\r\nSOC=8"), [("SPD", "42")])
        self.assertEqual(p.feed(b"0\r\n"), [("SOC", "80")])

    def test_derived(self):
        t = Telemetry()
        t.update([("SOC", "80"), ("MODE", "2"), ("ERR", "0000"), ("PARK", "1"),
                  ("VBAT", "72.5"), ("IBAT", "-10")])
        self.assertEqual(t.drive_mode, "SPORT")
        self.assertEqual(t.park_state, "PARK")
        self.assertEqual(t.power_w, -725.0)
        self.assertEqual(t.range_km, 80.0)
        self.assertFalse(t.has_error)
        t.update([("ERR", "0800")])
        self.assertTrue(t.has_error)
        self.assertEqual(len(t.errors), 1)
        self.assertIn("Throttle", t.errors[0])


if __name__ == "__main__":
    unittest.main()
