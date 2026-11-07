# Модели

Все модели работают на устройстве. Скачиваются **только** скриптом разработчика
`scripts/download_models.sh` в `src/android/app/src/main/assets/models/` (в git не попадают) и
описаны в `assets/models/manifest.json` (путь, размер, sha256, источник, лицензия, назначение).
При первом запуске `ModelManager` копирует их в `filesDir/models`.

| Назначение | Модель | Файл(ы) в assets/models | Размер | Квантование | Лицензия | Источник |
|---|---|---|---|---|---|---|
| ASR | GigaAM v3 e2e (транcдьюсер с пунктуацией), Сбер, 2025-12-16 | `asr/encoder.int8.onnx`, `asr/decoder.onnx`, `asr/joiner.onnx`, `asr/tokens.txt` | архив 170 МБ | int8 (энкодер) | MIT | [sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models): `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2` |
| VAD | Silero VAD | `vad/silero_vad.onnx` | 0,6 МБ | fp32 | MIT | `asr-models/silero_vad.onnx` |
| Сегментация | pyannote-segmentation-3.0 | `diarization/segmentation.onnx` | ~6 МБ | int8, если есть в архиве | MIT | [speaker-segmentation-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models) |
| Эмбеддинги | 3D-Speaker CAM++ (zh-cn 16k common) | `diarization/embedding.onnx` | 28 МБ | fp32 | Apache-2.0 | [speaker-recongition-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models) |
| LLM | Gemma 4 E2B (или Qwen2.5-0.5B, если APK не влезает) | `llm/model.litertlm` | TODO | int4/int8 | см. карточку модели | TODO(роль 4): [litert-community на HF](https://huggingface.co/litert-community) |

sha256, проверенные на GitHub (2026-11-06): GigaAM v3 punct transducer —
`f9620a0099019c6afcee26525ef9ed3297fa50dd5691c1902af0c948fc1a470b`; silero_vad.onnx —
`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`. Для сегментации и CAM++ GitHub
не публикует digest — скрипт печатает фактический sha256, его нужно вписать в скрипт (TODO роль 2).

Альтернативы для экспериментов (подбор на стенде `src/eval`): CTC-вариант GigaAM v3 punct
(`sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16`, быстрее), ERes2NetV2 вместо CAM++
(`3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx`, 71 МБ, точнее, медленнее).

## Нативный рантайм

| Компонент | Версия | Как подключён |
|---|---|---|
| sherpa-onnx | **2.0.1** | `scripts/fetch_sherpa.sh`: `libsherpa-onnx-jni.so` + `libonnxruntime.so` (arm64-v8a) в `ml/sherpa/src/main/jniLibs/` (в .gitignore), официальные Kotlin API-файлы в `ml/sherpa/src/main/kotlin/com/k2fsa/sherpa/onnx/` (коммитятся, Apache-2.0, лицензия рядом) |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` (Google Maven) | объявлен в `libs.versions.toml`, подключает роль 4 |

Почему не AAR sherpa-onnx: релиз публикует `sherpa-onnx-2.0.1.aar` (49 МБ) только на GitHub, не в Maven;
локальный AAR внутри library-модуля AGP не позволяет собрать (`bundleAar`), а `.so` + исходники Kotlin API
дают тот же результат, компилируются в CI без бинарников и позволяют выкинуть ненужные `.so`.

## Размер APK

Цель — < 2 ГБ (лимит GitHub Releases). Оценка: нативные библиотеки ~27 МБ, ASR ~170 МБ,
диаризация ~35 МБ, LLM 0,5–2 ГБ. Если Gemma 4 E2B не влезает — Qwen2.5-0.5B.
