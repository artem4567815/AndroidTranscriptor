import { afterEach, beforeEach, describe, expect, it } from 'vitest'
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
    expect(p.running.value).toBe(true)
    await waitFor(() => p.completed.value)
    expect(p.running.value).toBe(false)
    expect(Object.values(p.stages).every((s) => s.done)).toBe(true)
    expect(recordings.byId('2')?.status).toBe('DONE')

    const t = useTranscriptStore()
    await t.load('2')
    expect(t.utterances.value.length).toBeGreaterThan(0)
    expect(t.speakerIds.value).toEqual(['speaker_1', 'speaker_2', 'speaker_3'])
  })

  it('ошибка по кнопке приходит как failed с русским текстом', async () => {
    mock.devFailNext('OUT_OF_MEMORY')
    const p = useProcessingStore()
    await p.start('2', options)
    await waitFor(() => !p.running.value)
    expect(p.failure.value?.code).toBe('OUT_OF_MEMORY')
    expect(p.failure.value?.text).toContain('памяти')
  })

  it('отмена', async () => {
    mock.devSetSpeed(1)
    const p = useProcessingStore()
    await p.start('2', options)
    await p.cancel()
    await waitFor(() => !p.running.value)
    expect(p.failure.value?.code).toBe('CANCELLED')
  })
})

describe('transcript store', () => {
  beforeEach(() => {
    setStenoForTests(createStenoMock())
  })

  it('правка текста, смена и переименование спикера', async () => {
    const t = useTranscriptStore()
    await t.load('1')
    await t.updateText(0, 'Новый текст')
    await t.changeSpeaker(1, 'speaker_4')
    await t.renameSpeaker('speaker_4', 'Секретарь')
    await t.load('1')
    expect(t.utterances.value[0].text).toBe('Новый текст')
    expect(t.utterances.value[1].speaker_id).toBe('speaker_4')
    expect(t.speakerNames.value.speaker_4).toBe('Секретарь')
  })

  it('подсветка текущей реплики по позиции плеера', async () => {
    const t = useTranscriptStore()
    await t.load('1')
    t.positionMs.value = t.utterances.value[2].start_ms + 10
    expect(t.activeIndex.value).toBe(2)
  })
})

describe('отмена', () => {
  beforeEach(() => {
    const mock = createStenoMock()
    mock.devSetSpeed(1)
    setStenoForTests(mock)
  })
  afterEach(() => setStenoForTests(null))

  it('флаг «отменяю» держится до конца обработки и сбрасывается после', async () => {
    const p = useProcessingStore()
    await p.start('2', options)
    await p.cancel()
    expect(p.cancelRequested.value).toBe(true)
    await p.cancel() // повторное нажатие ничего не ломает
    await waitFor(() => !p.running.value)
    expect(p.cancelRequested.value).toBe(false)
    expect(p.failure.value?.code).toBe('CANCELLED')
  })
})

describe('настройки: в Kotlin уходит только то, что пользователь поменял', () => {
  beforeEach(() => {
    localStorage.clear()
    setStenoForTests(createStenoMock())
  })
  afterEach(() => setStenoForTests(null))

  it('по умолчанию параметры обработки не отправляются, значения берутся из Kotlin', async () => {
    const { useSettingsStore } = await import('./settings')
    const st = useSettingsStore()
    st.resetDefaults()
    await st.loadDefaults()
    const opts = st.pipelineOptions()
    expect(opts.clusterThreshold).toBeUndefined()
    expect(opts.vadThreshold).toBeUndefined()
    expect(st.tunableValue('clusterThreshold')).toBe(0.5) // показ — из getPipelineDefaults
    st.setTunable('clusterThreshold', 0.35)
    expect(st.pipelineOptions().clusterThreshold).toBe(0.35)
    expect(st.isChanged('clusterThreshold')).toBe(true)
    st.resetTunable('clusterThreshold')
    expect(st.pipelineOptions().clusterThreshold).toBeUndefined()
  })
})
