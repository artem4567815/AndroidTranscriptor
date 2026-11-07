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
| Эмбеддинги | 3D-Speaker CAM++ zh-cn 16k common (`campplus_sv_zh-cn_16k-common`) | `diarization/embedding.onnx` | 28 МБ | fp32 | Apache-2.0 | [speaker-recongition-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models) |
| LLM | Qwen3-1.7B (Alibaba), формат LiteRT-LM | `llm/model.litertlm` | 977 МБ | веса int4 (`dynamic_wi4b32_afp32`) | Apache-2.0 | [litert-community/Qwen3-1.7B](https://huggingface.co/litert-community/Qwen3-1.7B): `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`, sha256 `2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8` |

sha256, проверенные на GitHub (2026-11-06): GigaAM v3 punct transducer —
`f9620a0099019c6afcee26525ef9ed3297fa50dd5691c1902af0c948fc1a470b`; silero_vad.onnx —
`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`. Для сегментации и CAM++ GitHub
не публикует digest — скрипт печатает фактический sha256, его нужно вписать в скрипт (TODO роль 2).
CAM++ zh-cn — `f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11` (из официального `checksum.txt`).

**Выбор модели отпечатков — по замерам, а не по языку:** сравнение 6 моделей на 4 записях (docs/TESTS.md) —
CAM++ zh-cn верна на всех, CAM++ VoxCeleb худшая. Историческая заметка: сначала была выбрана VoxCeleb из-за
«китайскости» zh-cn — это не подтвердилось. **Почему не русская модель:** моделей отпечатков голоса, обученных на русской речи, у sherpa-onnx нет.
VoxCeleb — голоса тысяч людей ~145 национальностей на разных языках, включая русский; китайские модели
(`zh-cn`, `cnceleb`) обучены на более однородных данных. Тембр голоса от языка зависит слабо, но разнообразие
голосов в обучении важно.

Альтернативы для экспериментов (подбор на стенде `src/eval`, флаг `run_pipeline.py --embedding`):
CTC-вариант GigaAM v3 punct (`sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16`, быстрее);
для отпечатков голоса — `wespeaker_en_voxceleb_resnet34_LM.onnx` (27 МБ),
`3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` (27 МБ), `nemo_en_titanet_small.onnx` (40 МБ).

## Нативный рантайм

| Компонент | Версия | Как подключён |
|---|---|---|
| sherpa-onnx | **2.0.1** | `scripts/fetch_sherpa.sh`: `libsherpa-onnx-jni.so` + `libonnxruntime.so` (arm64-v8a) в `ml/sherpa/src/main/jniLibs/` (в .gitignore), официальные Kotlin API-файлы в `ml/sherpa/src/main/kotlin/com/k2fsa/sherpa/onnx/` (коммитятся, Apache-2.0, лицензия рядом) |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` (Google Maven) | `ml/llm`, Kotlin API (`Engine`, `Conversation`), +23 МБ нативной библиотеки arm64 |

Почему не AAR sherpa-onnx: релиз публикует `sherpa-onnx-2.0.1.aar` (49 МБ) только на GitHub, не в Maven;
локальный AAR внутри library-модуля AGP не позволяет собрать (`bundleAar`), а `.so` + исходники Kotlin API
дают тот же результат, компилируются в CI без бинарников и позволяют выкинуть ненужные `.so`.

## Размер APK

Цель — < 2 ГБ (лимит GitHub Releases). Факт: APK без LLM ~0,29 ГБ, с Qwen3-1.7B — **1,3 ГБ**.
На телефоне после первого запуска: копия моделей в `filesDir/models` (~1,2 ГБ) + кэш весов LLM
в `cacheDir/litertlm` (~0,95 ГБ, создаётся при первой обработке с итогами).

## Выбор LLM (2026-11-07)

Задача LLM — итоги разговора (краткий пересказ) по расшифровке на русском, на CPU телефона, офлайн, уже после
показа расшифровки. Имена спикеров — не LLM, а правило по самопредставлениям (docs/DECISIONS.md, 2026-11-08).
Сравнение на Mac (CPU, 4 потока, LiteRT-LM 0.19.1, один и тот же промпт; подробности — docs/TESTS.md):

| Модель | Размер | Скорость | Качество протокола |
|---|---|---|---|
| **Qwen3-1.7B int4** | 977 МБ | разбор промпта ~90 ток/с, генерация ~18 ток/с | планёрка: участники, вопросы, решения и поручения со сроками верны; одна выдуманная дата |
| Qwen3.5-0.8B int8 | 965 МБ | ~2× быстрее | непригодна: зацикливается («24 одинаковых пункта»), повторяет инструкцию вместо протокола |
| Qwen3.5-0.8B `dynamic_wi8` (рекомендованный файл) | 791 МБ | — | не запускается: нужен LiteRT-LM ≥ 0.20, а последний релиз — 0.19.1 |
| Gemma 4 E2B | 2,6 ГБ | — | не подходит по размеру: APK > 2 ГБ |

Почему Qwen3 без «размышлений»: по умолчанию Qwen3 сначала «думает вслух» (сотни токенов, минуты на
телефоне), в приложении это выключено (`ThinkingConfig(enableThinking = false)`).

Память: кэш весов (`cacheDir`) обязателен. Без него LiteRT держит в ОЗУ и исходные, и переупакованные
веса — пик 4,6 ГБ против 2,1 ГБ с кэшем (Mac), а на эмуляторе с 4 ГБ приложение убивала система.
