#!/usr/bin/env python3
"""Средняя ошибка границ реплик (мс) для сопоставленных реплик.

Каждой реплике эталона ставится в пару реплика гипотезы с максимальным пересечением по времени
(IoU > --min-iou). Считаются средние |Δstart| и |Δend| и доля сопоставленных реплик.

Примеры:
  python eval_timestamps.py --ref data/cats_1.ref.json --hyp out/cats_1.json
  python eval_timestamps.py --data data --out out
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from common import find_pairs, load_transcript


def iou(a: dict, b: dict) -> float:
    inter = min(a["end_ms"], b["end_ms"]) - max(a["start_ms"], b["start_ms"])
    union = max(a["end_ms"], b["end_ms"]) - min(a["start_ms"], b["start_ms"])
    return max(0, inter) / union if union > 0 else 0.0


def boundary_errors(ref: list[dict], hyp: list[dict], min_iou: float) -> dict:
    ds, de = [], []
    for r in ref:
        best = max(hyp, key=lambda h: iou(r, h), default=None)
        if best is not None and iou(r, best) > min_iou:
            ds.append(abs(r["start_ms"] - best["start_ms"]))
            de.append(abs(r["end_ms"] - best["end_ms"]))
    n = len(ds)
    return {
        "matched": n,
        "total": len(ref),
        "mean_start_ms": sum(ds) / n if n else float("nan"),
        "mean_end_ms": sum(de) / n if n else float("nan"),
        "mean_boundary_ms": (sum(ds) + sum(de)) / (2 * n) if n else float("nan"),
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ref")
    p.add_argument("--hyp")
    p.add_argument("--data")
    p.add_argument("--out", default="out")
    p.add_argument("--min-iou", type=float, default=0.1, help="минимальный IoU для пары (по умолчанию 0.1)")
    a = p.parse_args()
    pairs = [(Path(a.hyp).stem, Path(a.ref), Path(a.hyp))] if a.ref and a.hyp else \
        find_pairs(Path(a.data), Path(a.out)) if a.data else []
    if not pairs:
        p.error("укажите --ref и --hyp или --data")
    for name, ref, hyp in pairs:
        e = boundary_errors(load_transcript(ref), load_transcript(hyp), a.min_iou)
        print(f"{name}\tboundary={e['mean_boundary_ms']:.0f}ms\tstart={e['mean_start_ms']:.0f}ms\t"
              f"end={e['mean_end_ms']:.0f}ms\tmatched={e['matched']}/{e['total']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
