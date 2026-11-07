# Отчёт о создании каркаса

Дата: 2026-11-06. Каркас собирается и проходит тесты; ML-движки — заглушки с `TODO(роль N)`.

## Важно до начала работы

1. **Шаблона организаторов в папке нет** (`hacathon_template` не клонирован, `README.md` шаблона отсутствовал) —
   использована раскладка по умолчанию из промпта. Если шаблон требует другую — перенесите папки и поправьте пути
   в `scripts/*.sh`, `.github/workflows/android.yml`, `capacitor.config.ts`.
2. **Git:** папка проекта сейчас лежит внутри git-репозитория, корень которого — домашний каталог пользователя.
   Ничего не коммитилось. Создайте отдельный репозиторий команды (из шаблона) и перенесите туда файлы.
3. В корне остались чужие для каркаса файлы IntelliJ (`HackthonMidphi.iml`, пустой `src/main.kt`) — не трогал, их можно удалить.
4. **JDK:** Gradle 8.14.3 (wrapper Capacitor 8) не запускается на JDK 25 (JBR Android Studio 2026.2).
   В Android Studio: *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* = 21 (скачать там же).
   Из терминала проверено на JDK 24 (Homebrew). CI использует Temurin 21.
5. **Node:** локально Node 23.10 (не LTS) с npm 11.2 — у него баг установки (`edgesOut`); ставил через npm 11.12.
   Рекомендуется Node 26 LTS (используется в CI).

## Дерево (до 3 уровней)

```text
├─ .github/  CODEOWNERS, workflows/android.yml
├─ README.md
├─ artifacts/README.md
├─ config/pipeline.default.json
├─ docs/  AGENT_PROMPT, ARCHITECTURE, BACKLOG, CONTRIBUTING, DECISIONS, INSTALL, LIMITATIONS,
│         MODELS, TEAM, TESTS, WEB_DEV, SETUP_REPORT
├─ scripts/  build_release.sh, download_models.sh, fetch_sherpa.sh
└─ src/
   ├─ android/
   │  ├─ app/            StenoPlugin, TranscriptionController/Service, RecordingFiles, DI;
   │  │                  src/debug: FakePipeline; src/release: манифест без INTERNET
   │  ├─ core/           model/ audio/ data/
   │  ├─ ml/             runtime/ sherpa/ asr/ diarization/ llm/
   │  ├─ pipeline/
   │  ├─ gradle/libs.versions.toml
   │  └─ build.gradle, settings.gradle, variables.gradle, gradle.properties
   ├─ eval/              run_pipeline, eval_wer, eval_der, eval_timestamps, report, common, README
   └─ web/
      ├─ src/            pages/ components/ stores/ native/ i18n/ theme/ utils/ router/
      ├─ scripts/check-no-mock.mjs
      └─ package.json, vite.config.ts, capacitor.config.ts, eslint.config.js, tsconfig*.json
```

## Что реализовано полностью, а что — заглушка

| Часть | Статус | Владелец |
|---|---|---|
| `core/model`: контракты из раздела 6, `ErrorCode`, `TranscriptJson` | ✅ полностью, тест на примере ТЗ | все |
| `core/audio`: `MediaCodecAudioDecoder` (mp3/m4a/ogg/wav/flac/amr → 16 кГц моно), `Resampler` (оконный sinc), `WavWriter/WavReader`, `AudioRecorder` (WAV 16 кГц моно), `DefaultAudioPreprocessor` | ✅ полностью; шумоподавление — TODO | роль 2 |
| `core/data`: Room (3 таблицы, схема экспортируется), `RoomTranscriptRepository`, `TranscriptExporter` JSON/TXT/SRT, DataStore | ✅ полностью | роль 4 |
| `ml/runtime`: `ModelManager` + `ModelInstaller` (потоковое копирование, прогресс, маркер, `NO_SPACE`/`MODELS_MISSING`) | ✅ полностью | роль 2 |
| `ml/sherpa`: `.so` v2.0.1 + официальные Kotlin API, `SherpaRuntime` | ✅ подключено (скриптом) | роль 2 |
| `ml/asr`: `GigaAmSpeechRecognizer` | ⏳ заглушка `NotImplementedError("TODO(роль 2)…")`, проверка путей моделей есть | роль 2 |
| `ml/diarization`: `SileroVoiceActivityDetector`, `SherpaDiarizer` | ⏳ заглушки (роль 3) | роль 3 |
| `ml/llm`: `LiteRtLmEngine` | ⏳ заглушка (роль 4) | роль 4 |
| `ml/llm`: `LlmTranscriptPostProcessor` (промпты, разбор ролей, защита правки) | ✅ полностью, тесты | роль 4 |
| `pipeline`: `DefaultTranscriptionPipeline`, `SegmentPlanner`, `UtteranceMerger` | ✅ полностью, тесты | роль 3 |
| `app`: `StenoPlugin` (все методы контракта), контроллер, Foreground Service, импорт/экспорт/шаринг через системные диалоги, разрешения, Koin | ✅ полностью; на телефоне не проверено | роль 3 |
| `app/src/debug/FakePipeline` (настоящий DECODE + имитация остального) | ✅ только debug | роль 3 |
| Vue: 6 страниц, 5 компонентов, 5 сторов, мок, тема, тексты | ✅ полностью, проверено в браузере | роль 1 |
| Python-стенд | ✅ скрипты, `--help`; прогон на моделях не делался | роль 4 |
| Документация, CODEOWNERS, CI, `.gitignore` | ✅ | роль 4 |

## Проверки, которые прошли

| Команда | Результат |
|---|---|
| `npm run lint` / `npm run test` / `npm run build` (src/web) | ✅ / 13 тестов ✅ / ✅ |
| `npm run check:no-mock` | ✅ мока в dist нет |
| `npx cap sync android` | ✅ |
| `./gradlew assembleDebug testDebugUnitTest :core:model:test lint assembleRelease` | ✅ 44 Kotlin-теста, lint без ошибок в наших модулях |
| `aapt2 dump permissions app-release.apk` | ✅ INTERNET нет |
| поиск `FakePipeline` в dex release | ✅ отсутствует (в debug есть) |
| `npm run dev` в браузере | ✅ все страницы, обработка с прогрессом, плеер, перемотка, подсветка, меню, запись; сетевые запросы — только localhost |
| `python <скрипт>.py --help` для всех 5 скриптов | ✅ |

**Не проверено** (нужен телефон / модели): запуск на arm64-телефоне, импорт через системный диалог,
запись с микрофона, декодер на реальных файлах, экспорт через SAF, авиарежим, сохранение в Room после перезапуска,
прогон `run_pipeline.py` на моделях. Debug-APK — 34 МБ (без моделей), release — 32 МБ.

## Версии ключевых библиотек

| Библиотека | Версия | Где проверено |
|---|---|---|
| Capacitor (core/cli/android) | 8.6.0 | npm registry |
| AGP / compileSdk / targetSdk / Java | 8.13.0 / 36 / 36 / 21 | `@capacitor/android` 8.6.0 (`build.gradle`, `capacitor.build.gradle`) |
| Gradle | 8.14.3 | wrapper Capacitor (lint предлагает 8.14.6) |
| Kotlin | 2.4.21 | Maven Central |
| KSP | 2.3.13 | Maven Central |
| Room | 2.8.5 | Google Maven |
| Koin | 4.2.2 | Maven Central (4.3.0 — только Beta) |
| kotlinx-coroutines / serialization | 1.11.0 / 1.12.0 | Maven Central |
| DataStore | 1.3.0 | Google Maven |
| androidx.core | 1.17.0 | Google Maven (1.19.1 требует compileSdk 37 + AGP 9.1) |
| lifecycle-service / documentfile | 2.11.0 / 1.1.0 | Google Maven |
| LiteRT-LM | 0.19.1 (`com.google.ai.edge.litertlm:litertlm-android`) | Google Maven, ещё не подключён |
| sherpa-onnx | 2.0.1 (Android tar, sha256 из GitHub API) | GitHub Releases |
| Vue / Vite / TypeScript / vue-tsc | 3.5.45 / 8.3.6 / 6.0.3 / 3.3.14 | npm (TS 7 не поддерживает typescript-eslint, нужен < 6.1) |
| Ionic Vue / vue-router / Pinia | 9.0.10 / 5.4.0 / 4.1.0 | npm |
| Vitest / ESLint / Prettier | 5.1.0 / 10.14.0 / 3.9.10 | npm |
| Python: sherpa-onnx / numpy / jiwer / pyannote.core / pyannote.metrics / soundfile | 2.0.1 / 2.5.4 / 4.0.0 / 6.1.0 / 4.2.0 / 0.14.0 | PyPI |
| GitHub Actions: checkout / setup-node / setup-java / gradle / upload-artifact | v7 / v7 / v6 / v6 / v7 | GitHub API (latest releases) |

## Команды

```bash
# Нативные библиотеки и модели (разработчикам, нужен интернет)
scripts/fetch_sherpa.sh
scripts/download_models.sh            # ASR, VAD, диаризация (~210 МБ)
LLM_URL=<ссылка> scripts/download_models.sh --with-llm

# Vue
cd src/web && npm ci && npm run lint && npm run test && npm run build && npx cap sync android

# Android (JDK 21)
cd src/android && ./gradlew assembleDebug testDebugUnitTest :core:model:test
./gradlew installDebug -Psteno.fakePipeline=true   # debug с FakePipeline даже при наличии моделей

# Релиз
scripts/build_release.sh

# Стенд
cd src/eval && pip install -r requirements.txt && python run_pipeline.py data/*.wav -o out && python report.py --data data --out out
```

### Коротко для Vue-разработчика

- **Браузер:** `cd src/web && npm install && npm run dev` → http://localhost:5173 (мок, телефон и модели не нужны;
  ошибки и скорость мока — в *Настройки → Отладка мока*).
- **Телефон с live reload:** `npx cap run android -l --external` (одна Wi-Fi-сеть).
- **Телефон со сборкой:** `npm run build && npx cap sync android` → Run в Android Studio. Отладка — `chrome://inspect`.

Подробно — `docs/WEB_DEV.md`.

## Отступления от промпта и открытые вопросы

- **Расширения контракта моста** (нужны страницам раздела 7): `getModelsInfo()`, событие `modelsProgress`,
  код ошибки `NO_SPACE`, необязательные `numThreads`/`clusterThreshold`/`vadThreshold` в `PipelineOptions`,
  поле `vadThreshold` в `PipelineConfig`. Базовые методы и типы не менялись.
- **JDK 21 вместо 17** — требование Capacitor 8.
- **Kotlin 2.4 предупреждает о старом Gradle 8.14** — поднимать вместе с переходом Capacitor на AGP 9.
- **minSdk 26** задан в `variables.gradle` (Capacitor по умолчанию 24).
- Kotlin-исходники `app` лежат в `src/main/java` (как у Capacitor), модулей — в `src/main/kotlin`.
- `vue-router` 5 не объявлен зависимостью `@ionic/vue-router` 9 — работает (проверено в браузере), но следите за предупреждениями.

**Непроверенные TODO:**
- имена файлов внутри архива GigaAM v3 (`encoder/decoder/joiner(.int8).onnx`, `tokens.txt`) — скрипт выбирает
  int8 или fp32 и печатает содержимое архива; сверить при первом запуске;
- sha256 для сегментации и CAM++ (GitHub не публикует digest) — вписать после первой загрузки;
- URL LLM-модели `.litertlm` и точные имена классов Kotlin API LiteRT-LM;
- Python API sherpa-onnx в `run_pipeline.py` (VAD, диаризация, `from_transducer`) — сверить с `python-api-examples`;
- вопрос организаторам о нормализации WER (`docs/TESTS.md`).

## Первые три задачи по ролям

**Роль 1 — Vue**
1. Пройти все сценарии в `npm run dev` и на телефоне (`npx cap run android -l --external`), собрать список UX-правок.
2. Inline-редактирование реплики и выбор спикера без alert; автоскролл/подсветка на длинных записях.
3. Иконка приложения, splash, пустые и ошибочные состояния по всем кодам ошибок.

**Роль 2 — ASR, устройство и релиз**
1. `scripts/download_models.sh`, зафиксировать sha256, проверить `manifest.json` и первый запуск на телефоне.
2. Реализовать `GigaAmSpeechRecognizer` через `OfflineRecognizer` (nemo_transducer), замерить RTF.
3. Проверить декодер и запись на 2–3 телефонах (mp3/m4a/ogg/wav/amr), настроить подпись release.

**Роль 3 — диаризация, пайплайн и мост**
1. Реализовать `SileroVoiceActivityDetector` и `SherpaDiarizer`.
2. Прогнать debug-APK с FakePipeline: импорт → обработка → расшифровка → экспорт → перезапуск.
3. Подобрать `clusterThreshold`/`mergeGapMs` на стенде по DER и ошибке границ.

**Роль 4 — LLM, данные, оценка, документация**
1. Записать/разметить 3–5 своих «диалогов котиков» в `src/eval/data`, прогнать `run_pipeline.py` + `report.py`.
2. Выбрать LLM по размеру/качеству, реализовать `LiteRtLmEngine`.
3. Задать организаторам вопрос о нормализации WER, вписать ответ в `docs/TESTS.md`, заполнить `docs/TEAM.md` логинами.
