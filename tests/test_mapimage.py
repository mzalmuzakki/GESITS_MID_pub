import random
import unittest

from gesits import mapimage as m


def random_codes(seed):
    rnd = random.Random(seed)
    return [[rnd.randrange(4) for _ in range(m.WIDTH)] for _ in range(m.HEIGHT)]


class Encoding(unittest.TestCase):
    def test_matches_app_algorithm(self):
        for seed in range(5):
            codes = random_codes(seed)
            self.assertEqual(m.encode_codes(codes), m.app_reference_encode(codes))

    def test_length_and_roundtrip(self):
        codes = random_codes(42)
        payload = m.encode_codes(codes)
        self.assertEqual(len(payload), 7216)
        self.assertEqual(m.decode_hex(payload), codes)

    def test_pixel_order(self):
        codes = [[0] * m.WIDTH for _ in range(m.HEIGHT)]
        codes[0][0] = 3          # first pixel -> lowest two bits of first byte
        codes[0][3] = 1          # fourth pixel -> top two bits of first byte
        self.assertTrue(m.encode_codes(codes).startswith("43"))

    def test_classify_like_app(self):
        self.assertEqual(m.classify(255, 255, 255), m.WHITE)
        self.assertEqual(m.classify(10, 20, 30), m.BLACK)
        self.assertEqual(m.classify(30, 120, 250), m.BLUE)
        self.assertEqual(m.classify(250, 60, 60), m.RED)
        self.assertEqual(m.classify(128, 128, 128), m.WHITE)  # anything else
        self.assertEqual(m.classify(250, 250, 100), m.RED)


if __name__ == "__main__":
    unittest.main()
