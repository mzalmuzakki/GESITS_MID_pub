"""Adaptive range: learns km per 1 % battery from odometer (ODO) vs battery level (SOC).

Same algorithm as the Android app (RangeEstimator.kt):
* every SOC drop of >= 1 % records (km ridden since the previous drop, % dropped);
* only the most recent `window_percent` % of battery use is kept;
* with no data the result is the prior (starting value); the prior counts like
  `prior_weight_percent` % of riding and fades out by `fade_percent` % learned;
* charging, odometer going backwards, gaps > 30 min and impossible segments are not learned.
"""
from __future__ import annotations

import json
import math
import time
from collections import deque
from pathlib import Path
from typing import Optional


class RangeEstimator:
    def __init__(self, prior_km_per_percent: float = 1.0, window_percent: float = 60.0,
                 prior_weight_percent: float = 8.0, fade_percent: float = 20.0,
                 max_km_per_percent: float = 5.0, max_gap_s: float = 30 * 60) -> None:
        self.prior = prior_km_per_percent
        self.window = window_percent
        self.prior_weight = prior_weight_percent
        self.fade = fade_percent
        self.max_kmpp = max_km_per_percent
        self.max_gap = max_gap_s
        self.reset()

    def reset(self) -> None:
        self.segments: deque = deque()   # (km, percent)
        self.anchor_odo = math.nan
        self.anchor_soc = math.nan
        self.last_t = 0.0

    @property
    def learned_km(self) -> float:
        return sum(k for k, _ in self.segments)

    @property
    def learned_percent(self) -> float:
        return sum(p for _, p in self.segments)

    @property
    def fully_learned(self) -> bool:
        return self.learned_percent >= self.fade

    @property
    def km_per_percent(self) -> float:
        pct = self.learned_percent
        w = self.prior_weight * max(0.0, 1.0 - pct / self.fade)
        if pct + w <= 0:
            return self.prior
        return max(0.05, (self.learned_km + self.prior * w) / (pct + w))

    def range_km(self, soc: float) -> float:
        return min(max(soc, 0.0), 100.0) * self.km_per_percent

    def update(self, odo_km: float, soc: float, now: Optional[float] = None) -> bool:
        """Feed each new (ODO, SOC) pair; returns True when a segment was learned."""
        now = time.time() if now is None else now
        if odo_km is None or soc is None or not 0 <= soc <= 100:
            return False
        gap, self.last_t = now - self.last_t, now
        if (math.isnan(self.anchor_odo) or gap > self.max_gap or odo_km < self.anchor_odo - 0.05
                or soc > self.anchor_soc + 0.5):
            self.anchor_odo, self.anchor_soc = odo_km, soc
            return False
        drop = self.anchor_soc - soc
        if drop < 1:
            return False
        km = odo_km - self.anchor_odo
        self.anchor_odo, self.anchor_soc = odo_km, soc
        if km < 0 or km > drop * self.max_kmpp:
            return False
        self.segments.append((km, drop))
        while len(self.segments) > 1 and self.learned_percent - self.segments[0][1] >= self.window:
            self.segments.popleft()
        return True

    def describe(self) -> str:
        state = "learned" if self.fully_learned else "learning"
        return (f"{self.km_per_percent:.2f} km per 1% - {state} "
                f"({self.learned_km:.1f} km over {self.learned_percent:.0f}% battery)")

    # -- persistence -------------------------------------------------------------
    def save(self, path) -> None:
        Path(path).write_text(json.dumps({
            "anchor_odo": None if math.isnan(self.anchor_odo) else self.anchor_odo,
            "anchor_soc": None if math.isnan(self.anchor_soc) else self.anchor_soc,
            "last_t": self.last_t, "segments": list(self.segments), "prior": self.prior}))

    def load(self, path) -> None:
        self.reset()
        try:
            d = json.loads(Path(path).read_text())
            self.anchor_odo = math.nan if d["anchor_odo"] is None else float(d["anchor_odo"])
            self.anchor_soc = math.nan if d["anchor_soc"] is None else float(d["anchor_soc"])
            self.last_t = float(d["last_t"])
            self.segments = deque((float(k), float(p)) for k, p in d["segments"])
        except (OSError, ValueError, KeyError, TypeError):
            self.reset()
