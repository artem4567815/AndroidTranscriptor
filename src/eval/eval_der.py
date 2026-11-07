#!/usr/bin/env python3
"""DER (Diarization Error Rate) через pyannote.metrics.

Collar по умолчанию 250 мс (как принято на соревнованиях; настраивается).
Имена спикеров эталона и гипотезы сопоставляются оптимально (Венгерский алгоритм внутри pyannote).

Примеры:
  python eval_der.py --ref data/cats_1.ref.json --hyp out/cats_1.json
  python eval_der.py --data data --out out --collar-ms 250
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from common import find_pairs, load_transcript


def annotation(items: list[dict]):
    from pyannote.core import Annotation, Segment

    ann = Annotation()
    for i, u in enumerate(items):
        if u["end_ms"] > u["start_ms"]:
            ann[Segment(u["start_ms"] / 1000, u["end_ms"] / 1000), i] = u["speaker_id"]
    return ann


def der_pair(ref: Path, hyp: Path, collar_ms: int, skip_overlap: bool) -> float:
    from pyannote.metrics.diarization import DiarizationErrorRate

    metric = DiarizationErrorRate(collar=collar_ms / 1000, skip_overlap=skip_overlap)
    return float(metric(annotation(load_transcript(ref)), annotation(load_transcript(hyp))))


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ref")
    p.add_argument("--hyp")
    p.add_argument("--data", help="каталог с <имя>.ref.json")
    p.add_argument("--out", default="out", help="каталог гипотез")
    p.add_argument("--collar-ms", type=int, default=250, help="collar в мс (по умолчанию 250)")
    p.add_argument("--skip-overlap", action="store_true", help="не оценивать участки наложения речи")
    a = p.parse_args()
    pairs = [(Path(a.hyp).stem, Path(a.ref), Path(a.hyp))] if a.ref and a.hyp else \
        find_pairs(Path(a.data), Path(a.out)) if a.data else []
    if not pairs:
        p.error("укажите --ref и --hyp или --data")
    for name, ref, hyp in pairs:
        print(f"{name}\tDER={der_pair(ref, hyp, a.collar_ms, a.skip_overlap):.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
