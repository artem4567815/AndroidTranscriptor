import type { Utterance } from '@/native/steno'

/** 75_300 → "01:15", 3_725_000 → "1:02:05". */
export function formatMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

/** Индекс реплики, в интервал которой попадает позиция плеера; -1 если между репликами. */
export function activeUtteranceIndex(items: readonly Utterance[], positionMs: number): number {
  let lo = 0
  let hi = items.length - 1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const u = items[mid]
    if (positionMs < u.start_ms) hi = mid - 1
    else if (positionMs >= u.end_ms) lo = mid + 1
    else return mid
  }
  return -1
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} ГБ`
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} МБ`
  return `${Math.round(bytes / 1e3)} КБ`
}
