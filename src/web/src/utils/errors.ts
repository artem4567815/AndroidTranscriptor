import { ru } from '@/i18n/ru'
import type { StenoErrorCode } from '@/native/steno'

const CODES = Object.keys(ru.errors) as StenoErrorCode[]

export function errorCode(e: unknown): StenoErrorCode {
  const code = (e as { code?: unknown } | null)?.code
  return typeof code === 'string' && (CODES as string[]).includes(code)
    ? (code as StenoErrorCode)
    : 'UNKNOWN'
}

/** Понятный русский текст ошибки плагина по её коду. */
export function errorMessage(e: unknown): string {
  return ru.errors[errorCode(e)]
}
