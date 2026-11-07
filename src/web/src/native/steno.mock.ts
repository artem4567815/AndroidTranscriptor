/**
 * Мок плагина Steno — ТОЛЬКО для `npm run dev` в браузере.
 *
 * Подключается из `native/index.ts` под `import.meta.env.DEV`, в production-сборку не попадает.
 * Имитирует все методы и события: этапы с задержками, расшифровку на 3 спикеров, ошибки по кнопке.
 */
import type { PluginListenerHandle } from '@capacitor/core'
import {
  STAGES,
  type ExportFormat,
  type ModelInfo,
  type PipelineOptions,
  type Recording,
  type Stage,
  type StenoErrorCode,
  type StenoPlugin,
  type Transcript,
  type TranscriptResult,
} from './steno'

/** Дополнительные ручки мока для dev-панели в настройках. */
export interface StenoMockControls {
  devFailNext(code: StenoErrorCode | null): void
  devSetSpeed(multiplier: number): void
}

export type StenoMock = StenoPlugin & StenoMockControls

const SAMPLE: Transcript = {
  transcript: [
    {
      speaker_id: 'speaker_1',
      start_ms: 400,
      end_ms: 3900,
      text: 'Добрый день! Открываем заседание совета котиков.',
    },
    {
      speaker_id: 'speaker_2',
      start_ms: 4300,
      end_ms: 7200,
      text: 'Мяу. Предлагаю начать с вопроса о сметане.',
    },
    {
      speaker_id: 'speaker_3',
      start_ms: 7600,
      end_ms: 11800,
      text: 'Поддерживаю. Запасы сметаны на кухне подходят к концу.',
    },
    {
      speaker_id: 'speaker_1',
      start_ms: 12200,
      end_ms: 15900,
      text: 'Кто отвечает за наблюдение за холодильником?',
    },
    {
      speaker_id: 'speaker_2',
      start_ms: 16300,
      end_ms: 19500,
      text: 'Я дежурил вчера, дверь открывали трижды.',
    },
    {
      speaker_id: 'speaker_3',
      start_ms: 19900,
      end_ms: 23400,
      text: 'Нужно сесть у холодильника всем вместе и громко мяукать.',
    },
    {
      speaker_id: 'speaker_1',
      start_ms: 23800,
      end_ms: 27100,
      text: 'Хорошо. Второй вопрос — коробка в прихожей.',
    },
    {
      speaker_id: 'speaker_2',
      start_ms: 27500,
      end_ms: 31000,
      text: 'Коробка моя, я в ней сплю с понедельника.',
    },
    {
      speaker_id: 'speaker_3',
      start_ms: 31400,
      end_ms: 34800,
      text: 'Предлагаю спать в ней по очереди, по два часа.',
    },
    {
      speaker_id: 'speaker_1',
      start_ms: 35200,
      end_ms: 38600,
      text: 'Голосуем. Кто за очередь в коробку?',
    },
    { speaker_id: 'speaker_2', start_ms: 39000, end_ms: 40800, text: 'Против.' },
    {
      speaker_id: 'speaker_3',
      start_ms: 41200,
      end_ms: 44500,
      text: 'За. Решение принято, заседание закрыто.',
    },
  ],
}

const SAMPLE_PROTOCOL = `ПРОТОКОЛ ЗАСЕДАНИЯ СОВЕТА КОТИКОВ (тестовые данные мока)

Председатель: speaker_1. Присутствовали: speaker_1, speaker_2, speaker_3.

1. О запасах сметаны.
   Решили: организовать совместное дежурство у холодильника.
2. О коробке в прихожей.
   Решили: пользоваться коробкой по очереди, по два часа (2 — за, 1 — против).`

const MODELS: ModelInfo[] = [
  {
    id: 'asr',
    purpose: 'Распознавание речи (GigaAM v3 e2e, int8)',
    version: 'mock',
    sizeBytes: 230_000_000,
    license: 'MIT',
    present: true,
  },
  {
    id: 'vad',
    purpose: 'VAD (Silero)',
    version: 'mock',
    sizeBytes: 2_200_000,
    license: 'MIT',
    present: true,
  },
  {
    id: 'segmentation',
    purpose: 'Сегментация (pyannote-segmentation-3.0)',
    version: 'mock',
    sizeBytes: 6_000_000,
    license: 'MIT',
    present: true,
  },
  {
    id: 'embedding',
    purpose: 'Эмбеддинги спикеров (3D-Speaker)',
    version: 'mock',
    sizeBytes: 28_000_000,
    license: 'Apache-2.0',
    present: true,
  },
  {
    id: 'llm',
    purpose: 'LLM (LiteRT-LM)',
    version: 'mock',
    sizeBytes: 1_500_000_000,
    license: 'см. карточку модели',
    present: false,
  },
]

const STAGE_MS: Record<Stage, number> = {
  DECODE: 500,
  PREPROCESS: 300,
  VAD: 500,
  DIARIZATION: 1600,
  ASR: 2400,
  MERGE: 200,
  LLM: 1400,
  SAVE: 200,
}

class MockError extends Error {
  constructor(
    public code: StenoErrorCode,
    message: string,
  ) {
    super(message)
  }
}

type Listener = (e: unknown) => void

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Синтезирует WAV с тональными посылками по репликам, чтобы плеер было что играть. */
export function synthWav(t: Transcript, durationMs: number, sampleRate = 8000): Blob {
  const n = Math.floor((durationMs / 1000) * sampleRate)
  const buffer = new ArrayBuffer(44 + n * 2)
  const v = new DataView(buffer)
  const str = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)))
  str(0, 'RIFF')
  v.setUint32(4, 36 + n * 2, true)
  str(8, 'WAVE')
  str(12, 'fmt ')
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)
  v.setUint16(22, 1, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate * 2, true)
  v.setUint16(32, 2, true)
  v.setUint16(34, 16, true)
  str(36, 'data')
  v.setUint32(40, n * 2, true)
  const freq: Record<string, number> = { speaker_1: 220, speaker_2: 330, speaker_3: 440 }
  for (const u of t.transcript) {
    const f = freq[u.speaker_id] ?? 550
    const from = Math.floor((u.start_ms / 1000) * sampleRate)
    const to = Math.min(n, Math.floor((u.end_ms / 1000) * sampleRate))
    for (let i = from; i < to; i++) {
      const env = Math.sin((Math.PI * (i - from)) / (to - from))
      const s = 0.15 * env * Math.sin((2 * Math.PI * f * i) / sampleRate)
      v.setInt16(44 + i * 2, Math.round(s * 32767), true)
    }
  }
  return new Blob([buffer], { type: 'audio/wav' })
}

function audioUrl(durationMs: number): string {
  if (typeof URL.createObjectURL !== 'function') return 'mock://audio.wav'
  return URL.createObjectURL(synthWav(SAMPLE, durationMs))
}

export function createStenoMock(): StenoMock {
  const listeners = new Map<string, Set<Listener>>()
  const emit = (event: string, data: unknown) => listeners.get(event)?.forEach((cb) => cb(data))
  let seq = 3
  let failNext: StenoErrorCode | null = null
  let speed = 1
  let cancelled = false
  let running: string | null = null
  let recordTimer: ReturnType<typeof setInterval> | null = null
  let recordStartedAt = 0

  const now = Date.now()
  const recordings: Recording[] = [
    {
      id: '1',
      title: 'Совет котиков.m4a',
      durationMs: 45_000,
      createdAt: now - 86_400_000,
      status: 'DONE',
      audioPath: audioUrl(45_000),
    },
    {
      id: '2',
      title: 'Диалог у миски.mp3',
      durationMs: 45_000,
      createdAt: now - 3_600_000,
      status: 'NEW',
      audioPath: audioUrl(45_000),
    },
  ]
  const results = new Map<string, TranscriptResult>([
    ['1', makeResult('1', { numSpeakers: 0, enableLlmProtocol: true, enableLlmCorrection: false })],
  ])

  function makeResult(id: string, o: PipelineOptions): TranscriptResult {
    return {
      recordingId: id,
      transcript: structuredClone(SAMPLE),
      llmCorrected: o.enableLlmCorrection ? structuredClone(SAMPLE) : undefined,
      protocol: o.enableLlmProtocol ? SAMPLE_PROTOCOL : undefined,
      speakerNames: { speaker_1: 'Председатель' },
      rtf: 0.21,
    }
  }

  const find = (id: string) => {
    const r = recordings.find((x) => x.id === id)
    if (!r) throw new MockError('UNKNOWN', `Запись ${id} не найдена`)
    return r
  }
  const result = (id: string) => {
    const r = results.get(id)
    if (!r) throw new MockError('UNKNOWN', `Нет результата для ${id}`)
    return r
  }

  async function runPipeline(id: string, o: PipelineOptions) {
    const rec = find(id)
    running = id
    cancelled = false
    const fail = failNext
    failNext = null
    try {
      for (const stage of STAGES) {
        if (stage === 'LLM' && !o.enableLlmProtocol && !o.enableLlmCorrection) {
          emit('progress', { id, stage, fraction: 1 })
          continue
        }
        const steps = 10
        for (let i = 1; i <= steps; i++) {
          await sleep(STAGE_MS[stage] / steps / speed)
          if (cancelled) throw new MockError('CANCELLED', 'Обработка отменена')
          if (fail && stage === 'DIARIZATION' && i === 5) {
            throw new MockError(fail, `Имитация ошибки ${fail}`)
          }
          emit('progress', { id, stage, fraction: i / steps })
        }
      }
      results.set(id, makeResult(id, o))
      rec.status = 'DONE'
      emit('completed', { id })
    } catch (e) {
      const err = e instanceof MockError ? e : new MockError('UNKNOWN', String(e))
      rec.status = err.code === 'CANCELLED' ? 'NEW' : 'ERROR'
      emit('failed', { id, stage: 'DIARIZATION', code: err.code, message: err.message })
    } finally {
      running = null
    }
  }

  const mock: StenoMock = {
    async prepareModels() {
      const files = [
        'asr/model.int8.onnx',
        'vad/silero_vad.onnx',
        'diarization/segmentation.onnx',
        'diarization/embedding.onnx',
      ]
      for (let i = 0; i < files.length; i++) {
        await sleep(250 / speed)
        emit('modelsProgress', { fraction: (i + 1) / files.length, file: files[i] })
      }
      return { ready: true }
    },
    async getPipelineDefaults() {
      // Копия PipelineConfig() из Kotlin — только для браузерного мока.
      return {
        numSpeakers: 0,
        clusterThreshold: 0.5,
        mergeGapMs: 700,
        maxSegmentMs: 20000,
        segmentPaddingMs: 150,
        minUtteranceMs: 200,
        numThreads: 4,
        enableDenoise: false,
        enableLlmProtocol: true,
        enableLlmCorrection: false,
        vadThreshold: 0.5,
        diarizationWindowShift: 0.1,
        regroupSpeakers: true,
        embeddingPaddingMs: 200,
        minSpeakerSilhouette: 0.1,
      }
    },
    async getModelsInfo() {
      return { items: structuredClone(MODELS) }
    },
    async listRecordings() {
      return { items: recordings.map((r) => ({ ...r })).sort((a, b) => b.createdAt - a.createdAt) }
    },
    async importAudio() {
      await sleep(300 / speed)
      const rec: Recording = {
        id: String(seq++),
        title: `Импорт ${new Date().toLocaleTimeString('ru-RU')}.wav`,
        durationMs: 45_000,
        createdAt: Date.now(),
        status: 'NEW',
        audioPath: audioUrl(45_000),
      }
      recordings.push(rec)
      return { ...rec }
    },
    async startRecording() {
      recordStartedAt = Date.now()
      recordTimer = setInterval(() => {
        const t = (Date.now() - recordStartedAt) / 1000
        emit('recordingLevel', { level: Math.abs(Math.sin(t * 3)) * (0.4 + 0.6 * Math.random()) })
      }, 100)
    },
    async stopRecording() {
      if (recordTimer) clearInterval(recordTimer)
      recordTimer = null
      const durationMs = Math.max(1000, Date.now() - recordStartedAt)
      const rec: Recording = {
        id: String(seq++),
        title: `Запись ${new Date().toLocaleString('ru-RU')}`,
        durationMs,
        createdAt: Date.now(),
        status: 'NEW',
        audioPath: audioUrl(durationMs),
      }
      recordings.push(rec)
      return { ...rec }
    },
    async deleteRecording({ id }) {
      const i = recordings.findIndex((r) => r.id === id)
      if (i >= 0) recordings.splice(i, 1)
      results.delete(id)
    },
    async process({ id, options }) {
      if (running) throw new MockError('UNKNOWN', 'Уже идёт другая обработка')
      find(id).status = 'PROCESSING'
      void runPipeline(id, options)
    },
    async cancel() {
      cancelled = true
    },
    async getResult({ id }) {
      return structuredClone(result(id))
    },
    async updateUtterance({ id, index, text, speakerId }) {
      const u = result(id).transcript.transcript[index]
      if (!u) throw new MockError('UNKNOWN', `Нет реплики ${index}`)
      if (text !== undefined) u.text = text
      if (speakerId !== undefined) u.speaker_id = speakerId
    },
    async renameSpeaker({ id, speakerId, name }) {
      result(id).speakerNames[speakerId] = name
    },
    async exportResult({ id, format }: { id: string; format: ExportFormat }) {
      // Строгий JSON собирает Kotlin; мок лишь печатает сырой объект для отладки.
      console.info('[steno.mock] export', format, result(id).transcript)
      return { saved: true }
    },
    async share({ id, format }) {
      console.info('[steno.mock] share', id, format)
    },
    addListener(event: string, cb: Listener): Promise<PluginListenerHandle> {
      if (!listeners.has(event)) listeners.set(event, new Set())
      listeners.get(event)!.add(cb)
      return Promise.resolve({ remove: async () => void listeners.get(event)?.delete(cb) })
    },
    devFailNext(code) {
      failNext = code
    },
    devSetSpeed(multiplier) {
      speed = multiplier
    },
  } as StenoMock
  return mock
}
