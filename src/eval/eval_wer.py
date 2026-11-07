#!/usr/bin/env python3
"""WER гипотезы против эталона (тексты реплик склеиваются по времени).

Нормализация по умолчанию: нижний регистр, ё→е, без пунктуации.

Примеры:
  python eval_wer.py --ref data/cats_1.ref.json --hyp out/cats_1.json
  python eval_wer.py --data data --out out            # все пары <имя>.ref.json / <имя>.json
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from common import find_pairs, load_transcript, normalize, transcript_text


def wer_pair(ref: Path, hyp: Path, keep_punct: bool, no_normalize: bool) -> tuple[float, int]:
    import jiwer

    r = transcript_text(load_transcript(ref))
    h = transcript_text(load_transcript(hyp))
    if not no_normalize:
        r, h = normalize(r, keep_punct), normalize(h, keep_punct)
    words = len(r.split())
    return (jiwer.wer(r, h) if words else float(bool(h))), words


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ref", help="эталон JSON")
    p.add_argument("--hyp", help="гипотеза JSON")
    p.add_argument("--data", help="каталог с <имя>.ref.json (пакетный режим)")
    p.add_argument("--out", default="out", help="каталог гипотез <имя>.json (пакетный режим)")
    p.add_argument("--keep-punct", action="store_true", help="не удалять пунктуацию")
    p.add_argument("--no-normalize", action="store_true", help="не нормализовать текст вовсе")
    a = p.parse_args()

    pairs = [(Path(a.hyp).stem, Path(a.ref), Path(a.hyp))] if a.ref and a.hyp else \
        find_pairs(Path(a.data), Path(a.out)) if a.data else []
    if not pairs:
        p.error("укажите --ref и --hyp или --data")
    total_err = total_words = 0.0
    for name, ref, hyp in pairs:
        w, n = wer_pair(ref, hyp, a.keep_punct, a.no_normalize)
        total_err += w * n
        total_words += n
        print(f"{name}\tWER={w:.4f}\twords={n}")
    if len(pairs) > 1:
        print(f"ИТОГО\tWER={total_err / max(1, total_words):.4f}\twords={int(total_words)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
