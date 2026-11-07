# Архитектура

## Слои

```
┌──────────────── Vue 3 + Ionic (src/web) ────────────────┐
│ pages → stores (Pinia) → native/index.ts → steno.ts      │  только UI, без вычислений
└───────────────────────────┬──────────────────────────────┘
                            │ Capacitor-плагин «Steno»: id, пути, настройки, прогресс, JSON
┌───────────────────────────┴──────────── app ─────────────┐
│ StenoPlugin → TranscriptionController (StateFlow) ←→ TranscriptionService (FGS) │
│ RecordingFiles · Koin · FakePipeline (только src/debug)                         │
└──────┬──────────────┬───────────────┬───────────────────────────────┘
   pipeline        core/data       core/audio
   (оркестрация,   (Room, экспорт, (декодер, ресемплер,
    склейка)        DataStore)      WAV, запись)
       │
   ml/asr · ml/diarization · ml/llm  →  ml/runtime (ModelManager)  ·  ml/sherpa (JNI)
       └──────────────── core/model (контракты, чистый Kotlin/JVM) ────────────────┘
```

## Модули Gradle (`src/android`)

| Модуль | Namespace | Что внутри | Владелец |
|---|---|---|---|
| `app` | `hackuna.matata` | оболочка Capacitor, `StenoPlugin`, `TranscriptionController`, `TranscriptionService`, DI | роль 3 |
| `core/model` | `hackuna.matata.core.model` | `Utterance`, `Transcript`, `PipelineConfig`, интерфейсы движков и фабрик, `ErrorCode` | все |
| `core/audio` | `…core.audio` | `MediaCodecAudioDecoder`, `Resampler`, `WavWriter/WavReader`, `AudioRecorder`, предобработка | роль 2 |
| `core/data` | `…core.data` | Room (`recordings`, `utterances`, `results`), `TranscriptRepository`, `TranscriptExporter`, DataStore | роль 4 |
| `ml/runtime` | `…ml.runtime` | `ModelManager` / `ModelInstaller`: копирование моделей из assets, маркер версии, пути | роль 2 |
| `ml/sherpa` | `…ml.sherpa` | `.so` + официальные Kotlin API sherpa-onnx (пакет `com.k2fsa.sherpa.onnx`) | роль 2 |
| `ml/asr` | `…ml.asr` | `GigaAmSpeechRecognizer` (заглушка) | роль 2 |
| `ml/diarization` | `…ml.diarization` | `SileroVoiceActivityDetector`, `SherpaDiarizer` (заглушки) | роль 3 |
| `ml/llm` | `…ml.llm` | `LiteRtLmEngine` (Qwen3-1.7B на LiteRT-LM), `LlmTranscriptPostProcessor` (промпт итогов, правка), `LlmMemoryGuard`, `LlmWeightCache` | роль 4 |
| `pipeline` | `…pipeline` | `DefaultTranscriptionPipeline`, `SegmentPlanner`, `UtteranceMerger` | роль 3 |

**Правила зависимостей:** `core/model` ни от чего не зависит (кроме kotlinx.serialization и coroutines);
`core/audio`, `core/data`, `ml/runtime` → только `core/model`; `ml/asr`, `ml/diarization` → `core/model`,
`ml/runtime`, `ml/sherpa`; `ml/llm` → `core/model`, `ml/runtime`; `pipeline` → `core/*`, `ml/*`;
`app` → всё. Никто не зависит от `app`. Один Koin-модуль на Gradle-модуль (`audioModule`, `dataModule`, …).

## Конвейер

| Этап | Где | Что делает |
|---|---|---|
| DECODE | `core/audio` | MediaExtractor + MediaCodec → PCM float, даунмикс, ресемплинг в 16 кГц (оконный sinc) |
| PREPROCESS | `core/audio` | удаление DC, нормализация пика; шумоподавление — TODO(роль 2) |
| VAD | `ml/diarization` | Silero VAD → участки речи (нужны для нарезки длинных реплик) |
| DIARIZATION | `ml/diarization` | pyannote + 3D-Speaker → `SpeakerSegment`; если пусто — один спикер на всю речь |
| ASR | `ml/asr` + `pipeline/SegmentPlanner` | каждый сегмент говорящего отдельно, с паддингом 150 мс; длиннее 20 с — режется по паузам VAD |
| MERGE | `pipeline/UtteranceMerger` | склейка соседних реплик одного спикера (пауза < 700 мс), выброс пустых, перенумерация `speaker_N`, `start < end`, сортировка |
| SAVE | `app/TranscriptionController` | запись в Room; статус DONE; событие `completed` — пользователь видит расшифровку |
| LLM (после показа) | `pipeline.analyze` + `ml/llm` + `pipeline/SpeakerNames` | итоги разговора свободным текстом, имена из самопредставлений («Я Лёша»), правка (`llmCorrected`); события `analysis` (идёт / готово), `saveAnalysis` не трогает правки пользователя; сбой LLM — объяснение во вкладке «Итоги»; в RTF не входит |

Каждый движок создаётся фабрикой (`VadFactory`, `DiarizerFactory`, …) прямо перед этапом и закрывается
после (`use {}`): в памяти одна модель за раз. Время этапов — в лог с тегом `Pipeline` и в `timings`
(→ RTF). Параметры — `PipelineConfig`, значения по умолчанию продублированы в `config/pipeline.default.json`.

## Мост Vue ↔ Kotlin

Контракт: `src/web/src/native/steno.ts` ↔ `app/.../bridge/StenoPlugin.kt`. Меняются только вместе, одним PR.

- Методы плагина сразу уходят в корутину (IO/Default) и отвечают `resolve` / `reject(message, code)`.
- Коды ошибок: `MODELS_MISSING`, `NO_PERMISSION`, `DECODE_FAILED`, `OUT_OF_MEMORY`, `CANCELLED`, `NO_SPACE`, `UNKNOWN`.
- События: `progress`, `completed`, `failed`, `recordingLevel`, `modelsProgress`.
- Через мост не ходит аудио; плеер играет файл по `Capacitor.convertFileSrc(audioPath)`.
- Строгий JSON собирает только Kotlin (`TranscriptJson` / `TranscriptExporter`).

## Поток данных обработки

1. Vue `processing` store → `Steno.process({id, options})`.
2. `StenoPlugin` сохраняет `PipelineConfig` в DataStore и вызывает `TranscriptionController.start`.
3. Контроллер запускает `TranscriptionService` (Foreground: `mediaProcessing` на Android 15+, иначе `dataSync`)
   и корутину с `TranscriptionPipeline.run()`.
4. События пайплайна → `SharedFlow` контроллера → `notifyListeners("progress")` → Vue.
5. `Completed` → Room → событие `completed` → Vue открывает расшифровку (`getResult`).
6. После перезапуска процесса «застрявшие» записи PROCESSING переводятся в ERROR (`resetStaleProcessing`).

## Debug и release

| | debug | release |
|---|---|---|
| INTERNET | есть (live reload) | удалён (`src/release/AndroidManifest.xml`) |
| FakePipeline | `src/debug`; режим `-Psteno.fakePipeline=auto/true/false` | физически отсутствует |
| Без моделей | `prepareModels` → ready, работает FakePipeline | `MODELS_MISSING` |
| timings.json | `Android/data/hackuna.matata/files/exports/` | нет |
| Мок во Vue | только `npm run dev` в браузере (`import.meta.env.DEV`) | вырезается Vite, проверка `npm run check:no-mock` |
