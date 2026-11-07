# Журнал решений

| Дата | Решение | Почему |
|---|---|---|
| 2026-11-06 | UI — Vue 3 + Ionic Vue в Capacitor, обработка — Kotlin | веб-разработчик работает в привычном стеке с первого часа; ML не теряет в скорости и памяти |
| 2026-11-06 | Capacitor 8.6.0 → AGP 8.13.0, compileSdk/targetSdk 36, **Java 21** | так требует Capacitor 8 (`capacitor.build.gradle`); промпт упоминал JDK 17, но Capacitor 8 компилирует под 21 |
| 2026-11-06 | Gradle 8.14.3 (wrapper Capacitor) | AGP 8.13 + плагины Capacitor; Kotlin 2.4 предупреждает о старом Gradle — поднять вместе с переходом Capacitor на AGP 9 |
| 2026-11-06 | `androidx.core` 1.17.0 вместо 1.19.1 | 1.18+ требует compileSdk 37 и AGP 9.1 |
| 2026-11-06 | Kotlin 2.4.21, KSP 2.3.13, Room 2.8.5, Koin 4.2.2, coroutines 1.11.0, serialization 1.12.0, DataStore 1.3.0 | последние стабильные на Maven Central / Google Maven |
| 2026-11-06 | Плагины Kotlin/KSP подключены через `buildscript` корневого Groovy `build.gradle` | модули Capacitor так же тянут AGP через buildscript; `plugins {}` с версиями конфликтует с этим |
| 2026-11-06 | sherpa-onnx 2.0.1: `.so` + исходники Kotlin API, без AAR | AAR только на GitHub, локальный AAR в library-модуле ломает `bundleAar`; так CI собирается без бинарников |
| 2026-11-06 | ASR — `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16` | GigaAM v3 e2e с пунктуацией в официальном релизе sherpa-onnx; CTC-вариант — запасной |
| 2026-11-06 | Расширения контракта моста: `getModelsInfo`, событие `modelsProgress`, код `NO_SPACE`, необязательные `numThreads`/`clusterThreshold`/`vadThreshold` в `PipelineOptions`; `vadThreshold` в `PipelineConfig` | нужны страницам ModelSetupPage и SettingsPage из раздела 7; базовый контракт не изменён |
| 2026-11-06 | Пустые сегменты в склейке = без букв и цифр; `minUtteranceMs` отбрасывает короткие сегменты диаризации до ASR | короткие осмысленные реплики («Да.») не теряются — это важно для WER |
| 2026-11-06 | Сбой LLM не валит обработку | LLM — необязательный этап; сырой ASR всегда сохраняется |
| 2026-11-06 | FakePipeline только в `src/debug`, режим `-Psteno.fakePipeline=auto|true|false` | ТЗ запрещает подмену модели в release; в debug нужно проверить мост до готовности движков |
| 2026-11-06 | Ресемплер — оконный sinc на Kotlin | без FFmpeg и нативного кода; антиалиасинг при понижении частоты |
| 2026-11-06 | Настройки UI — localStorage WebView, последний `PipelineConfig` — DataStore | контракт моста не содержит методов настроек; Kotlin хранит фактически применённые параметры |
