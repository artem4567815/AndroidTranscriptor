#!/usr/bin/env python3
"""Перебор параметров пайплайна на всех записях с эталоном и таблица метрик.

Для каждой комбинации значений прогоняет run_pipeline.py по всем data/<имя>.wav, у которых есть
data/<имя>.ref.json, и считает DER (и WER с --full). Лучшая комбинация — сверху.

Примеры:
  python tune.py --param minSpeakerSilhouette=0.05,0.1,0.15 --param embeddingPaddingMs=100,200,300
  python tune.py --param mergeGapMs=300,500,700 --full          # с ASR: ещё WER (дольше)
  python tune.py --param regroupSpeakers=true,false               # второй проход vs только sherpa
"""
from __future__ import annotations

import argparse
import itertools
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def metric(script: str, ref: Path, hyp: Path) -> float:
    out = subprocess.run([sys.executable, str(HERE / script), "--ref", str(ref), "--hyp", str(hyp)],
                         capture_output=True, text=True, check=True).stdout
    return float(out.strip().split("=")[-1].split()[0])


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--param", action="append", required=True, metavar="ИМЯ=v1,v2,...",
                   help="параметр PipelineConfig и значения через запятую; можно несколько --param")
    p.add_argument("--data", default=str(HERE / "data"), help="каталог с <имя>.wav и <имя>.ref.json")
    p.add_argument("--full", action="store_true", help="с ASR: считать ещё WER (медленнее)")
    a = p.parse_args()

    grid = {}
    for item in a.param:
        key, _, values = item.partition("=")
        grid[key] = values.split(",")
    wavs = [w for w in sorted(Path(a.data).glob("*.wav")) if w.with_suffix(".ref.json").exists()]
    if not wavs:
        p.error(f"в {a.data} нет пар <имя>.wav + <имя>.ref.json")
    print(f"Записей: {len(wavs)} ({', '.join(w.stem for w in wavs)}); комбинаций: "
          f"{len(list(itertools.product(*grid.values())))}\n")

    rows = []
    for combo in itertools.product(*grid.values()):
        sets = [f"{k}={v}" for k, v in zip(grid, combo)]
        with tempfile.TemporaryDirectory() as out:
            cmd = [sys.executable, str(HERE / "run_pipeline.py"), *map(str, wavs), "-o", out]
            for s in sets:
                cmd += ["--set", s]
            if not a.full:
                cmd.append("--diarization-only")
            subprocess.run(cmd, capture_output=True, text=True, check=True)
            ders, wers = [], []
            for w in wavs:
                hyp = Path(out) / f"{w.stem}.json"
                ders.append(metric("eval_der.py", w.with_suffix(".ref.json"), hyp))
                if a.full:
                    wers.append(metric("eval_wer.py", w.with_suffix(".ref.json"), hyp))
        rows.append((sum(ders) / len(ders), sum(wers) / len(wers) if wers else None, sets, ders))
        print(f"  {' '.join(sets):55s} DER={rows[-1][0]:.3f}" + (f"  WER={rows[-1][1]:.3f}" if wers else ""))

    print("\nЛучшие (по среднему DER):")
    for der, wer, sets, ders in sorted(rows, key=lambda r: r[0])[:5]:
        per = "  ".join(f"{w.stem}={d:.3f}" for w, d in zip(wavs, ders))
        print(f"  DER={der:.3f}" + (f" WER={wer:.3f}" if wer is not None else "") + f"  {' '.join(sets)}   [{per}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
