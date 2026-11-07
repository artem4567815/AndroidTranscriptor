./gradlew assembleDebug testDebugUnitTest lint assembleRelease# Для Vue-разработчика

Интерфейс — Vue 3 + TypeScript (`<script setup>`) + Ionic Vue + Pinia внутри Capacitor. Вся тяжёлая
работа (файлы, микрофон, модели, хранение, обработка) — на Kotlin. Вы работаете против TypeScript-контракта
`src/native/steno.ts` и мок-плагина — без телефона и моделей.

## Три режима запуска

### 1. В браузере (основной)

```bash
cd src/web
npm install
npm run dev            # http://localhost:5173
```

Работает мок-плагин `src/native/steno.mock.ts`: две тестовые записи, имитация всех этапов с задержками,
расшифровка «совета котиков» на 3 спикеров, синтезированное аудио для плеера.
В **Настройки → Отладка мока** можно включить ошибку для следующей обработки и ускорить имитацию.
Горячая перезагрузка и Vue DevTools (расширение браузера) работают как обычно.
Для телефонного вида — DevTools → Toggle device toolbar.

### 2. На телефоне с live reload (только debug)

ПК и телефон в одной Wi-Fi-сети, телефон подключён по USB с включённой отладкой:

```bash
cd src/web
npx cap run android -l --external
```

Capacitor поднимет dev-сервер и подставит его адрес только на время этого запуска (в `capacitor.config.ts`
нет `server.url` — release всегда грузит файлы из APK).

### 3. На телефоне со сборкой

```bash
cd src/web
npm run build && npx cap sync android
```

Затем в Android Studio: открыть `src/android` → Run. Без моделей debug-сборка использует Kotlin-`FakePipeline`.

### Отладка WebView

На ПК в Chrome откройте `chrome://inspect` → ваше устройство → **inspect**: обычные DevTools, консоль, сеть.

## Структура `src/web/src`

| Папка | Что |
|---|---|
| `pages/` | `ModelSetupPage`, `LibraryPage`, `RecordPage`, `ProcessingPage`, `TranscriptPage`, `SettingsPage` |
| `components/` | `UtteranceCard`, `AudioPlayerBar`, `StageProgressList`, `SpeakerChip`, `SpeakerPicker` — данные через props, действия через emits |
| `stores/` | Pinia: `recordings`, `processing`, `transcript`, `settings`, `models` — **только они** вызывают плагин |
| `native/` | `steno.ts` (контракт), `steno.mock.ts` (только dev), `index.ts` (`getSteno()`, `playableSrc()`) |
| `i18n/ru.ts` | все тексты интерфейса и тексты ошибок по кодам |
| `theme/` | CSS-переменные Ionic, палитра из 8 цветов спикеров (`--speaker-1..8`), светлая/тёмная тема |
| `utils/` | форматирование времени, поиск активной реплики, цвета спикеров, маппинг ошибок |

## Как вызывать плагин

Только из стора:

```ts
import { getSteno } from '@/native'

const steno = await getSteno()            // в браузере — мок, на телефоне — Kotlin
const { items } = await steno.listRecordings()
const handle = await steno.addListener('progress', (e) => { /* e.stage, e.fraction */ })
await handle.remove()
```

ESLint запрещает импортировать `@capacitor/core` в страницах и компонентах.
Ошибки плагина приходят с `code` из фиксированного списка; русский текст — `errorMessage(e)` из `utils/errors.ts`.

## Как работает мок

`native/index.ts`: если `import.meta.env.DEV && !Capacitor.isNativePlatform()` — динамический импорт
`steno.mock.ts`, иначе настоящий плагин. В production-сборке Vite заменяет `import.meta.env.DEV` на `false`
и вырезает импорт вместе с моком. Проверка: `npm run build && npm run check:no-mock`.
Меняете `steno.ts` — обновите мок и Kotlin `StenoPlugin` в том же PR (ревью ролей 1 и 3).

## Команды

```bash
npm run dev          # dev-сервер с моком
npm run build        # vue-tsc + vite build → dist/
npm run lint         # ESLint + Prettier --check
npm run lint:fix     # автоисправление
npm run test         # Vitest
npm run check:no-mock
npx cap sync android # скопировать dist в APK
```

## Частые ошибки

- **Белый экран на телефоне** — забыли `npm run build && npx cap sync android` или в консоли (`chrome://inspect`) ошибка.
- **`Steno plugin is not implemented on web`** — запустили production-сборку (`npm run preview`) в браузере: мок есть только в `npm run dev`.
- **Плеер не играет на телефоне** — путь передан без `playableSrc()`.
- **Live reload не подключается** — телефон и ПК в разных сетях или файрвол блокирует порт 5173.
- **`npm install` падает с `Cannot read properties of null (reading 'edgesOut')`** — старый npm 11.2; обновите npm (`npm i -g npm@11`) или Node до LTS.
