#!/usr/bin/env python3
"""Прогон того же пайплайна, что в приложении, на ПК (те же ONNX-модели и параметры).

Декодирование → VAD → диаризация → ASR по сегментам говорящих → склейка.
Результат — тот же строгий JSON, что экспортирует приложение, плюс <имя>.timings.json для RTF.

Пример:
  python run_pipeline.py data/cats_1.wav --models ../android/app/src/main/assets/models -o out/
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from common import (SpeakerText, cluster_speakers, label_units, load_config, merge_utterances, plan_segments,
                    preprocess, resolve_overlaps, save_transcript, speech_units)

SR = 16_000


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("audio", nargs="+", help="аудиофайлы (wav/flac/ogg/mp3; m4a сначала перевести ffmpeg в wav)")
    p.add_argument("--models", default=str(Path(__file__).resolve().parents[1] / "android/app/src/main/assets/models"),
                   help="каталог моделей с той же раскладкой, что assets/models (asr/, vad/, diarization/)")
    p.add_argument("--config", default=None, help="JSON с параметрами (по умолчанию config/pipeline.default.json)")
    p.add_argument("-o", "--out", default="out", help="каталог для результатов")
    p.add_argument("--num-speakers", type=int, default=None, help="переопределить numSpeakers (0 = авто)")
    p.add_argument("--cluster-threshold", type=float, default=None, help="переопределить clusterThreshold")
    p.add_argument("--threads", type=int, default=None, help="переопределить numThreads")
    p.add_argument("--window-shift", type=float, default=None,
                   help="переопределить diarizationWindowShift (шаг окна сегментации, доля 10-секундного окна)")
    p.add_argument("--min-on", type=float, default=0.2, help="диаризация: отрезки короче (с) выбрасываются (как в приложении)")
    p.add_argument("--min-off", type=float, default=0.5, help="диаризация: паузы одного спикера короче (с) склеиваются")
    p.add_argument("--set", action="append", default=[], metavar="ПАРАМЕТР=ЗНАЧЕНИЕ",
                   help="переопределить любой параметр PipelineConfig, можно несколько раз: "
                        "--set mergeGapMs=400 --set minSpeakerSilhouette=0.15")
    p.add_argument("--no-regroup", action="store_true", help="без второго прохода диаризации (только sherpa)")
    p.add_argument("--diarization-only", action="store_true",
                   help="только VAD + диаризация (без ASR): <имя>.json с пустым текстом — для eval_der.py "
                        "и подбора порогов диаризации; быстро и не зависит от языка записи")
    p.add_argument("--diar-window-min", type=float, default=DIAR_WINDOW_MIN,
                   help="первый проход sherpa окнами по N минут (0 — весь файл сразу), как SherpaDiarizer")
    p.add_argument("--embedding", default=None,
                   help="другая модель отпечатков голоса (.onnx) для сравнения по DER; "
                        "по умолчанию <models>/diarization/embedding.onnx")
    return p.parse_args()


def load_audio(path: str):
    import numpy as np
    import soundfile as sf

    data, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if sr != SR:  # ресемплинг через FFT (без scipy)
        n_out = int(round(len(mono) * SR / sr))
        spec = np.fft.rfft(mono)
        keep = n_out // 2 + 1
        spec = spec[:keep] if len(spec) >= keep else np.pad(spec, (0, keep - len(spec)))
        mono = np.fft.irfft(spec, n=n_out).astype("float32") * (n_out / len(mono))
    return mono


SHORT_UNIT_MS = 1500
SHORT_UNIT_SHIFTS = [(0, 0), (100, 0), (0, 100), (100, 100), (-100, -100)]


def short_unit_embedding(ext, samples, a, b, lo, hi, duration_ms):
    """Порт SherpaDiarizer.labelUnits для коротких участков: среднее нормированных отпечатков со сдвигом краёв."""
    import numpy as np
    vs = []
    for dl, dr in SHORT_UNIT_SHIFTS:
        x0, x1 = (min(a, lo - dl), max(b, hi + dr)) if dl < 0 else (max(0, lo - dl), min(duration_ms, hi + dr))
        st = ext.create_stream(); st.accept_waveform(SR, samples[x0 * SR // 1000: x1 * SR // 1000]); st.input_finished()
        if ext.is_ready(st):
            v = np.array(ext.compute(st)); vs.append(v / max(np.linalg.norm(v), 1e-9))
    return np.mean(vs, axis=0) if vs else None


DIAR_WINDOW_MIN = 10
DIAR_WINDOW_OVERLAP_S = 30


def windowed_diarization(sd, samples, window_s: float, overlap_s: float) -> list[tuple[int, int, int]]:
    """Порт SherpaDiarizer.diarize: окна с перекрытием, в перекрытии — граница посередине.
    Номера спикеров в окнах разные (окно * 1000 + номер): общих людей находит второй проход."""
    n = len(samples)
    if window_s <= 0 or n <= window_s * SR:
        return [(r.speaker, int(r.start * 1000), int(r.end * 1000)) for r in sd.process(samples).sort_by_start_time()]
    win, step = int(window_s * SR), int((window_s - overlap_s) * SR)
    out, start, w = [], 0, 0
    while True:
        end = min(n, start + win)
        keep_from = start + (int(overlap_s * SR) // 2 if start > 0 else 0)
        keep_to = end - (int(overlap_s * SR) // 2 if end < n else 0)
        for r in sd.process(samples[start:end]).sort_by_start_time():
            a = max(start + int(r.start * SR), keep_from)
            b = min(start + int(r.end * SR), keep_to)
            if b > a:
                out.append((w * 1000 + r.speaker, a * 1000 // SR, b * 1000 // SR))
        if end >= n:
            break
        start += step
        w += 1
    return sorted(out, key=lambda x: (x[1], x[2]))


def main() -> int:
    args = parse_args()
    try:
        import numpy as np  # noqa: F401
        import sherpa_onnx
    except ImportError as e:
        print(f"Не установлены зависимости: {e}. pip install -r requirements.txt", file=sys.stderr)
        return 2

    cfg = load_config(args.config)
    if args.num_speakers is not None:
        cfg["numSpeakers"] = args.num_speakers
    if args.cluster_threshold is not None:
        cfg["clusterThreshold"] = args.cluster_threshold
    if args.threads is not None:
        cfg["numThreads"] = args.threads
    if args.window_shift is not None:
        cfg["diarizationWindowShift"] = args.window_shift
    for item in args.set:
        key, _, raw = item.partition("=")
        if key not in cfg:
            print(f"Нет такого параметра: {key}. Есть: {', '.join(cfg)}", file=sys.stderr)
            return 2
        old_v = cfg[key]
        cfg[key] = (raw.lower() in ("1", "true", "yes", "да")) if isinstance(old_v, bool) else type(old_v)(float(raw))
    m = Path(args.models)
    out_dir = Path(args.out)

    # Пути совпадают с assets/models/manifest.json.
    # TODO(роль 2/3): сверить API с sherpa-onnx/python-api-examples для версии из requirements.txt.
    vad_cfg = sherpa_onnx.VadModelConfig()
    vad_cfg.silero_vad.model = str(m / "vad/silero_vad.onnx")
    vad_cfg.silero_vad.threshold = cfg["vadThreshold"]
    # Как в SileroVoiceActivityDetector (Kotlin): иначе у sherpa свои значения по умолчанию.
    vad_cfg.silero_vad.min_silence_duration = 0.25
    vad_cfg.silero_vad.min_speech_duration = 0.25
    vad_cfg.silero_vad.max_speech_duration = 30
    vad_cfg.sample_rate = SR

    diar_cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(m / "diarization/segmentation.onnx"),
                window_shift_ratio=cfg.get("diarizationWindowShift", 0.1)),
            num_threads=cfg["numThreads"],
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=args.embedding or str(m / "diarization/embedding.onnx"), num_threads=cfg["numThreads"]),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=cfg["numSpeakers"] if cfg["numSpeakers"] > 0 else -1,
            threshold=cfg["clusterThreshold"]),
        # Как в SherpaDiarizer (Kotlin): иначе у sherpa свои значения по умолчанию и стенд расходится с приложением.
        min_duration_on=args.min_on,
        min_duration_off=args.min_off,
    )

    for audio_path in args.audio:
        name = Path(audio_path).stem
        timings: dict[str, int] = {}

        def tick(stage: str, t0: float) -> None:
            timings[stage] = int((time.perf_counter() - t0) * 1000)

        t0 = time.perf_counter()
        samples = load_audio(audio_path)
        tick("DECODE", t0)
        duration_ms = int(len(samples) * 1000 / SR)

        t0 = time.perf_counter()
        samples = preprocess(samples)  # как DefaultAudioPreprocessor в приложении
        tick("PREPROCESS", t0)

        t0 = time.perf_counter()
        vad = sherpa_onnx.VoiceActivityDetector(vad_cfg, buffer_size_in_seconds=max(60, duration_ms // 1000 + 10))
        window = vad_cfg.silero_vad.window_size
        speech: list[tuple[int, int]] = []
        for i in range(0, len(samples), window):
            vad.accept_waveform(samples[i:i + window])
            while not vad.empty():
                seg = vad.front
                speech.append((seg.start * 1000 // SR, (seg.start + len(seg.samples)) * 1000 // SR))
                vad.pop()
        vad.flush()
        while not vad.empty():
            seg = vad.front
            speech.append((seg.start * 1000 // SR, (seg.start + len(seg.samples)) * 1000 // SR))
            vad.pop()
        del vad
        tick("VAD", t0)

        t0 = time.perf_counter()
        sd = sherpa_onnx.OfflineSpeakerDiarization(diar_cfg)
        diar = windowed_diarization(sd, samples, args.diar_window_min * 60, DIAR_WINDOW_OVERLAP_S)
        del sd
        # Второй проход (как в приложении): участки речи VAD → отпечатки голоса → группировка.
        units = speech_units(speech, diar)
        labels = None
        if cfg.get("regroupSpeakers", True) and not args.no_regroup and len(units) >= 2:
            ext = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model=args.embedding or str(m / "diarization/embedding.onnx"), num_threads=cfg["numThreads"]))
            pad = cfg.get("embeddingPaddingMs", 200); E = []
            for i, (a, b) in enumerate(units):
                lo = min(max(a - pad, units[i - 1][1] if i else 0), a)
                hi = max(min(b + pad, units[i + 1][0] if i + 1 < len(units) else duration_ms), b)
                if b - a < SHORT_UNIT_MS:  # как SherpaDiarizer.labelUnits: среднее нескольких отпечатков
                    v = short_unit_embedding(ext, samples, a, b, lo, hi, duration_ms)
                    if v is not None:
                        E.append(v); continue
                st = ext.create_stream(); st.accept_waveform(SR, samples[lo * SR // 1000: hi * SR // 1000]); st.input_finished()
                E.append(ext.compute(st))
            labels = cluster_speakers(E, cfg["numSpeakers"], cfg.get("minSpeakerSilhouette", 0.10))
            del ext
        if units:
            diar = label_units(units, labels, diar)
        if not diar:
            diar = [(0, a, b) for a, b in speech]
        tick("DIARIZATION", t0)

        if args.diarization_only:
            ids: dict[int, str] = {}
            items = []
            for spk, a, b in resolve_overlaps(diar):
                sid = ids.setdefault(spk, f"speaker_{len(ids) + 1}")
                items.append({"speaker_id": sid, "start_ms": a, "end_ms": b, "text": ""})
            save_transcript(items, out_dir / f"{name}.json")
            total = sum(timings.values())
            meta = {"recording_id": name, "audio_duration_ms": duration_ms, "rtf": total / max(1, duration_ms),
                    "timings_ms": timings, "platform": "pc", "mode": "diarization-only",
                    "window_shift": cfg.get("diarizationWindowShift")}
            (out_dir / f"{name}.timings.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2),
                                                          encoding="utf-8")
            print(f"{name}: спикеров={len(ids)} отрезков={len(items)} "
                  f"диаризация={timings['DIARIZATION']} мс (RTF {timings['DIARIZATION'] / max(1, duration_ms):.3f})")
            continue

        t0 = time.perf_counter()
        rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(m / "asr/encoder.int8.onnx"),
            decoder=str(m / "asr/decoder.onnx"),
            joiner=str(m / "asr/joiner.onnx"),
            tokens=str(m / "asr/tokens.txt"),
            num_threads=cfg["numThreads"],
            model_type="nemo_transducer",
            decoding_method="greedy_search",
        )
        texts = []
        for spk, a, b, aa, bb in plan_segments(diar, speech, cfg, duration_ms):
            stream = rec.create_stream()
            stream.accept_waveform(SR, samples[aa * SR // 1000: bb * SR // 1000])
            rec.decode_stream(stream)
            # Границы — по участку VAD, как в приложении (DefaultTranscriptionPipeline.speakerText).
            texts.append(SpeakerText(spk, a, b, stream.result.text))
        del rec
        tick("ASR", t0)

        t0 = time.perf_counter()
        items = merge_utterances(texts, cfg)
        tick("MERGE", t0)

        save_transcript(items, out_dir / f"{name}.json")
        total = sum(timings.values())
        meta = {"recording_id": name, "audio_duration_ms": duration_ms, "rtf": total / max(1, duration_ms),
                "timings_ms": timings, "platform": "pc"}
        (out_dir / f"{name}.timings.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"{name}: {len(items)} реплик, RTF={meta['rtf']:.3f} → {out_dir / (name + '.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
