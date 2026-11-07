#!/usr/bin/env python3
"""Сводная таблица WER, DER, ошибки границ и RTF по всем записям — Markdown для docs/TESTS.md.

RTF берётся из <имя>.timings.json: его пишет run_pipeline.py (ПК) и debug-сборка приложения
(adb pull /sdcard/Android/data/hackuna.matata/files/exports/ — файлы <id>.timings.json;
переименуйте в <имя>.timings.json или укажите --timings).

Пример:
  python report.py --data data --out out --timings out > report.md
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from common import find_pairs, load_transcript
from eval_der import der_pair
from eval_timestamps import boundary_errors
from eval_wer import wer_pair


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--data", required=True, help="каталог эталонов <имя>.ref.json")
    p.add_argument("--out", default="out", help="каталог гипотез <имя>.json")
    p.add_argument("--timings", default=None, help="каталог <имя>.timings.json (по умолчанию = --out)")
    p.add_argument("--collar-ms", type=int, default=250)
    p.add_argument("--title", default="Результаты", help="заголовок таблицы")
    a = p.parse_args()

    pairs = find_pairs(Path(a.data), Path(a.out))
    if not pairs:
        p.error(f"нет пар эталон/гипотеза в {a.data} и {a.out}")
    tdir = Path(a.timings or a.out)
    rows = []
    for name, ref, hyp in pairs:
        wer, words = wer_pair(ref, hyp, keep_punct=False, no_normalize=False)
        der = der_pair(ref, hyp, a.collar_ms, skip_overlap=False)
        b = boundary_errors(load_transcript(ref), load_transcript(hyp), 0.1)
        tf = tdir / f"{name}.timings.json"
        rtf = json.loads(tf.read_text(encoding="utf-8")).get("rtf") if tf.exists() else None
        rows.append((name, wer, words, der, b["mean_boundary_ms"], rtf))

    print(f"### {a.title}\n")
    print("| Запись | WER | Слов | DER (collar {} мс) | Ошибка границ, мс | RTF |".format(a.collar_ms))
    print("|---|---|---|---|---|---|")
    for name, wer, words, der, bnd, rtf in rows:
        print(f"| {name} | {wer:.3f} | {words} | {der:.3f} | {bnd:.0f} | {'—' if rtf is None else f'{rtf:.3f}'} |")
    total_words = sum(r[2] for r in rows) or 1
    print(f"| **Итого** | {sum(r[1] * r[2] for r in rows) / total_words:.3f} | {total_words} | "
          f"{sum(r[3] for r in rows) / len(rows):.3f} | — | — |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
