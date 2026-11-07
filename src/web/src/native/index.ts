/**
 * Единственная точка доступа к плагину для сторов.
 *
 * В браузере под `import.meta.env.DEV` подключается мок; в production-сборке ветка
 * с моком вырезается Vite целиком, поэтому готовая расшифровка в APK не попадает.
 */
import { Capacitor } from '@capacitor/core'
import { Steno, type StenoPlugin } from './steno'

let pluginPromise: Promise<StenoPlugin> | null = null

/**
 * Все методы контракта. Record<keyof StenoPlugin, true> заставляет компилятор
 * напомнить, если в steno.ts появится метод, а сюда его забудут добавить.
 */
const METHODS: Record<keyof StenoPlugin, true> = {
  prepareModels: true,
  getModelsInfo: true,
  getPipelineDefaults: true,
  listRecordings: true,
  importAudio: true,
  startRecording: true,
  stopRecording: true,
  deleteRecording: true,
  process: true,
  cancel: true,
  getResult: true,
  updateUtterance: true,
  renameSpeaker: true,
  exportResult: true,
  share: true,
  addListener: true,
}

/**
 * Плагин Capacitor на телефоне — Proxy: ЛЮБОЕ свойство, включая `then`, он считает
 * нативным методом. Если отдать его через Promise, JS спросит `plugin.then`, вызов уйдёт
 * в Kotlin и упадёт с "Steno.then() is not implemented". Поэтому наружу отдаём обычный
 * объект только с методами контракта.
 */
export function wrapNativePlugin(plugin: StenoPlugin): StenoPlugin {
  const wrapper: Record<string, unknown> = {}
  for (const name of Object.keys(METHODS) as (keyof StenoPlugin)[]) {
    wrapper[name] = (...args: unknown[]) =>
      (plugin[name] as (...a: unknown[]) => unknown).apply(plugin, args)
  }
  return wrapper as unknown as StenoPlugin
}

export function getSteno(): Promise<StenoPlugin> {
  if (!pluginPromise) {
    if (import.meta.env.DEV && !Capacitor.isNativePlatform()) {
      pluginPromise = import('./steno.mock').then((m) => m.createStenoMock())
    } else {
      pluginPromise = Promise.resolve(wrapNativePlugin(Steno))
    }
  }
  return pluginPromise
}

/** Подмена плагина в unit-тестах. */
export function setStenoForTests(plugin: StenoPlugin | null): void {
  pluginPromise = plugin ? Promise.resolve(plugin) : null
}

/** Превращает путь из Kotlin в URL, который понимает `<audio>` в WebView. */
export function playableSrc(path: string): string {
  if (path.startsWith('blob:') || path.startsWith('data:') || path.startsWith('http')) return path
  return Capacitor.convertFileSrc(path)
}

export function isNative(): boolean {
  return Capacitor.isNativePlatform()
}

export * from './steno'
