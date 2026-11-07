# Нейро-стенографист

Android-приложение для хакатона **Nuclear IT Hack**: локально, без интернета, превращает аудиозапись
разговора в расшифровку — с распознаванием речи, автоматической диаризацией (кто говорит),
временными метками реплик, протоколом от локальной LLM и экспортом в JSON / TXT / SRT.
Аудио никуда не передаётся: все модели работают на телефоне.

**Конвейер:** декодирование 16 кГц моно → предобработка → VAD (Silero) → диаризация
(pyannote-segmentation-3.0 + 3D-Speaker) → ASR по сегментам говорящих (GigaAM v3 e2e) →
склейка реплик → LLM-постобработка (LiteRT-LM) → Room и экспорт.

**Стек:** Vue 3 + Ionic Vue внутри Capacitor 8 (интерфейс) · Kotlin-модули (вся обработка) ·
sherpa-onnx · LiteRT-LM · Room · Koin.

## Быстрый старт

```bash
# 1. Интерфейс в браузере (мок-плагин, без телефона и моделей)
cd src/web && npm install && npm run dev

# 2. Нативные библиотеки и модели (для разработчиков, нужен интернет)
scripts/fetch_sherpa.sh
scripts/download_models.sh

# 3. Сборка Vue → Android
cd src/web && npm run build && npx cap sync android
cd ../android && ./gradlew assembleDebug testDebugUnitTest

# 4. Release APK
scripts/build_release.sh
```

Затем откройте `src/android` в Android Studio, дождитесь Gradle Sync и нажмите Run.

## Документация

| Документ | О чём |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | установка APK, использование, сборка из исходников |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | модули, конвейер, правила зависимостей, мост Vue ↔ Kotlin |
| [docs/MODELS.md](docs/MODELS.md) | модели: назначение, версия, размер, лицензия |
| [docs/WEB_DEV.md](docs/WEB_DEV.md) | для Vue-разработчика: три режима запуска, мок, отладка |
| [docs/TEAM.md](docs/TEAM.md) | роли, задачи, таймлайн |
| [docs/TESTS.md](docs/TESTS.md) | метрики WER / DER / RTF, собственные тесты |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | требования к телефону, известные ограничения |
| [docs/BACKLOG.md](docs/BACKLOG.md) | идеи и улучшения |
| [docs/DECISIONS.md](docs/DECISIONS.md) | журнал решений |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | ветки, PR, стиль кода |
| [docs/SETUP_REPORT.md](docs/SETUP_REPORT.md) | отчёт о создании каркаса |

Релиз APK: см. [artifacts/README.md](artifacts/README.md).

## Формат экспорта

```json
{
  "transcript": [
    { "speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!" }
  ]
}
```
