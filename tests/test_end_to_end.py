"""Client <-> simulator over a real TCP socket."""
import socket
import tempfile
import threading
import unittest
from pathlib import Path

from gesits import mapimage, simulator
from gesits.client import GesitsClient
from gesits.transport import open_transport


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class EndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        cls.tmp = tempfile.TemporaryDirectory()
        ready = threading.Event()
        threading.Thread(target=simulator.serve, daemon=True,
                         kwargs=dict(port=cls.port, preview_dir=cls.tmp.name, ready=ready, verbose=False)).start()
        ready.wait(5)

    def test_poll_and_map(self):
        with GesitsClient(open_transport(f"tcp://127.0.0.1:{self.port}")) as c:
            tel = c.poll(["SOC", "SPD", "MODE", "ERR", "PARK", "VBAT", "IBAT"])
            self.assertEqual(tel.raw["SOC"], "82")
            self.assertIn(tel.drive_mode, ("ECO", "URBAN", "SPORT"))
            self.assertEqual(tel.park_state, "DRIVE")
            self.assertIsNotNone(tel.power_w)
            self.assertFalse(tel.has_error)
            from PIL import Image
            b64, _, _ = mapimage.jpeg_to_base64(Image.new("RGB", (800, 600), (0, 90, 255)))
            c.show_text("Hello Gesits")
            c.show_range(42.0)
            c.show_jpeg(b64)
            codes = [[(x // 41) for x in range(mapimage.WIDTH)] for _ in range(mapimage.HEIGHT)]
            c.show_map(codes, "50.0 m turn-right")
            c.request("SPD")  # round-trip so the simulator has processed everything
        self.assertEqual(len(list(Path(self.tmp.name).glob("*.jpg"))), 1)
        self.assertEqual(len(list(Path(self.tmp.name).glob("*.png"))), 1)


if __name__ == "__main__":
    unittest.main()
