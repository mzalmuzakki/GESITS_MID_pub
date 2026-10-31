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
        self.assertEqual(protocol.nav_text("120.0 m turn-left"), b'AT+NAV="120.0 m turn-left"\r\n')
        with self.assertRaises(ValueError):
            protocol.nav_text('bad "quote"')

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
        t.update([("SOC", "80"), ("MODE", "2"), ("ERR", "0000")])
        self.assertEqual(t.drive_mode, "SPORT")
        self.assertEqual(t.range_km, 80.0)
        self.assertEqual(t.energy_kwh, 2.24)
        self.assertFalse(t.has_error)
        t.update([("ERR", "0102")])
        self.assertTrue(t.has_error)


if __name__ == "__main__":
    unittest.main()
