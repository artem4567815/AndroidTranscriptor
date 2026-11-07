"""Общие функции стенда: конфиг, формат JSON, склейка реплик (как в Kotlin pipeline)."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = ROOT / "config" / "pipeline.default.json"


def load_config(path: str | Path | None = None) -> dict:
    """Те же параметры, что PipelineConfig в core/model (config/pipeline.default.json)."""
    with open(path or DEFAULT_CONFIG, encoding="utf-8") as f:
        return json.load(f)


def load_transcript(path: str | Path) -> list[dict]:
    """Читает строгий формат организаторов: {"transcript": [{speaker_id, start_ms, end_ms, text}]}."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    items = data["transcript"]
    for u in items:
        missing = {"speaker_id", "start_ms", "end_ms", "text"} - u.keys()
        if missing:
            raise ValueError(f"{path}: в реплике нет полей {missing}")
    return sorted(items, key=lambda u: (u["start_ms"], u["end_ms"]))


def save_transcript(items: list[dict], path: str | Path) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"transcript": items}, f, ensure_ascii=False, indent=2)


# ---- Нормализация текста для WER ----

_PUNCT = re.compile(r"[^\w\s]", re.UNICODE)


def normalize(text: str, keep_punct: bool = False) -> str:
    """Нижний регистр, ё→е, без пунктуации, схлопнутые пробелы.

    TODO(роль 4): уточнить у организаторов нормализацию (числа цифрами/словами) — см. docs/TESTS.md.
    """
    t = text.lower().replace("ё", "е")
    if not keep_punct:
        t = _PUNCT.sub(" ", t).replace("_", " ")
    return " ".join(t.split())


def transcript_text(items: list[dict]) -> str:
    return " ".join(u["text"].strip() for u in items if u["text"].strip())


# ---- Предобработка — порт DefaultAudioPreprocessor (core/audio), чтобы стенд совпадал с приложением ----

def preprocess(samples, target_peak: float = 0.9, max_gain: float = 10.0):
    """DC-фильтр y[n] = x[n] - x[n-1] + 0.995*y[n-1] и нормализация пика (как в Kotlin)."""
    import numpy as np

    x = np.asarray(samples, dtype=np.float64)
    if x.size == 0:
        return np.asarray(samples, dtype=np.float32)
    # y[n] = 0.995*y[n-1] + (x[n] - x[n-1]) — однополюсный фильтр; считаем блоками через cumsum-трюк нельзя,
    # поэтому простой цикл: ~1 с на минуту аудио, для стенда достаточно.
    d = np.diff(x, prepend=0.0)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(x.size):
        acc = d[i] + 0.995 * acc
        y[i] = acc
    peak = float(np.max(np.abs(y)))
    if peak > 1e-6:
        y = np.clip(y * min(target_peak / peak, max_gain), -1.0, 1.0)
    return y.astype(np.float32)


# ---- Склейка и нарезка — порт UtteranceMerger / SegmentPlanner из модуля pipeline ----

@dataclass
class SpeakerText:
    speaker: int
    start_ms: int
    end_ms: int
    text: str


def merge_utterances(segments: list[SpeakerText], cfg: dict) -> list[dict]:
    gap = cfg["mergeGapMs"]
    items = [
        SpeakerText(s.speaker, max(0, s.start_ms), s.end_ms, " ".join(s.text.split()))
        for s in segments
    ]
    items = [s for s in items if any(ch.isalnum() for ch in s.text)]
    items.sort(key=lambda s: (s.start_ms, s.end_ms))
    merged: list[SpeakerText] = []
    for s in items:
        if merged and merged[-1].speaker == s.speaker and s.start_ms - merged[-1].end_ms < gap:
            last = merged[-1]
            last.end_ms = max(last.end_ms, s.end_ms)
            last.text = f"{last.text} {s.text}".strip()
        else:
            merged.append(s)
    ids: dict[int, str] = {}
    out = []
    for s in merged:
        sid = ids.setdefault(s.speaker, f"speaker_{len(ids) + 1}")
        out.append({"speaker_id": sid, "start_ms": int(s.start_ms),
                    "end_ms": int(max(s.end_ms, s.start_ms + 1)), "text": s.text})
    return out


def pause_midpoints(speech: list[tuple[int, int]]) -> list[int]:
    speech = sorted(speech)
    return [(a[1] + b[0]) // 2 for a, b in zip(speech, speech[1:]) if b[0] > a[1]]


def resolve_overlaps(segments: list[tuple[int, int, int]]) -> list[tuple[int, int, int]]:
    """Порт SegmentPlanner.resolveOverlaps: каждый момент — одному спикеру. (speaker, start, end)."""
    out: list[list[int]] = []
    for spk, start, end in sorted(segments, key=lambda s: (s[1], s[2])):
        if out and start < out[-1][1]:
            start = out[-1][1]  # начало предыдущего могли сдвинуть разрезом
        if end <= start:
            continue
        if not out or start >= out[-1][2]:
            out.append([spk, start, end])
            continue
        prev = out[-1]
        if spk == prev[0]:
            prev[2] = max(prev[2], end)
        elif end < prev[2]:
            tail_end = prev[2]
            prev[2] = start
            out.append([spk, start, end])
            out.append([prev[0], end, tail_end])
        else:
            mid = (start + prev[2]) // 2
            prev[2] = mid
            out.append([spk, mid, end])
    return [(s, a, b) for s, a, b in out if b > a]


def plan_segments(segments: list[tuple[int, int, int]], speech: list[tuple[int, int]], cfg: dict,
                  duration_ms: int) -> list[tuple[int, int, int, int, int]]:
    """(speaker, start, end) → [(speaker, start, end, audio_start, audio_end)]."""
    max_ms, pad, min_ms = cfg["maxSegmentMs"], cfg["segmentPaddingMs"], cfg["minUtteranceMs"]
    cuts = pause_midpoints(speech)
    out = []
    for spk, start, end in (s for s in resolve_overlaps(segments) if s[2] - s[1] >= min_ms):
        cur = start
        inside = [c for c in cuts if start < c < end]
        while end - cur > max_ms:
            cands = [c for c in inside if cur + max_ms // 4 < c <= cur + max_ms]
            cut = cands[-1] if cands else cur + max_ms
            out.append((spk, cur, cut))
            cur = cut
        out.append((spk, cur, end))
    return [(s, a, b, max(0, a - pad), min(duration_ms, b + pad)) for s, a, b in out]


def find_pairs(data_dir: Path, hyp_dir: Path) -> list[tuple[str, Path, Path]]:
    """Пары (имя, эталон data/<имя>.ref.json, гипотеза out/<имя>.json)."""
    pairs = []
    for ref in sorted(data_dir.glob("*.ref.json")):
        name = ref.name[: -len(".ref.json")]
        hyp = hyp_dir / f"{name}.json"
        if hyp.exists():
            pairs.append((name, ref, hyp))
    return pairs
