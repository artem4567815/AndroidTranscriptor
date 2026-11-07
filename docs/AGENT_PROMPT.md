# Промпт для AI-агента: каркас Android-приложения «Нейро-стенографист»

Nov 6, 2026 · @Artem

## 1. Как пользоваться (для человека)

Это промпт для AI-агента, который работает прямо в папке репозитория (Claude Code, Cursor или агент в Android Studio). Он соберёт Vue-интерфейс внутри Capacitor, Gradle-проект с Kotlin-модулями, мост между ними, контракты пайплайна, Python-стенд оценки, документацию и распределение ролей. Разделы 2–12 адресованы агенту.

1. Установите Android Studio и при первом запуске выберите Standard setup: она скачает Android SDK и встроенный JDK.
2. Установите Node.js LTS (вместе с ним ставится npm) — это нужно для Vue-части.
3. Создайте репозиторий команды из шаблона организаторов: на GitHub откройте `https://github.com/grigorevmp/hacathon_template` → Use this template, затем `git clone` к себе.
4. Экспортируйте этот документ в Markdown и положите в репозиторий как `docs/AGENT_PROMPT.md`.
5. Запустите агента в корне репозитория с одной фразой: «Прочитай docs/AGENT\_PROMPT.md и выполни разделы 2–12».
6. Когда агент закончит, Vue-разработчик запускает в `src/web` команды `npm install` и `npm run dev` и сразу работает с интерфейсом в браузере. Остальные открывают в Android Studio папку `src/android`, ждут Gradle Sync, подключают телефон и нажимают Run.
7. Прочитайте итоговый отчёт агента и раздайте роли по `docs/TEAM.md`.

Интернет агенту на этапе подготовки нужен (Gradle-зависимости, модели) — это разрешено. Запрет хакатона касается только работы готового приложения.

## 2. Контекст задачи

Ты — senior Android- и ML-инженер. Команда участвует в хакатоне Nuclear IT Hack с задачей «Нейро-стенографист». Нужно Android-приложение, которое локально превращает аудиозапись разговора в текст, определяет говорящих, ставит временные метки, показывает расшифровку и экспортирует её в файл. Все этапы обработки выполняются только на телефоне.

**Обязательно:** Android OS; локальная LLM; импорт аудио из памяти устройства и запись из приложения; распознавание речи; автоматическая диаризация; временные метки реплик; показ расшифровки; экспорт в файл.

**Запрещено:** передавать аудио внешним сервисам; облачные API для транскрибации, диаризации или обработки текста; вычисления на удалённом сервере или ПК; подмена работы модели заранее подготовленной расшифровкой. Основная задача должна работать без интернета, все модели — на устройстве к моменту проверки.

**Выходной JSON (строго):**

```json
{
  "transcript": [
    { "speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!" }
  ]
}
```

**Проверка:** эксперты ставят APK, импортируют три тестовые записи «диалогов котиков», запускают обработку, экспортируют результат и считают WER и RTF относительно эталона.

| Критерий | Баллы |
| --- | --- |
| Точность транскрибации (WER) | 40 |
| Точность диаризации | 25 |
| Точность временных меток | 10 |
| Скорость обработки (RTF) | 10 |
| Работоспособность и UX | 10 |
| Защита решения | 5 |

**Выбранное решение.** Конвейер: декодирование в 16 кГц моно → предобработка → VAD → диаризация → ASR по сегментам говорящих → склейка реплик → LLM-постобработка → Room и экспорт. Модели:

- ASR: GigaAM v3 e2e (Сбер, MIT, int8, встроенная пунктуация) через sherpa-onnx;
- VAD: Silero VAD через sherpa-onnx;
- диаризация: pyannote-segmentation-3.0 + эмбеддинги 3D-Speaker (CAM++ или ERes2NetV2) через sherpa-onnx;
- LLM: Gemma 4 E2B через LiteRT-LM (Kotlin API); если APK не влезает по размеру — меньшая модель из поддерживаемых LiteRT-LM, например Qwen2.5-0.5B.

LLM используется для протокола заседания, определения ролей спикеров и опциональной правки текста. Правка LLM никогда не перезаписывает сырой текст ASR: он остаётся основой экспорта, пока команда не докажет на своём стенде, что правка снижает WER.

**Интерфейс:** Vue 3 + Ionic Vue внутри Capacitor. Vue только рисует страницы и вызывает Kotlin-плагин `Steno`; файлы, микрофон, модели, хранение и фоновая обработка — на Kotlin. Так веб-разработчик команды работает в привычном стеке с первого часа, а ML не теряет в скорости и памяти.

## 3. Цель этого шага и критерии готовности

Задача — собираемый каркас, на котором четыре человека сразу работают параллельно, не мешая друг другу. ML-движки полностью не реализуй: они стоят за интерфейсами с понятными заглушками и `TODO(роль)`. Всё, что не является ML (декодер аудио, запись, JSON-экспорт, склейка реплик, мост и Vue-интерфейс), реализуй по-настоящему и покрой unit-тестами.

Шаг готов, когда выполнено всё ниже:

- [ ] В `src/web` проходят `npm run build`, `npm run lint`, `npm run test`; затем `npx cap sync android` без ошибок.
- [ ] `./gradlew assembleDebug testDebugUnitTest` в `src/android` проходит без ошибок.
- [ ] `npm run dev` открывает интерфейс в браузере на ПК с мок-плагином: работают все страницы, имитация обработки и расшифровка. Vue-разработчику для этого не нужны ни телефон, ни модели.
- [ ] Приложение запускается на arm64-телефоне и показывает страницы из раздела 7.
- [ ] Импорт аудио через системный выбор файла работает; запись с микрофона пишет WAV 16 кГц моно.
- [ ] Декодер переводит mp3, m4a, ogg, wav в PCM 16 кГц моно float.
- [ ] В debug-сборке Kotlin-`FakePipeline` прогоняет мост целиком: события прогресса доходят до Vue, результат показывается. В release его нет физически.
- [ ] Экспорт JSON строго по формату делает Kotlin; unit-тест сверяет сериализацию с примером из раздела 2 по ключам и типам.
- [ ] Экспорт TXT и SRT работает.
- [ ] Room сохраняет записи, реплики и статус обработки; данные переживают перезапуск.
- [ ] Плеер воспроизводит запись; тап по реплике перематывает к `start_ms`, текущая реплика подсвечивается.
- [ ] Release-APK открывается и работает в авиарежиме; в манифесте release-сборки нет INTERNET.
- [ ] Созданы документы из раздела 10, `CODEOWNERS`, CI и `.gitignore`.
- [ ] Python-скрипты стенда из раздела 9 запускаются с `--help`.

## 4. Технологический стек

Интерфейс — Vue 3 внутри Capacitor, вся обработка — нативный Kotlin. Версии бери последние стабильные (без alpha/beta/rc) и проверяй по официальным источникам: Android-зависимости держи в `gradle/libs.versions.toml`, веб-зависимости — в `package.json` с точными версиями и закоммиченным `package-lock.json`. Ни одну версию не выдумывай.

| Область | Выбор |
| --- | --- |
| Язык и сборка | Kotlin 2.x (K2), JDK 17, version catalog; Gradle-файлы, которые генерирует Capacitor, остаются на Groovy, новые модули — Kotlin DSL; Node.js LTS + npm для веб-части |
| Android | Capacitor последней стабильной версии; AGP, compileSdk и targetSdk — те, что поддерживает эта версия Capacitor (файл `variables.gradle`); minSdk 26; ABI только `arm64-v8a` |
| UI | Vue 3 + TypeScript (Composition API, `<script setup>`), Vite, Ionic Vue (мобильные компоненты, иконки Ionicons), Pinia |
| Навигация | Vue Router через `@ionic/vue-router`: стек страниц и системная кнопка «назад» |
| Архитектура | Vue: страницы + Pinia-сторы. Мост: Capacitor-плагин `Steno` на Kotlin. Kotlin: контроллер обработки со StateFlow, события во Vue через `notifyListeners` |
| Асинхронность | Kotlin Coroutines и Flow |
| DI | Koin (koin-android), один Koin-модуль на Gradle-модуль |
| База | Room с KSP, DAO возвращают Flow |
| Настройки | DataStore Preferences |
| JSON | kotlinx.serialization-json |
| Плеер | HTML5 `<audio>` во Vue; путь к файлу через `Capacitor.convertFileSrc` |
| Тесты | Kotlin: JUnit, kotlinx-coroutines-test, Robolectric только при необходимости. Vue: Vitest, ESLint, Prettier |
| Речь | sherpa-onnx: ASR, VAD, диаризация (нативные .so + официальные Kotlin API-файлы) |
| LLM | интерфейс `LlmEngine`; реализация на LiteRT-LM через его Kotlin API, без своего C++ — подключает владелец роли 4 |
| Аудио Android API | `MediaExtractor` + `MediaCodec` для декодирования, `AudioRecord` для записи, без FFmpegKit |
| CI | GitHub Actions: сборка и тесты Vue, `npx cap sync android`, затем `assembleDebug` и unit-тесты Kotlin |

Запрещены любые сетевые, аналитические и облачные библиотеки: Retrofit, OkHttp, Firebase, Crashlytics, облачные AI SDK и подобные; во Vue — CDN, внешние шрифты и любые загрузки из сети.

Собственного C++ и NDK-кода в проекте нет: весь код на Kotlin. sherpa-onnx и LiteRT-LM подключаются как готовые нативные библиотеки со своими Kotlin API, их C++ внутри не трогаем.

## 5. Структура репозитория и модулей

Сначала прочитай `README.md` шаблона организаторов. Если он требует другую раскладку, следуй шаблону и опиши отличия в отчёте. По умолчанию Android-проект живёт в `src/android`, Vue-проект — в src/web, Python-стенд — в `src/eval`.

```text
<корень репозитория>
├─ README.md              ← описание, быстрый старт, ссылки на docs
├─ artifacts/             ← README со ссылкой на APK в GitHub Releases, видео, отчёты тестов
├─ config/
│  └─ pipeline.default.json   ← пороги VAD, кластеризации, склейки, число потоков
├─ docs/                  ← документация (раздел 10)
├─ scripts/
│  ├─ download_models.sh  ← скачивает модели в assets с проверкой sha256 (только для разработки)
│  ├─ fetch_sherpa.sh     ← скачивает нативные .so и Kotlin API sherpa-onnx
│  └─ build_release.sh    ← сборка Vue → cap sync → подписанный APK
├─ .github/workflows/android.yml
├─ .github/CODEOWNERS
└─ src/
   ├─ web/                ← Vue-проект: Vite + Vue 3 + TypeScript + Ionic Vue + Pinia
   │  ├─ package.json, package-lock.json, vite.config.ts, capacitor.config.ts
   │  └─ src/
   │     ├─ pages/        ← страницы (раздел 7)
   │     ├─ components/   ← карточка реплики, плеер, прогресс этапов, выбор спикера
   │     ├─ stores/       ← Pinia: recordings, processing, transcript, settings
   │     ├─ native/       ← steno.ts (контракт плагина) и steno.mock.ts (только dev)
   │     ├─ i18n/ru.ts    ← все тексты интерфейса
   │     └─ theme/        ← CSS-переменные Ionic, палитра спикеров
   ├─ android/            ← Gradle-проект (создаётся командой Capacitor)
   │  ├─ settings.gradle, build.gradle, variables.gradle, capacitor.settings.gradle
   │  ├─ gradle/libs.versions.toml
   │  ├─ app/             ← оболочка Capacitor, StenoPlugin (мост), DI, сервис обработки
   │  │  └─ src/main/assets/
   │  │     ├─ public/    ← сборка Vue (создаёт cap sync, в .gitignore)
   │  │     └─ models/{asr,vad,diarization,llm}/  ← модели (в .gitignore)
   │  ├─ core/model/      ← data-классы и интерфейсы пайплайна (чистый Kotlin/JVM)
   │  ├─ core/audio/      ← декодер, ресемплер, WAV-запись и чтение, предобработка
   │  ├─ core/data/       ← Room, репозиторий, экспорт JSON/TXT/SRT
   │  ├─ ml/runtime/      ← ModelManager: копирование моделей, проверка, пути
   │  ├─ ml/sherpa/       ← обёртка sherpa-onnx: jniLibs + Kotlin API-файлы
   │  ├─ ml/asr/          ← SpeechRecognizer на GigaAM
   │  ├─ ml/diarization/  ← VoiceActivityDetector и Diarizer
   │  ├─ ml/llm/          ← LlmEngine и TranscriptPostProcessor на LiteRT-LM
   │  └─ pipeline/        ← оркестрация, склейка реплик, прогресс, тайминги
   └─ eval/               ← Python-стенд (раздел 9)
```

Базовый пакет — `hackuna.matata`, у модулей свои namespace: `hackuna.matata.core.model`, `hackuna.matata.ml.asr` и так далее.

**Правила зависимостей между модулями:**

- `core/model` ни от чего не зависит, кроме kotlinx.serialization и coroutines-core;
- `core/audio`, `core/data`, `ml/runtime` зависят только от `core/model`;
- `ml/asr` и `ml/diarization` зависят от `core/model`, `ml/runtime`, `ml/sherpa`;
- `ml/llm` зависит от `core/model` и `ml/runtime`;
- `pipeline` зависит от `core/*` и `ml/*`;
- `app` зависит от всего; никакой модуль не зависит от `app`.

В `ml/sherpa` Kotlin API-файлы sherpa-onnx кладутся без изменения пакета `com.k2fsa.sherpa.onnx`: к нему привязаны имена JNI-функций в нативных библиотеках. Рядом положи их лицензию. Если у sherpa-onnx есть официальный Maven-артефакт или AAR, проверь, подходит ли он для library-модуля, и опиши выбор в отчёте.

**Связка Capacitor и Gradle.** В `capacitor.config.ts` укажи `webDir: 'dist'` и `android.path: '../android'`. Затем выполни `npx cap add android`: команда сама создаст `src/android` с модулем `app` и служебными файлами Capacitor. Наши модули добавляются в этот же проект, служебные файлы Capacitor (`capacitor.settings.gradle`, `capacitor.build.gradle`, `variables.gradle`, модуль плагинов Cordova) не переписывай. Сверь шаги с официальной документацией Capacitor для выбранной версии.

**Правило для Vue:** `src/web` знает о нативной части только через TypeScript-контракт `src/native/steno.ts` (раздел 6). Никаких прямых вызовов других нативных API из страниц и компонентов.

## 6. Контракты: модели данных и интерфейсы

Контракты в `core/model` — единственное место, о котором команда договаривается заранее. Создай их так, как ниже; имена полей экспорта менять нельзя.

```kotlin
// Экспорт строго по формату организаторов
@Serializable data class Utterance(
    @SerialName("speaker_id") val speakerId: String,   // "speaker_1", "speaker_2", ...
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)
@Serializable data class Transcript(val transcript: List<Utterance>)

// Внутренние типы (в строгий экспорт не попадают)
class PcmAudio(val samples: FloatArray, val sampleRate: Int = 16_000) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}
data class SpeechRegion(val startMs: Long, val endMs: Long)
data class SpeakerSegment(val speaker: Int, val startMs: Long, val endMs: Long)
data class TimedToken(val text: String, val startMs: Long)
data class RecognizedSegment(val startMs: Long, val endMs: Long, val text: String,
                             val tokens: List<TimedToken> = emptyList())

enum class Stage { DECODE, PREPROCESS, VAD, DIARIZATION, ASR, MERGE, LLM, SAVE }

@Serializable data class PipelineConfig(
    val numSpeakers: Int = 0,            // 0 = определить автоматически
    val clusterThreshold: Float = 0.5f,
    val mergeGapMs: Long = 700,          // склеивать реплики одного спикера с паузой меньше
    val maxSegmentMs: Long = 20_000,     // длиннее — резать по паузам VAD
    val segmentPaddingMs: Long = 150,
    val minUtteranceMs: Long = 200,
    val numThreads: Int = 4,
    val enableDenoise: Boolean = false,
    val enableLlmProtocol: Boolean = true,
    val enableLlmCorrection: Boolean = false,
)

data class TranscriptionResult(
    val transcript: Transcript,            // сырой ASR — идёт в строгий экспорт
    val llmCorrected: Transcript?,         // правка LLM — отдельно, только по переключателю
    val protocol: String?,                 // протокол заседания от LLM
    val speakerNames: Map<String, String>, // speaker_1 -> «Председатель»
    val timings: Map<Stage, Long>,
    val audioDurationMs: Long,
) { val rtf: Double get() = timings.values.sum().toDouble() / audioDurationMs }

sealed interface PipelineEvent {
    data class Progress(val stage: Stage, val fraction: Float) : PipelineEvent
    data class StageDone(val stage: Stage, val millis: Long) : PipelineEvent
    data class Completed(val result: TranscriptionResult) : PipelineEvent
    data class Failed(val stage: Stage, val error: Throwable) : PipelineEvent
}

// Интерфейсы. uri — String, чтобы core/model не зависел от Android
interface AudioDecoder { suspend fun decode(uri: String): PcmAudio }   // любой формат -> 16 кГц моно
interface AudioPreprocessor { fun process(audio: PcmAudio): PcmAudio }
interface VoiceActivityDetector : Closeable { fun detect(audio: PcmAudio): List<SpeechRegion> }
interface Diarizer : Closeable {
    fun diarize(audio: PcmAudio, config: PipelineConfig, onProgress: (Float) -> Unit): List<SpeakerSegment>
}
interface SpeechRecognizer : Closeable { fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment }
interface LlmEngine : Closeable { suspend fun generate(prompt: String, maxTokens: Int): String }
interface TranscriptPostProcessor {
    suspend fun protocol(t: Transcript): String
    suspend fun speakerRoles(t: Transcript): Map<String, String>
    suspend fun correct(t: Transcript): Transcript
}
interface TranscriptionPipeline { fun run(uri: String, config: PipelineConfig): Flow<PipelineEvent> }
```

**Правила для реализаций:**

- Каждый движок создаётся лениво через фабрику и закрывается сразу после своего этапа: модели загружаются по очереди (диаризация → ASR → LLM), иначе телефон упадёт по памяти.
- Пайплайн распознаёт каждый сегмент говорящего отдельно, с паддингом `segmentPaddingMs`; сегменты длиннее `maxSegmentMs` режет по паузам VAD.
- Склейка (реализуй полностью, это чистая логика с тестами): объединить соседние сегменты одного спикера с паузой меньше `mergeGapMs`; выбросить пустые сегменты короче `minUtteranceMs`; перенумеровать спикеров по первому появлению в `speaker_1`, `speaker_2`, ...; гарантировать `start_ms < end_ms` и сортировку по времени.
- Время каждого этапа пишется в лог с тегом `Pipeline` и в `timings`: из этого считается RTF для отчёта.
- Значения `PipelineConfig` по умолчанию дублируются в `config/pipeline.default.json`, чтобы Python-стенд использовал те же параметры.

**Контракт моста Vue ↔ Kotlin.** Это второй договор команды, между ролями 1 и 3. TypeScript-сторона лежит в `src/web/src/native/steno.ts`, Kotlin-сторона — класс `StenoPlugin` с `@CapacitorPlugin(name = "Steno")` в модуле `app`.

```ts
import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export interface Utterance { speaker_id: string; start_ms: number; end_ms: number; text: string }
export interface Transcript { transcript: Utterance[] }
export type Stage = 'DECODE' | 'PREPROCESS' | 'VAD' | 'DIARIZATION' | 'ASR' | 'MERGE' | 'LLM' | 'SAVE'
export type RecordingStatus = 'NEW' | 'PROCESSING' | 'DONE' | 'ERROR'
export type ExportFormat = 'json' | 'txt' | 'srt'

export interface Recording {
  id: string; title: string; durationMs: number; createdAt: number
  status: RecordingStatus; audioPath: string   // путь к файлу, который умеет играть WebView
}
export interface PipelineOptions { numSpeakers: number; enableLlmProtocol: boolean; enableLlmCorrection: boolean }
export interface TranscriptResult {
  recordingId: string; transcript: Transcript; llmCorrected?: Transcript
  protocol?: string; speakerNames: Record<string, string>; rtf: number
}

export interface StenoPlugin {
  prepareModels(): Promise<{ ready: boolean }>          // первый запуск: копирование моделей
  listRecordings(): Promise<{ items: Recording[] }>
  importAudio(): Promise<Recording>                     // системный выбор файла
  startRecording(): Promise<void>
  stopRecording(): Promise<Recording>
  deleteRecording(o: { id: string }): Promise<void>
  process(o: { id: string; options: PipelineOptions }): Promise<void>   // итог — событие completed
  cancel(): Promise<void>
  getResult(o: { id: string }): Promise<TranscriptResult>
  updateUtterance(o: { id: string; index: number; text?: string; speakerId?: string }): Promise<void>
  renameSpeaker(o: { id: string; speakerId: string; name: string }): Promise<void>
  exportResult(o: { id: string; format: ExportFormat }): Promise<{ saved: boolean }>  // системный диалог сохранения
  share(o: { id: string; format: ExportFormat }): Promise<void>
  addListener(e: 'progress', cb: (e: { id: string; stage: Stage; fraction: number }) => void): Promise<PluginListenerHandle>
  addListener(e: 'completed', cb: (e: { id: string }) => void): Promise<PluginListenerHandle>
  addListener(e: 'failed', cb: (e: { id: string; stage: Stage; code: string; message: string }) => void): Promise<PluginListenerHandle>
  addListener(e: 'recordingLevel', cb: (e: { level: number }) => void): Promise<PluginListenerHandle>
}

export const Steno = registerPlugin<StenoPlugin>('Steno')
```

**Правила моста:**

- Через мост никогда не ходит аудио и большие массивы: только id, пути, настройки, прогресс и JSON расшифровки.
- Ошибки — `call.reject` с кодом из фиксированного списка: `MODELS_MISSING`, `NO_PERMISSION`, `DECODE_FAILED`, `OUT_OF_MEMORY`, `CANCELLED`, `UNKNOWN`. Vue показывает для каждого понятный русский текст.
- Строгий JSON для экспорта собирает только Kotlin (`core/data`), Vue его не формирует: один источник правды.
- `steno.ts` и `StenoPlugin` меняются только вместе, одним PR, с ревью ролей 1 и 3.

## 7. Интерфейс на Vue и Kotlin-мост

Интерфейс — Vue-приложение внутри Capacitor; файлы, микрофон, модели, хранение и фоновую работу делает Kotlin. Главная цель раздела — чтобы Vue-разработчик работал независимо: в браузере на ПК с мок-плагином, без телефона и моделей.

**Vue-проект `src/web`:** Vite, Vue 3 + TypeScript с `<script setup>`, Ionic Vue, Pinia, `@ionic/vue-router`, ESLint + Prettier, Vitest. Тексты на русском в `src/i18n/ru.ts`. Светлая и тёмная тема через CSS-переменные Ionic, палитра из 8 цветов спикеров.

| Страница | Что делает |
| --- | --- |
| ModelSetupPage (первый запуск) | вызывает `prepareModels()`, показывает прогресс, понятную ошибку при нехватке места или отсутствии моделей |
| LibraryPage | список записей (`listRecordings`) со статусом; кнопки «Импорт файла» и «Записать»; удаление |
| RecordPage | `startRecording` / `stopRecording`, таймер, индикатор уровня из события `recordingLevel` |
| ProcessingPage | перед стартом: число спикеров (авто, 2–6), переключатели LLM; во время: этапы с прогрессом из события `progress`, общее время, «Отмена» |
| TranscriptPage | вкладки «Расшифровка» и «Протокол»; карточки реплик с цветом спикера и таймкодом; подсветка текущей реплики; плеер внизу; тап — перемотка; долгое нажатие — правка текста или смена спикера; меню: переименовать спикеров, экспорт JSON/TXT/SRT, «Поделиться» |
| SettingsPage | число потоков, пороги VAD и кластеризации, сведения о моделях, «О приложении» |

Компоненты: `UtteranceCard`, `AudioPlayerBar`, `StageProgressList`, `SpeakerChip`, `SpeakerPicker`. Каждый компонент получает данные через props и сообщает о действиях через emits, плагин вызывают только сторы.

**Плеер:** `<audio>` с `src = Capacitor.convertFileSrc(recording.audioPath)`; по событию `timeupdate` подсвечивается реплика, в интервал которой попадает позиция; тап по карточке — `audio.currentTime = startMs / 1000`. Плагин гарантирует, что `audioPath` указывает на файл, который WebView умеет играть (для редких форматов — WAV-копия).

**Kotlin-мост (модуль `app`):** `StenoPlugin` реализует контракт из раздела 6. Каждый метод сразу уходит в корутину (`Dispatchers.Default` для вычислений, `Dispatchers.IO` для файлов) и отвечает через `call.resolve` или `call.reject` с кодом ошибки. Прогресс шлётся через `notifyListeners`. Импорт и экспорт — системные диалоги через механизм Activity Result у плагинов Capacitor. Запись — `AudioRecord` в WAV 16 кГц моно. Разрешения микрофона и уведомлений — через механизм разрешений Capacitor.

**Фоновая обработка:** `TranscriptionService` — Foreground Service с уведомлением о прогрессе; на Android 15+ тип `mediaProcessing`, ниже — `dataSync`. Состояние держит синглтон `TranscriptionController` (StateFlow), плагин пересылает его во Vue событиями. Одна обработка за раз; результат сразу пишется в Room, поэтому интерфейс восстанавливается после перезапуска.

**Мок и заглушки.** `src/web/src/native/steno.mock.ts` имитирует все методы и события плагина: этапы с задержками, тестовая расшифровка на 3 спикеров, ошибки по кнопке. Подключается только при запуске в браузере и только под `import.meta.env.DEV`, поэтому в production-сборку Vite не попадает. Kotlin-`FakePipeline` лежит только в `app/src/debug` и нужен, чтобы проверить мост на телефоне до готовности моделей. В release нет ни того, ни другого: ТЗ запрещает подменять работу модели готовой расшифровкой.

**Три режима работы Vue-разработчика** (опиши их в `docs/WEB_DEV.md` пошагово, с командами):

1. В браузере: `npm run dev` — мок-плагин, горячая перезагрузка, Vue DevTools. Основной режим.
2. На телефоне с live reload: `npx cap run android -l --external` (ПК и телефон в одной Wi-Fi-сети, только debug).
3. На телефоне со сборкой: `npm run build && npx cap sync android`, затем Run в Android Studio.

Отладка WebView на телефоне — через `chrome://inspect` в Chrome на ПК: обычные DevTools, консоль и сеть.

## 8. Gradle, манифест и упаковка моделей

Главный риск сборки — большие модели и нативные библиотеки, поэтому их упаковка настраивается сразу.

**Настройки модуля `app`** (в его `build.gradle` в синтаксисе, который сгенерировал Capacitor; ниже — то же на Kotlin DSL для наглядности):

```kotlin
android {
    defaultConfig { ndk { abiFilters += "arm64-v8a" } }
    androidResources { noCompress += listOf("onnx", "gguf", "litertlm", "bin") }
    buildTypes {
        release {
            isMinifyEnabled = false          // включать только после проверки keep-правил
            signingConfig = signingConfigs.getByName("release")  // из keystore.properties
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

- `keystore.properties` и сам keystore лежат вне git; если их нет, release подписывается debug-ключом с предупреждением в логе сборки.
- В `proguard-rules.pro` заранее добавь `-keep class com.k2fsa.sherpa.onnx.** { *; }`, такое же правило для пакета LiteRT-LM и стандартные правила kotlinx.serialization.
- `gradle.properties`: `org.gradle.jvmargs=-Xmx4g`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `kotlin.code.style=official`.

**Манифест `app`:**

- разрешения: `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC`;
- шаблон Capacitor добавляет INTERNET. Оставь его только в debug (нужен для live reload), а в `app/src/release/AndroidManifest.xml` удали: `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`;
- проверь, что release-сборка открывает интерфейс в авиарежиме: Capacitor отдаёт файлы Vue из assets локально, сеть ему не нужна;
- в `capacitor.config.ts` для release нет `server.url` и cleartext-настроек; live reload включается только флагом команды `npx cap run`;
- сервис `TranscriptionService` с `android:foregroundServiceType="mediaProcessing|dataSync"`.

**Модели:**

- Лежат в `app/src/main/assets/models/{asr,vad,diarization,llm}/` и в git не попадают.
- `scripts/download_models.sh` скачивает их при разработке и проверяет sha256. Ссылки возьми с официальных страниц релизов sherpa-onnx, GigaAM и выбранной LLM; если точный URL проверить нельзя, оставь `TODO` с адресом страницы, где его искать.
- `assets/models/manifest.json` описывает каждый файл: путь, размер, sha256, источник, лицензия, назначение.
- `ModelManager` при первом запуске потоково копирует модели в `filesDir/models` с прогрессом, пишет маркер версии и повторно не копирует. Движки получают абсолютные пути к файлам.

**Нативные библиотеки:** `scripts/fetch_sherpa.sh` скачивает Android-сборку sherpa-onnx нужной версии, кладёт `.so` в `ml/sherpa/src/main/jniLibs/arm64-v8a/`, а Kotlin API-файлы — в исходники `ml/sherpa`. Версию зафиксируй в скрипте и в `docs/MODELS.md`.

**Размер APK:** держи итоговый APK меньше 2 ГБ. APK в git не коммитится: он публикуется в GitHub Releases (ограничение на файл там около 2 ГБ), а `artifacts/README.md` содержит ссылку на релиз.

**Сборка Vue:** все шрифты, иконки и картинки лежат внутри сборки, никаких CDN и внешних шрифтов. `scripts/build_release.sh` выполняет по порядку: `npm ci`, `npm run build` и `npx cap sync android` в `src/web`, затем `./gradlew assembleRelease` в `src/android`, и кладёт APK в понятное место с версией в имени.

## 9. Python-стенд оценки

Стенд в `src/eval` прогоняет тот же пайплайн на ПК с теми же ONNX-моделями и параметрами из `config/pipeline.default.json`. Так команда подбирает пороги за минуты, а не пересборкой APK. На ПК это разрешено: запрет касается только работы приложения.

| Файл | Назначение |
| --- | --- |
| `requirements.txt` | sherpa-onnx, numpy, soundfile, jiwer, pyannote.core, pyannote.metrics (версии зафиксировать) |
| `run_pipeline.py` | декодирование → VAD → диаризация → ASR по сегментам → склейка; результат в том же JSON-формате, что и приложение |
| `eval_wer.py` | WER гипотезы против эталона; нормализация по умолчанию: нижний регистр, ё→е, без пунктуации; флаги `--keep-punct` и `--no-normalize` |
| `eval_der.py` | DER через pyannote.metrics, collar 250 мс (настраивается) |
| `eval_timestamps.py` | средняя ошибка границ реплик в мс для сопоставленных реплик |
| `report.py` | сводная таблица WER, DER, ошибки границ и RTF по всем записям в Markdown для `docs/TESTS.md` |
| `README.md` | установка окружения и примеры команд |

Собственные тестовые записи и эталоны лежат в `src/eval/data/` (в `.gitignore`), эталоны — в том же JSON-формате. RTF на телефоне считается по таймингам этапов: в debug-сборке приложение кладёт рядом с экспортом файл `timings.json`, а `report.py` умеет его читать.

В `docs/TESTS.md` заранее заведи вопрос к организаторам: как нормализуется текст при подсчёте WER (пунктуация, регистр, числа цифрами или словами). От ответа зависит выбор варианта GigaAM и настройки нормализации.

## 10. Документация, роли и git-процесс

Документация пишется на русском и сразу покрывает отчётные материалы хакатона: APK, исходники, инструкцию, архитектуру и модели, ограничения, собственные тесты, видео.

| Файл | Содержимое на старте |
| --- | --- |
| `README.md` | что за приложение, быстрый старт, ссылки на docs и релиз |
| `docs/INSTALL.md` | установка APK, первый запуск, импорт, обработка, экспорт; сборка из исходников |
| `docs/ARCHITECTURE.md` | схема модулей и конвейера, правила зависимостей, поток данных |
| `docs/MODELS.md` | каждая модель: назначение, версия, размер, квантование, лицензия, ссылка |
| `docs/LIMITATIONS.md` | заготовка: требования к телефону, длина записи, известные проблемы |
| `docs/TESTS.md` | шаблон таблицы метрик и вопрос про нормализацию WER |
| `docs/TEAM.md` | роли, задачи, таймлайн (ниже) |
| `docs/BACKLOG.md` | идеи среднего уровня и Ultra Pro Max как чек-лист |
| `docs/DECISIONS.md` | журнал решений: дата, решение, почему |
| `docs/CONTRIBUTING.md` | ветки, PR, стиль кода |
| `docs/WEB_DEV.md` | для Vue-разработчика: три режима запуска, структура src/web, как вызывать плагин, как работает мок, отладка через chrome://inspect, частые ошибки |

**Роли и владение модулями** (заполни ими `docs/TEAM.md` и `.github/CODEOWNERS` с плейсхолдерами `@login-1` … `@login-4`):

| Роль | Модули и папки | Первые задачи |
| --- | --- | --- |
| 1. Интерфейс (Vue) | `src/web` | страницы и компоненты на Ionic Vue, Pinia-сторы, плеер, мок-плагин, тексты, UX; видео-демо |
| 2. ASR, устройство и релиз | `ml/asr`, `ml/sherpa`, `core/audio`, `ml/runtime`, `scripts` | подключить sherpa-onnx и GigaAM, нарезка сегментов, замеры RTF, ускорение; тесты на 2–3 телефонах; сборка и публикация APK |
| 3. Диаризация, пайплайн и мост | `ml/diarization`, `pipeline`, `config`, `app` | VAD, диаризация, подбор порогов, склейка и границы реплик; `StenoPlugin`, сервис обработки, разрешения |
| 4. LLM, данные, оценка, документация | `ml/llm`, `core/data`, `src/eval`, `docs` | подключить LiteRT-LM, промпты протокола и ролей; Room и экспорт; стенд метрик, свои тестовые записи; документация и подготовка защиты |

Роль 1 не зависит от остальных с первого часа: работает против мок-плагина и контракта `steno.ts`. Контракт моста — общая зона ролей 1 и 3. Каждый пишет документацию про свой модуль; роль 4 сводит её и следит за полнотой отчётных материалов.

В `docs/TEAM.md` под каждой ролью — чек-лист задач и таймлайн в долях времени хакатона: 0–10% контракты и каркас; 10–45% сквозной MVP «импорт → JSON» на реальных моделях; 45–80% качество и UX; 80–90% заморозка кода и финальный APK; 90–100% документация, видео, репетиция защиты.

**Git-процесс:** `main` всегда собирается; ветки `feat/<модуль>-<кратко>` и `fix/...`; небольшие PR с ревью человеком из другой роли; теги `v0.1-mvp`, `v0.2`, `v1.0-final`. CI в `.github/workflows/android.yml`: в `src/web` — `npm ci`, `npm run lint`, `npm run test`, `npm run build`, `npx cap sync android`; затем в `src/android` — `assembleDebug` и unit-тесты. Модели и `.so` для CI не нужны.

**`.gitignore`:** build-папки, `local.properties`, `.idea` (кроме общих настроек кода), `keystore.properties`, `*.jks`, `*.apk`, `*.aab`, `app/src/main/assets/models/**` (кроме `manifest.json` и `README.md`), `ml/sherpa/src/main/jniLibs/**`, `src/eval/data/`, Python-окружения, node\_modules, src/web/dist, app/src/main/assets/public.

## 11. Запреты и правила для агента

Эти правила важнее удобства: нарушение любого из первых трёх может дисквалифицировать команду.

1. Никакого сетевого кода в приложении: ни INTERNET в release, ни HTTP-клиентов, ни облачных SDK, ни аналитики; во Vue — никаких CDN, внешних шрифтов и запросов в сеть.
2. Никаких готовых расшифровок в release-сборке: мок-плагин работает только в браузере под `import.meta.env.DEV`, Kotlin-заглушки живут только в `src/debug` и в тестах.
3. Модели и данные обрабатываются только на устройстве; скачивание моделей — только скриптами для разработчиков, не кодом приложения.
4. Не выдумывай версии библиотек (Gradle и npm), URL и API: проверяй по официальной документации. Не смог проверить — оставь `TODO` со ссылкой на страницу, где искать, и упомяни это в отчёте.
5. Не коммить модели, `.so`, keystore, APK, `node_modules`, сборку Vue.
6. ML-движки — интерфейсы, фабрики и заглушки, которые бросают `NotImplementedError("TODO(роль N): ...")`. Не-ML части (мост, Vue-страницы, экспорт, склейка, декодер) реализуй полностью и с тестами.
7. Через мост не передаются аудио и большие массивы; страницы и компоненты Vue не вызывают плагин напрямую, только через сторы.
8. Никогда не блокируй главный поток Android; каждый движок `Closeable` и освобождается после своего этапа.
9. Ключи JSON-экспорта не менять ни при каких условиях; строгий JSON формирует только Kotlin.
10. Комментарии и документация на русском, идентификаторы на английском; Kotlin в стиле official, Vue на TypeScript с `<script setup>`.
11. Не трогай файлы шаблона организаторов, которые не относятся к задаче; если что-то в шаблоне противоречит этому промпту, следуй шаблону и сообщи об этом.

## 12. Порядок выполнения и отчёт

Работай по шагам и после каждого проверяй сборку; не переходи дальше, пока текущий шаг не собирается.

1. Прочитай `README.md` шаблона и весь этот промпт; составь план и сверь раскладку папок.
2. `src/web`: создай проект Vite + Vue 3 + TypeScript, подключи Ionic Vue, Pinia, ESLint, Prettier, Vitest. Подключи Capacitor (`webDir: 'dist'`, `android.path: '../android'`) и выполни `npx cap add android`. Добейся `npm run build`, `npx cap sync android` и `assembleDebug`.
3. Добавь в `src/android` модули из раздела 5 и version catalog; сборка проходит.
4. `core/model`: контракты из раздела 6 и unit-тест JSON-сериализации на примере из раздела 2.
5. `core/audio`: декодер, ресемплер в 16 кГц моно, запись и чтение WAV; тесты ресемплера и WAV.
6. `core/data`: Room-схема, репозиторий, экспорт JSON/TXT/SRT; тесты экспорта.
7. `ml/*`: `ModelManager`, обёртка `ml/sherpa`, заглушки движков, `manifest.json` и скрипты `download_models.sh`, `fetch_sherpa.sh`.
8. `pipeline`: оркестрация с прогрессом и таймингами, полная логика склейки с тестами; `FakePipeline` в debug.
9. `app`: `StenoPlugin` по контракту раздела 6, сервис обработки, манифесты debug и release, Koin.
10. `src/web`: `steno.ts`, мок `steno.mock.ts`, сторы, страницы и компоненты из раздела 7, плеер, тема, тексты; `docs/WEB_DEV.md`. Проверь все страницы в браузере через `npm run dev`.
11. `src/eval`: скрипты из раздела 9 с `--help` и README.
12. Документация, `CODEOWNERS`, CI, `.gitignore`, `config/pipeline.default.json`, `scripts/build_release.sh`.
13. Финальная проверка: `npm run lint`, `npm run test`, `npm run build`, `npx cap sync android`, затем `./gradlew assembleDebug testDebugUnitTest lint` и `assembleRelease`; убедись, что в манифесте release нет INTERNET и мок не попал в production-сборку Vue. Исправь всё найденное.

**Итоговый отчёт** выведи в чат и сохрани в `docs/SETUP_REPORT.md`:

- дерево созданных файлов (до 3 уровней);
- что реализовано полностью, а что заглушка, с указанием роли-владельца;
- выбранные версии ключевых библиотек и где ты их проверил;
- команды для сборки, тестов и скачивания моделей, отдельно — короткая инструкция для Vue-разработчика, как запустить интерфейс в браузере и на телефоне;
- открытые вопросы и непроверенные `TODO`;
- первые три задачи для каждой из четырёх ролей.
