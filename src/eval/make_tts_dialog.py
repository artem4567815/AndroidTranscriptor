#!/usr/bin/env python3
"""Синтетический русский диалог 3 спикеров + эталон в формате организаторов (только macOS: команда `say`).

Голос один (Milena), «разные спикеры» получаются изменением тембра. Поэтому запись годится для проверки
ASR (WER) и всего пайплайна, но НЕ для подбора порогов диаризации — для этого нужны живые голоса.

Пример:
  python make_tts_dialog.py data/      # → data/cats_ru_3spk.wav + data/cats_ru_3spk.ref.json
"""
import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

_p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
_p.add_argument("out", help="каталог для .wav и .ref.json")
out = Path(_p.parse_args().out)
out.mkdir(parents=True, exist_ok=True)
SR = 16000
# коэффициент тембра: <1 — ниже и медленнее, >1 — выше и быстрее
VOICES = {"speaker_1": 1.0, "speaker_2": 0.78, "speaker_3": 1.22}
LINES = [
    ("speaker_1", "Добрый день, коллеги. Открываем заседание совета котиков."),
    ("speaker_2", "Предлагаю начать с самого важного вопроса, с вопроса о сметане."),
    ("speaker_3", "Поддерживаю. Запасы сметаны на кухне подходят к концу."),
    ("speaker_1", "Кто вчера дежурил у холодильника?"),
    ("speaker_2", "Я дежурил весь вечер, дверь открывали три раза."),
    ("speaker_3", "Тогда нужно сесть у холодильника всем вместе и громко мяукать."),
    ("speaker_1", "Хорошо. Второй вопрос касается коробки в прихожей."),
    ("speaker_2", "Коробка моя, я сплю в ней с понедельника."),
    ("speaker_3", "Предлагаю спать в коробке по очереди, по два часа."),
    ("speaker_1", "Голосуем. Решение принято, заседание закрыто."),
]
PAUSES_MS = [600, 450, 800, 500, 700, 550, 900, 400, 650]

def tts(text, path):
    subprocess.run(["say", "-v", "Milena", "-o", str(path), "--file-format=WAVE",
                    "--data-format=LEI16@16000", text], check=True)
    x, sr = sf.read(path, dtype="float32")
    assert sr == SR
    return x

def retimbre(x, f):
    if f == 1.0: return x
    n = int(len(x) / f)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype("float32")

def trim(x, thr=0.01):
    idx = np.where(np.abs(x) > thr)[0]
    return x[idx[0]:idx[-1] + 1] if len(idx) else x

audio = [np.zeros(int(0.5 * SR), dtype="float32")]
t = 500
ref = []
for i, (spk, text) in enumerate(LINES):
    x = trim(retimbre(tts(text, out / f"l{i}.wav"), VOICES[spk]))
    start, end = t, t + int(len(x) * 1000 / SR)
    ref.append({"speaker_id": spk, "start_ms": start, "end_ms": end, "text": text})
    audio.append(x)
    t = end
    if i < len(PAUSES_MS):
        audio.append(np.zeros(int(PAUSES_MS[i] * SR / 1000), dtype="float32")); t += PAUSES_MS[i]
audio.append(np.zeros(int(0.5 * SR), dtype="float32"))
sf.write(out / "cats_ru_3spk.wav", np.concatenate(audio), SR, subtype="PCM_16")
(out / "cats_ru_3spk.ref.json").write_text(json.dumps({"transcript": ref}, ensure_ascii=False, indent=2), encoding="utf-8")
for f in out.glob("l*.wav"): f.unlink()
print(f"готово: {t/1000:.1f} с, {len(ref)} реплик")
