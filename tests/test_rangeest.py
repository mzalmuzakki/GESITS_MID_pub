import math
import tempfile
import unittest
from pathlib import Path

from gesits.rangeest import RangeEstimator

MIN = 60.0


def ride(r, odo, soc0, pct, kmpp, t0):
    t = t0
    for i in range(pct * 10 + 1):
        r.update(odo, float(math.ceil(soc0 - i / 10)), t)   # MID reports whole %
        odo += kmpp / 10
        t += 10
    return odo, t


class RangeEst(unittest.TestCase):
    """Mirrors android/app/src/test/.../RangeTest.kt so both versions behave the same."""

    def test_prior_then_learned(self):
        r = RangeEstimator(1.0)
        self.assertEqual(r.range_km(80), 80)
        ride(r, 1000, 90, 40, 0.6, 0)
        self.assertAlmostEqual(r.km_per_percent, 0.6, delta=0.02)
        self.assertTrue(r.fully_learned)

    def test_early_blend(self):
        r = RangeEstimator(1.0)
        r.update(0, 80, 0)
        r.update(3, 75, MIN)
        self.assertAlmostEqual(r.km_per_percent, 9 / 11, places=3)

    def test_window_follows_recent(self):
        r = RangeEstimator()
        odo, t = ride(r, 0, 100, 50, 1.2, 0)
        ride(r, odo, 50, 45, 0.5, t)
        self.assertLessEqual(r.learned_percent, 61)
        self.assertLess(r.km_per_percent, 0.75)

    def test_charging_gap_implausible(self):
        r = RangeEstimator()
        r.update(100, 50, 0); r.update(110, 40, MIN)
        r.update(110, 90, 2 * MIN)                  # charged -> restart
        r.update(130, 60, 3 * MIN)
        self.assertEqual((r.learned_km, r.learned_percent), (30, 40))
        r2 = RangeEstimator()
        r2.update(100, 80, 0); r2.update(150, 30, 7200)          # 2 h gap
        r2.update(150, 30, 7260); r2.update(210, 29, 7320)       # 60 km for 1 %
        self.assertEqual(r2.learned_percent, 0)

    def test_save_load(self):
        r = RangeEstimator()
        ride(r, 500, 80, 20, 0.7, 0)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "r.json"
            r.save(f)
            r2 = RangeEstimator(); r2.load(f)
            self.assertAlmostEqual(r2.km_per_percent, r.km_per_percent, places=4)
            f.write_text("garbage"); r2.load(f)
            self.assertEqual(r2.learned_percent, 0)


if __name__ == "__main__":
    unittest.main()
