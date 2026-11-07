# Как работаем с кодом

## Ветки и PR

- `main` всегда собирается (CI: `.github/workflows/android.yml`).
- Ветки: `feat/<модуль>-<кратко>` (например `feat/asr-gigaam`), `fix/<кратко>`.
- Небольшие PR; ревью — человек из **другой** роли (`CODEOWNERS` назначает автоматически).
- `steno.ts` и `StenoPlugin.kt` меняются только вместе, одним PR, с ревью ролей 1 и 3.
- `core/model` — общий договор: изменения согласуются со всеми.
- Теги: `v0.1-mvp`, `v0.2`, `v1.0-final`.

## Перед PR

```bash
cd src/web && npm run lint && npm run test && npm run build && npm run check:no-mock
cd src/android && ./gradlew assembleDebug testDebugUnitTest :core:model:test
```

## Стиль

- Комментарии и документация — на русском, идентификаторы — на английском.
- Kotlin — official code style (`kotlin.code.style=official`); никакой работы на главном потоке;
  каждый движок `Closeable` и закрывается сразу после своего этапа.
- Vue — TypeScript, `<script setup>`, Composition API; компоненты — props/emits, плагин вызывают только сторы;
  тексты — только в `i18n/ru.ts`; форматирование — Prettier.

## Нельзя

- Сетевой код в приложении, INTERNET в release, HTTP-клиенты, облачные SDK, аналитика, CDN и внешние шрифты во Vue.
- Готовые расшифровки в release (мок — только `npm run dev`, FakePipeline — только `src/debug`).
- Коммитить модели, `.so`, keystore, APK, `node_modules`, сборку Vue.
- Менять ключи JSON-экспорта (`speaker_id`, `start_ms`, `end_ms`, `text`).
