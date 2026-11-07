/**
 * Единственная точка доступа к плагину для сторов.
 *
 * В браузере под `import.meta.env.DEV` подключается мок; в production-сборке ветка
 * с моком вырезается Vite целиком, поэтому готовая расшифровка в APK не попадает.
 */
import { Capacitor } from '@capacitor/core'
import { Steno, type StenoPlugin } from './steno'

let pluginPromise: Promise<StenoPlugin> | null = null

export function getSteno(): Promise<StenoPlugin> {
  if (!pluginPromise) {
    if (import.meta.env.DEV && !Capacitor.isNativePlatform()) {
      pluginPromise = import('./steno.mock').then((m) => m.createStenoMock())
    } else {
      pluginPromise = Promise.resolve(Steno)
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
