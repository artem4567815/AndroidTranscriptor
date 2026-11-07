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
        if out and spk == out[-1][0] and start <= out[-1][2]:
            out[-1][2] = max(out[-1][2], end)
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


def uncovered(speech: list[tuple[int, int]], segs: list[tuple[int, int, int]]) -> list[tuple[int, int]]:
    """Части участков речи, не покрытые отрезками (speaker, start, end)."""
    res = []
    for a, b in sorted(speech):
        cur = a
        for _, s, e in segs:
            if e <= cur or s >= b:
                continue
            if s > cur:
                res.append((cur, s))
            cur = max(cur, e)
        if cur < b:
            res.append((cur, b))
    return res


def cover_speech(segs: list[tuple[int, int, int]], speech: list[tuple[int, int]],
                 attach_ms: int = 300, min_gap_ms: int = 50) -> list[tuple[int, int, int]]:
    """Порт SegmentPlanner.coverSpeech: «ничья» речь VAD отдаётся ближайшему спикеру."""
    if not segs or not speech:
        return segs
    out = [list(x) for x in sorted(segs, key=lambda x: x[1])]
    for ga, gb in [g for g in uncovered(speech, [tuple(x) for x in out]) if g[1] - g[0] >= min_gap_ms]:
        prev = max((i for i, x in enumerate(out) if x[2] <= ga), default=None)
        nxt = min((i for i, x in enumerate(out) if x[1] >= gb), default=None)
        d_prev = ga - out[prev][2] if prev is not None else float("inf")
        d_next = out[nxt][1] - gb if nxt is not None else float("inf")
        if d_prev <= d_next:
            if d_prev <= attach_ms:
                out[prev][2] = gb
            else:
                out.append([out[prev][0], ga, gb])
        else:
            if d_next <= attach_ms:
                out[nxt][1] = ga
            else:
                out.append([out[nxt][0], ga, gb])
        out.sort(key=lambda x: x[1])
    return [tuple(x) for x in out]


def plan_segments(segments: list[tuple[int, int, int]], speech: list[tuple[int, int]], cfg: dict,
                  duration_ms: int) -> list[tuple[int, int, int, int, int]]:
    """(speaker, start, end) → [(speaker, start, end, audio_start, audio_end)]."""
    max_ms, pad, min_ms = cfg["maxSegmentMs"], cfg["segmentPaddingMs"], cfg["minUtteranceMs"]
    cuts = pause_midpoints(speech)
    out = []
    for spk, start, end in (s for s in cover_speech(resolve_overlaps(segments), speech) if s[2] - s[1] >= min_ms):
        cur = start
        inside = [c for c in cuts if start < c < end]
        while end - cur > max_ms:
            cands = [c for c in inside if cur + max_ms // 4 < c <= cur + max_ms]
            cut = cands[-1] if cands else cur + max_ms
            out.append((spk, cur, cut))
            cur = cut
        out.append((spk, cur, end))
    # Паддинг только в тишину — не заходим в соседний кусок (иначе слово на стыке распознаётся дважды).
    res = []
    for i, (s, a, b) in enumerate(out):
        prev_end = out[i - 1][2] if i > 0 else 0
        next_start = out[i + 1][1] if i + 1 < len(out) else duration_ms
        res.append((s, a, b, min(max(a - pad, prev_end, 0), a), max(min(b + pad, next_start, duration_ms), b)))
    return res


def find_pairs(data_dir: Path, hyp_dir: Path) -> list[tuple[str, Path, Path]]:
    """Пары (имя, эталон data/<имя>.ref.json, гипотеза out/<имя>.json)."""
    pairs = []
    for ref in sorted(data_dir.glob("*.ref.json")):
        name = ref.name[: -len(".ref.json")]
        hyp = hyp_dir / f"{name}.json"
        if hyp.exists():
            pairs.append((name, ref, hyp))
    return pairs


# ---- Второй проход диаризации — порт SegmentPlanner.speechUnits/labelUnits и SpeakerClustering ----

UNIT_MAX_MS, UNIT_WINDOW_MS, MIN_TURN_MS = 3000, 1500, 300
SAME_SPEAKER_SIMILARITY = 0.80


def speech_units(speech: list[tuple[int, int]], segs: list[tuple[int, int, int]]) -> list[tuple[int, int]]:
    resolved = resolve_overlaps(segs)
    base = sorted(speech) if speech else [(a, b) for _, a, b in resolved]
    out = []
    for ra, rb in base:
        inside = [x for x in resolved if x[2] > ra and x[1] < rb]
        cuts, last = [], ra
        for p, q in zip(inside, inside[1:]):
            if p[0] == q[0]:
                continue
            cut = min(max((p[2] + q[1]) // 2, ra), rb)
            if cut - last >= MIN_TURN_MS and rb - cut >= MIN_TURN_MS:
                cuts.append(cut); last = cut
        bounds = [ra] + cuts + [rb]
        for a, b in zip(bounds, bounds[1:]):
            if b - a <= UNIT_MAX_MS:
                out.append((a, b)); continue
            t = a
            while t < b:
                e = min(b, t + UNIT_WINDOW_MS)
                if b - e < UNIT_WINDOW_MS // 2:
                    e = b
                out.append((t, e)); t = e
    return out


def label_units(units, labels, segs) -> list[tuple[int, int, int]]:
    if labels is not None and len(labels) == len(units):
        return [(int(l), a, b) for l, (a, b) in zip(labels, units)]
    resolved = resolve_overlaps(segs); out = []
    for a, b in units:
        best = max(resolved, key=lambda x: min(b, x[2]) - max(a, x[1]), default=None)
        if best and min(b, best[2]) - max(a, best[1]) > 0:
            spk = best[0]
        elif resolved:
            spk = min(resolved, key=lambda x: max(x[1] - b, a - x[2]))[0]
        else:
            spk = 0
        out.append((spk, a, b))
    return out


def cluster_speakers(E, num_speakers: int = 0, min_silhouette: float = 0.10, max_speakers: int = 6):
    """Порт SpeakerClustering.cluster (без ограничения maxUnits — на стенде записи короткие)."""
    import numpy as np
    n = len(E)
    if n == 0:
        return []
    E = np.asarray(E, dtype=np.float64); E = E / np.maximum(np.linalg.norm(E, axis=1, keepdims=True), 1e-9)
    if n == 1:
        return [0]
    S = E @ E.T
    k_cap = min(max(max_speakers, num_speakers), n)
    parts = _agglomerate(S, k_cap)
    if num_speakers > 0:
        k = min(num_speakers, n)
    elif n < 3:
        k = 1
    else:
        sils = {k: _silhouette(S, parts[k]) for k in range(2, min(max_speakers, n - 1) + 1) if k in parts}
        k = max(sils, key=sils.get) if sils else 1
        if not sils or sils[k] < min_silhouette:
            k = 1
    lab = list(parts[k])
    if num_speakers <= 0:
        while True:
            groups = sorted(set(lab))
            if len(groups) < 2:
                break
            C = {g: E[[i for i, l in enumerate(lab) if l == g]].sum(0) for g in groups}
            C = {g: v / np.linalg.norm(v) for g, v in C.items()}
            best, pair = SAME_SPEAKER_SIMILARITY, None
            for i in range(len(groups)):
                for j in range(i + 1, len(groups)):
                    sim = float(C[groups[i]] @ C[groups[j]])
                    if sim > best:
                        best, pair = sim, (groups[i], groups[j])
            if pair is None:
                break
            lab = [pair[0] if l == pair[1] else l for l in lab]
    ids = {}
    return [ids.setdefault(l, len(ids)) for l in lab]


def _agglomerate(S, max_k):
    import numpy as np
    m = len(S); s = S.copy(); size = [1] * m; active = [True] * m; owner = list(range(m)); res = {}

    def snap():
        ids = {}
        return [ids.setdefault(o, len(ids)) for o in owner]
    count = m
    if count <= max_k:
        res[count] = snap()
    while count > 1:
        best, ba, bb = -np.inf, -1, -1
        for a in range(m):
            if not active[a]:
                continue
            for b in range(a + 1, m):
                if active[b] and s[a][b] > best:
                    best, ba, bb = s[a][b], a, b
        for c in range(m):
            if active[c] and c not in (ba, bb):
                v = (s[ba][c] * size[ba] + s[bb][c] * size[bb]) / (size[ba] + size[bb]); s[ba][c] = s[c][ba] = v
        size[ba] += size[bb]; active[bb] = False
        owner = [ba if o == bb else o for o in owner]; count -= 1
        if count <= max_k:
            res[count] = snap()
    return res


def _silhouette(S, lab):
    groups = set(lab)
    if len(groups) < 2:
        return -1.0
    tot = 0.0
    for i in range(len(lab)):
        own = [j for j in range(len(lab)) if j != i and lab[j] == lab[i]]
        if not own:
            continue
        a = sum(1 - S[i][j] for j in own) / len(own)
        b = min(sum(1 - S[i][j] for j in range(len(lab)) if lab[j] == g) / lab.count(g) for g in groups if g != lab[i])
        tot += (b - a) / max(a, b)
    return tot / len(lab)
