import { computed, reactive, ref } from 'vue'
import {
  getSteno,
  STAGES,
  type FailedEvent,
  type PipelineOptions,
  type Stage,
  type StenoPlugin,
} from '@/native'
import { errorMessage } from '@/utils/errors'
import { ru } from '@/i18n/ru'
import { useRecordingsStore } from './recordings'

export interface StageState {
  fraction: number
  done: boolean
}

function emptyStages(): Record<Stage, StageState> {
  return Object.fromEntries(STAGES.map((s) => [s, { fraction: 0, done: false }])) as Record<
    Stage,
    StageState
  >
}

const recordingId = ref<string | null>(null)
const running = ref(false)
const cancelRequested = ref(false)
const completed = ref(false)
const failure = ref<(FailedEvent & { text: string }) | null>(null)
const stages = reactive(emptyStages())
const currentStage = ref<Stage | null>(null)
const startedAt = ref<number | null>(null)
const finishedAt = ref<number | null>(null)
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null
let listening: Promise<void> | null = null
let listeningPlugin: StenoPlugin | null = null

const elapsedMs = computed(() =>
  startedAt.value ? (finishedAt.value ?? now.value) - startedAt.value : 0,
)

function resetStages() {
  Object.assign(stages, emptyStages())
  currentStage.value = null
}

function stopTicker() {
  if (ticker) clearInterval(ticker)
  ticker = null
  finishedAt.value = Date.now()
}

/** Подписка на события плагина — один раз за жизнь приложения. */
async function ensureListeners(): Promise<void> {
  const steno = await getSteno()
  if (listeningPlugin === steno && listening) {
    await listening
    return
  }
  listeningPlugin = steno
  listening = (async () => {
    await steno.addListener('progress', (e) => {
      if (e.id !== recordingId.value) return
      // Все этапы до текущего считаем завершёнными: события могут прийти пачкой.
      for (const s of STAGES) {
        if (s === e.stage) break
        stages[s] = { fraction: 1, done: true }
      }
      stages[e.stage] = { fraction: e.fraction, done: e.fraction >= 1 }
      currentStage.value = e.stage
    })
    await steno.addListener('completed', (e) => {
      if (e.id !== recordingId.value) return
      for (const s of STAGES) stages[s] = { fraction: 1, done: true }
      running.value = false
      completed.value = true
      stopTicker()
      useRecordingsStore().setStatus(e.id, 'DONE')
    })
    await steno.addListener('failed', (e) => {
      if (e.id !== recordingId.value) return
      running.value = false
      failure.value = { ...e, text: ru.errors[e.code] ?? ru.errors.UNKNOWN }
      stopTicker()
      useRecordingsStore().setStatus(e.id, e.code === 'CANCELLED' ? 'NEW' : 'ERROR')
    })
  })()
  await listening
}

/** Подготовить состояние страницы для записи (без запуска). */
function select(id: string) {
  if (recordingId.value === id) return
  recordingId.value = id
  running.value = false
  completed.value = false
  failure.value = null
  startedAt.value = null
  finishedAt.value = null
  resetStages()
}

async function start(id: string, options: PipelineOptions) {
  await ensureListeners()
  select(id)
  resetStages()
  failure.value = null
  completed.value = false
  cancelRequested.value = false
  running.value = true
  startedAt.value = Date.now()
  finishedAt.value = null
  now.value = Date.now()
  ticker = setInterval(() => (now.value = Date.now()), 250)
  useRecordingsStore().setStatus(id, 'PROCESSING')
  try {
    await (await getSteno()).process({ id, options })
  } catch (e) {
    running.value = false
    stopTicker()
    failure.value = {
      id,
      stage: 'DECODE',
      code: 'UNKNOWN',
      message: String(e),
      text: errorMessage(e),
    }
    useRecordingsStore().setStatus(id, 'ERROR')
  }
}

async function cancel() {
  if (!running.value || cancelRequested.value) return
  cancelRequested.value = true
  await (await getSteno()).cancel()
}

export function useProcessingStore() {
  return {
    recordingId,
    running,
    cancelRequested,
    completed,
    failure,
    stages,
    currentStage,
    elapsedMs,
    ensureListeners,
    select,
    start,
    cancel,
  }
}
