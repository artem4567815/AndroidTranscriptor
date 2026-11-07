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

from common import (SpeakerText, load_config, merge_utterances, plan_segments, preprocess, resolve_overlaps,
                    save_transcript)

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
    p.add_argument("--diarization-only", action="store_true",
                   help="только VAD + диаризация (без ASR): <имя>.json с пустым текстом — для eval_der.py "
                        "и подбора порогов диаризации; быстро и не зависит от языка записи")
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
    m = Path(args.models)
    out_dir = Path(args.out)

    # Пути совпадают с assets/models/manifest.json.
    # TODO(роль 2/3): сверить API с sherpa-onnx/python-api-examples для версии из requirements.txt.
    vad_cfg = sherpa_onnx.VadModelConfig()
    vad_cfg.silero_vad.model = str(m / "vad/silero_vad.onnx")
    vad_cfg.silero_vad.threshold = cfg["vadThreshold"]
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
        diar = [(r.speaker, int(r.start * 1000), int(r.end * 1000)) for r in sd.process(samples).sort_by_start_time()]
        del sd
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
            result = stream.result
            starts = [
                aa + int(seconds * 1000)
                for token, seconds in zip(result.tokens, result.timestamps)
                if 0 <= seconds * 1000 < bb - aa and any(c.isalnum() for c in token)
            ]
            start, end = a, b
            if starts:
                refined_start = max(a, min(starts))
                # Как в Android: метки содержат только начало токена.
                refined_end = min(b, bb, max(starts) + 200)
                if refined_start < refined_end:
                    start, end = refined_start, refined_end
            texts.append(SpeakerText(spk, start, end, result.text))
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
