import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createStenoMock, type StenoMock } from '@/native/steno.mock'
import { setStenoForTests } from '@/native'
import { useProcessingStore } from './processing'
import { useRecordingsStore } from './recordings'
import { useTranscriptStore } from './transcript'

const options = { numSpeakers: 0, enableLlmProtocol: true, enableLlmCorrection: false }

function waitFor(cond: () => boolean, timeoutMs = 5000): Promise<void> {
  return new Promise((resolve, reject) => {
    const t0 = Date.now()
    const tick = () => {
      if (cond()) return resolve()
      if (Date.now() - t0 > timeoutMs) return reject(new Error('timeout'))
      setTimeout(tick, 5)
    }
    tick()
  })
}

describe('processing store + мок', () => {
  let mock: StenoMock
  beforeEach(() => {
    setActivePinia(createPinia())
    mock = createStenoMock()
    mock.devSetSpeed(200)
    setStenoForTests(mock)
  })
  afterEach(() => setStenoForTests(null))

  it('проходит все этапы и помечает запись DONE', async () => {
    const recordings = useRecordingsStore()
    await recordings.refresh()
    const p = useProcessingStore()
    await p.start('2', options)
    expect(p.running).toBe(true)
    await waitFor(() => p.completed)
    expect(p.running).toBe(false)
    expect(Object.values(p.stages).every((s) => s.done)).toBe(true)
    expect(recordings.byId('2')?.status).toBe('DONE')

    const t = useTranscriptStore()
    await t.load('2')
    expect(t.utterances.length).toBeGreaterThan(0)
    expect(t.speakerIds).toEqual(['speaker_1', 'speaker_2', 'speaker_3'])
  })

  it('ошибка по кнопке приходит как failed с русским текстом', async () => {
    mock.devFailNext('OUT_OF_MEMORY')
    const p = useProcessingStore()
    await p.start('2', options)
    await waitFor(() => !p.running)
    expect(p.failure?.code).toBe('OUT_OF_MEMORY')
    expect(p.failure?.text).toContain('памяти')
  })

  it('отмена', async () => {
    mock.devSetSpeed(1)
    const p = useProcessingStore()
    await p.start('2', options)
    await p.cancel()
    await waitFor(() => !p.running)
    expect(p.failure?.code).toBe('CANCELLED')
  })
})

describe('transcript store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setStenoForTests(createStenoMock())
  })

  it('правка текста, смена и переименование спикера', async () => {
    const t = useTranscriptStore()
    await t.load('1')
    await t.updateText(0, 'Новый текст')
    await t.changeSpeaker(1, 'speaker_4')
    await t.renameSpeaker('speaker_4', 'Секретарь')
    await t.load('1')
    expect(t.utterances[0].text).toBe('Новый текст')
    expect(t.utterances[1].speaker_id).toBe('speaker_4')
    expect(t.speakerNames.speaker_4).toBe('Секретарь')
  })

  it('подсветка текущей реплики по позиции плеера', async () => {
    const t = useTranscriptStore()
    await t.load('1')
    t.positionMs = t.utterances[2].start_ms + 10
    expect(t.activeIndex).toBe(2)
  })
})

describe('отмена', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const mock = createStenoMock()
    mock.devSetSpeed(1)
    setStenoForTests(mock)
  })
  afterEach(() => setStenoForTests(null))

  it('флаг «отменяю» держится до конца обработки и сбрасывается после', async () => {
    const p = useProcessingStore()
    await p.start('2', options)
    await p.cancel()
    expect(p.cancelRequested).toBe(true)
    await p.cancel() // повторное нажатие ничего не ломает
    await waitFor(() => !p.running)
    expect(p.cancelRequested).toBe(false)
    expect(p.failure?.code).toBe('CANCELLED')
  })
})
