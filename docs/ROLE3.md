# Роль 3 — диаризация, пайплайн и мост

Состояние на 2026-11-07 и вводная для нового участника. Документ можно читать сверху вниз: что это за зона,
как её запустить, как она устроена, что готово, что осталось и на какие грабли уже наступили.

## 1. Зона ответственности

| Папка (`src/android/…`) | Что внутри |
|---|---|
| `ml/diarization/` | VAD (`SileroVoiceActivityDetector`) и диаризация (`SherpaDiarizer`) на sherpa-onnx |
| `pipeline/` | оркестрация этапов (`DefaultTranscriptionPipeline`), нарезка для ASR (`SegmentPlanner`), склейка реплик (`UtteranceMerger`) |
| `app/…/bridge/` | `StenoPlugin` — мост к Vue, `JsMapping` — перевод в JSON моста |
| `app/…/processing/` | `TranscriptionController` (одна обработка за раз), `TranscriptionService` (Foreground Service), `TimingsWriter` |
| `app/…/storage/` | `RecordingFiles` — импорт и запись файлов |
| `app/src/debug/` | `FakePipeline`, `FakeSpeechRecognizer` — заглушки только для debug |
| `/config/pipeline.default.json` | пороги по умолчанию (дублируют `PipelineConfig`) |
| `src/eval/` (вместе с ролью 4) | Python-стенд: подбор порогов, DER, ошибка границ |

Общие договоры: `core/model` (меняется с согласия всех), контракт моста `src/web/src/native/steno.ts` ↔
`StenoPlugin.kt` (одним PR, ревью ролей 1 и 3).

## 2. Как запустить (первый день)

1. **Android Studio** (Standard setup) и **JDK 21**: `brew install openjdk@21`, затем в Studio
   *Settings → Build Tools → Gradle → Gradle JDK* = 21. В терминале: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
2. **Node.js LTS** (26) — для Vue.
3. Нативные библиотеки и модели:
   ```bash
   scripts/fetch_sherpa.sh
   scripts/download_models.sh
   ```
4. Vue в Android-проект (после каждого изменения в `src/web`):
   ```bash
   cd src/web && npm ci && npm run build && npx cap sync android
   ```
5. Установка на эмулятор или телефон — нужный режим выбирается флагами:

   | Что проверяем | Команда (из `src/android`) |
   |---|---|
   | Мост и интерфейс, без моделей | `./gradlew installDebug -Psteno.fakePipeline=true` |
   | **Настоящие VAD и диаризация** (основной режим роли 3, пока нет ASR) | `./gradlew installDebug -Psteno.fakePipeline=false -Psteno.fakeAsr=true` |
   | Всё по-настоящему (когда роль 2 сделает ASR) | `./gradlew installDebug -Psteno.fakePipeline=false` |

6. Логи: `adb logcat -s Pipeline StenoPlugin ModelManager AndroidRuntime:E`.
   Строки `vad: regions=…` и `diarization: segments=… speakers=…` показывают, что нашли VAD и диаризация.
7. Python-стенд:
   ```bash
   cd src/eval && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
   .venv/bin/python run_pipeline.py data/*.wav --diarization-only -o out
   .venv/bin/python eval_der.py --data data --out out
   ```
8. Тестовые записи с известным числом спикеров (официальные sherpa-onnx, без разметки): `0-four-speakers-zh.wav`,
   `2-two-speakers-en.wav` из релиза
   [speaker-segmentation-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models).
   На эмулятор: `adb push файл.wav /sdcard/Download/`, затем «Импорт файла» → Downloads.

**Правило:** не запускать две сборки одновременно (терминал + Studio + агент) — портится кэш Gradle.

## 3. Как устроено

```
аудио ─▶ DECODE (16 кГц моно) ─▶ PREPROCESS (DC, нормализация)
          ├─▶ VAD (Silero) ───────────────▶ участки речи ──────────────┐
          └─▶ DIARIZATION (pyannote + CAM++) ─▶ отрезки (спикер, время) ┤
                                                                       ▼
   SegmentPlanner: разрешить пересечения → выбросить < 200 мс → резать > 20 с по паузам VAD → паддинг 150 мс
                                                                       ▼
   ASR по каждому куску (роль 2)  →  UtteranceMerger: сортировка, выброс пустых,
   склейка одного спикера при паузе < 700 мс, нумерация speaker_1… по первому появлению
                                                                       ▼
   LLM (роль 4, сбой не валит обработку)  →  SAVE (Room)  →  событие completed во Vue
```

**Диаризация в sherpa-onnx — четыре шага.**
1. Сегментация: окно 10 с скользит с шагом `diarizationWindowShift` × 10 с (0.1 → 1 с) и отмечает до трёх «местных» голосов.
2. Отпечатки голоса (CAM++ VoxCeleb) — вектор тембра для каждого куска.
3. Кластеризация: `numSpeakers > 0` — ровно столько групп; 0 («Авто») — решает `clusterThreshold`: больше порог → меньше спикеров.
4. Чистка: отрезки < 0,2 с выбрасываются, паузы одного спикера < 0,5 с склеиваются.

**Как обработка проходит через приложение.** Vue `processing`-стор → `Steno.process()` → `StenoPlugin`
(параметры = значения по умолчанию из `PipelineConfig` + выбор пользователя) → `TranscriptionController.start()` →
`TranscriptionService` (уведомление) + корутина с пайплайном → события `progress/completed/failed` → Vue.
Каждый движок создаётся фабрикой перед этапом и закрывается сразу после него: в памяти одна модель за раз.

## 4. Что сделано и где проверено

| Что | Файлы | Unit-тесты | Проверено на эмуляторе |
|---|---|---|---|
| Оркестрация, тайминги, RTF, лог итогов VAD/диаризации | `DefaultTranscriptionPipeline` | 4 | ✅ |
| Нарезка для ASR + разрешение пересечений спикеров | `SegmentPlanner` | 9 | ✅ |
| Склейка реплик | `UtteranceMerger` | 6 | ✅ |
| VAD на Silero | `SileroVoiceActivityDetector` | — (JNI) | ✅ 8 участков речи на тестовой записи |
| Диаризация на sherpa-onnx | `SherpaDiarizer`, `DiarizationMapping` | 4 | ✅ 4 спикера на записи с 4 спикерами |
| Модель отпечатков: CAM++ VoxCeleb вместо китайской | `download_models.sh`, `manifest.json` | — | ✅ |
| Мост: все методы контракта | `StenoPlugin`, `JsMapping` | — | ✅ импорт, запись, обработка, расшифровка, плеер, перезапуск |
| Foreground Service (`mediaProcessing` / `dataSync`) | `TranscriptionService` | — | ✅ Android API 37 |
| Debug-заглушки ASR и пайплайна | `app/src/debug/*` | — | ✅, в release их нет (проверено по dex) |
| Честная отмена во время диаризации (текст в UI) | `ProcessingPage.vue`, стор `processing` | 1 (Vue) | ✅ в браузере |
| Шаг окна сегментации как параметр | `PipelineConfig.diarizationWindowShift` (= 0.1) | — | ✅ |
| Стенд совпадает с приложением (предобработка, пересечения), режим `--diarization-only` | `src/eval/common.py`, `run_pipeline.py` | — | ✅ на ПК |

Все 53 Kotlin-теста и 16 тестов Vue проходят. Замеры скорости и порогов — в [TESTS.md](TESTS.md), решения — в [DECISIONS.md](DECISIONS.md).

## 5. Что осталось (по приоритету)

| # | Задача | Почему важно | Готово, когда |
|---|---|---|---|
| 1 | **Свои русские записи с разметкой**: 3–5 диалогов по 1–3 мин, 2–4 человека; разметить «кто когда говорил» (Audacity → метки → JSON). Нужен скрипт конвертации меток Audacity в формат `<имя>.ref.json` | без разметки нельзя посчитать DER — пороги подбираются вслепую, а кластеризация на чужих записях нестабильна | `src/eval/data/` с парами `.wav` + `.ref.json`, `eval_der.py` выдаёт числа |
| 2 | **Подбор порогов по DER**: `clusterThreshold`, `diarizationWindowShift`, `mergeGapMs`, `segmentPaddingMs`, `minUtteranceMs` | диаризация — 25 баллов, метки времени — 10 | лучшие значения в `PipelineConfig` **и** `config/pipeline.default.json`, таблица в `TESTS.md` |
| 3 | **Скорость на настоящем телефоне** | эмулятор (1 ядро) в ~17 раз медленнее ПК — его цифрам верить нельзя; RTF — 10 баллов | RTF по этапам на 1–2 телефонах в `TESTS.md` |
| 4 | **Границы реплик по таймштампам слов** из ASR (`RecognizedSegment.tokens`) | точнее `start_ms`/`end_ms` | после ASR роли 2; тест в `pipeline/src/test` |
| 5 | **Проверить на устройстве**: экспорт JSON/TXT/SRT, «Поделиться», запись ~1 ч, разрешения на Android 10/13/15 | пока проверено только в браузере / не проверено | пункты чек-листа в `TESTS.md` отмечены |
| 6 | **`getProcessingState()` в мосту** — если экран пересоздастся посреди обработки, Vue не знает, что она идёт | UX | с ролью 1, одним PR `steno.ts` + `StenoPlugin` |
| 7 | **Тест синхронизации** `config/pipeline.default.json` ↔ `PipelineConfig()` | пороги легко поменять в одном месте и забыть в другом | unit-тест падает при расхождении |
| 8 | **Пометка об ошибке LLM** вместо молчаливого пропуска | пустая вкладка «Протокол» без объяснения | с ролью 4 |
| 9 | *(если останется время)* мягкая мгновенная отмена диаризации в фоне | удобство | см. DECISIONS.md, вариант Б |

Блокер вне зоны: **ASR (роль 2)** — `GigaAmSpeechRecognizer` пока заглушка. До него используйте `-Psteno.fakeAsr=true`.

## 6. Грабли, на которые уже наступили

| Симптом | Причина | Как избежать |
|---|---|---|
| Краш `JNI DETECTED ERROR … NoSuchMethodError … invoke(IIJ)` | sherpa ищет у callback метод `invoke(int,int,long) → Integer`, а Kotlin 2.x компилирует лямбды иначе | в sherpa передавать **класс**, а не лямбду (см. `ProgressCallback`) |
| «Подготовка моделей» висит на 0% | плагин Capacitor на телефоне — Proxy; отданный через `Promise`, он ломается на `.then` | плагин наружу только через `wrapNativePlugin` в `src/web/src/native/index.ts` |
| Краш `Starting FGS with type none` | `ServiceCompat.startForeground` выкидывает тип `mediaProcessing` | вызывать `startForeground` напрямую (так и сделано) |
| Сборка падает на `abc_*`/`.flat not found` | две сборки одновременно испортили кэш Gradle | одна сборка за раз; лечение: `./gradlew --stop`, удалить `~/.gradle/caches/build-cache-1` и `transforms`, `./gradlew clean` |
| Диаризация на эмуляторе 100 с на минуту записи | у эмулятора 1 ядро | скорость мерить на телефоне; эмулятору можно дать 4 ядра в Device Manager |
| «Отмена» срабатывает с задержкой | sherpa игнорирует сигнал из callback | ограничение; интерфейс об этом предупреждает |
| Число спикеров скачет от параметров | кластеризация нестабильна на коротких записях | выбирать параметры только по DER на своей разметке |
| Unit-тесты зелёные, а на телефоне краш | unit-тесты не загружают `.so`, JNI и Android-сервисы | всё в `ml/diarization` и `app/` проверять на эмуляторе/телефоне |

## 7. Шпаргалка

```bash
# все тесты Kotlin
cd src/android && ./gradlew testDebugUnitTest :core:model:test
# только твои модули
./gradlew :pipeline:testDebugUnitTest :ml:diarization:testDebugUnitTest
# тесты и линтер Vue
cd src/web && npm run lint && npm run test
# стенд: перебор порога
cd src/eval && for t in 0.5 0.6 0.7 0.8; do .venv/bin/python run_pipeline.py data/*.wav --diarization-only --cluster-threshold $t -o out_$t; .venv/bin/python eval_der.py --data data --out out_$t; done
```
