import { describe, expect, it } from 'vitest'
import { activeUtteranceIndex, formatBytes, formatMs } from './time'
import type { Utterance } from '@/native/steno'

const u = (start: number, end: number): Utterance => ({
  speaker_id: 'speaker_1',
  start_ms: start,
  end_ms: end,
  text: 'x',
})

describe('formatMs', () => {
  it('форматирует минуты и часы', () => {
    expect(formatMs(0)).toBe('00:00')
    expect(formatMs(75_300)).toBe('01:15')
    expect(formatMs(3_725_000)).toBe('1:02:05')
    expect(formatMs(-5)).toBe('00:00')
  })
})

describe('activeUtteranceIndex', () => {
  const items = [u(0, 1000), u(1500, 3000), u(3000, 4000)]
  it('находит реплику по позиции', () => {
    expect(activeUtteranceIndex(items, 500)).toBe(0)
    expect(activeUtteranceIndex(items, 1500)).toBe(1)
    expect(activeUtteranceIndex(items, 3000)).toBe(2)
  })
  it('возвращает -1 между репликами и за краями', () => {
    expect(activeUtteranceIndex(items, 1200)).toBe(-1)
    expect(activeUtteranceIndex(items, 4000)).toBe(-1)
    expect(activeUtteranceIndex([], 10)).toBe(-1)
  })
})

describe('formatBytes', () => {
  it('ГБ / МБ / КБ', () => {
    expect(formatBytes(1_500_000_000)).toBe('1.5 ГБ')
    expect(formatBytes(2_200_000)).toBe('2.2 МБ')
    expect(formatBytes(1_000)).toBe('1 КБ')
  })
})
