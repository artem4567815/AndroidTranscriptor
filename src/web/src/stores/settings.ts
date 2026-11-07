import { ref, watch } from 'vue'
import { getSteno, type ModelInfo, type PipelineOptions, type StenoErrorCode } from '@/native'
import type { StenoMockControls } from '@/native/steno.mock'
import { errorMessage } from '@/utils/errors'

const KEY = 'steno.settings.v1'

interface Persisted {
  theme: 'light' | 'dark' | 'system'
  numSpeakers: number
  enableLlmProtocol: boolean
  enableLlmCorrection: boolean
  numThreads: number
  clusterThreshold: number
  vadThreshold: number
}

/** Значения по умолчанию совпадают с PipelineConfig и config/pipeline.default.json. */
export const DEFAULTS: Persisted = {
  theme: 'light',
  numSpeakers: 0,
  enableLlmProtocol: true,
  enableLlmCorrection: false,
  numThreads: 4,
  clusterThreshold: 0.5,
  vadThreshold: 0.5,
}

function load(): Persisted {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) ?? '{}') }
  } catch {
    return { ...DEFAULTS }
  }
}

const s = load()
const theme = ref<Persisted['theme']>(s.theme)
const numSpeakers = ref(s.numSpeakers)
const enableLlmProtocol = ref(s.enableLlmProtocol)
const enableLlmCorrection = ref(s.enableLlmCorrection)
const numThreads = ref(s.numThreads)
const clusterThreshold = ref(s.clusterThreshold)
const vadThreshold = ref(s.vadThreshold)
const models = ref<ModelInfo[]>([])
const modelsError = ref<string | null>(null)

watch(
    [
      theme,
      numSpeakers,
      enableLlmProtocol,
      enableLlmCorrection,
      numThreads,
      clusterThreshold,
      vadThreshold,
    ],
    () => {
      const data: Persisted = {
        theme: theme.value,
        numSpeakers: numSpeakers.value,
        enableLlmProtocol: enableLlmProtocol.value,
        enableLlmCorrection: enableLlmCorrection.value,
        numThreads: numThreads.value,
        clusterThreshold: clusterThreshold.value,
        vadThreshold: vadThreshold.value,
      }
      try {
        localStorage.setItem(KEY, JSON.stringify(data))
      } catch {
        /* приватный режим браузера — настройки живут до перезагрузки */
      }
    },
)

function pipelineOptions(): PipelineOptions {
    return {
      numSpeakers: numSpeakers.value,
      enableLlmProtocol: enableLlmProtocol.value,
      enableLlmCorrection: enableLlmCorrection.value,
      numThreads: numThreads.value,
      clusterThreshold: clusterThreshold.value,
      vadThreshold: vadThreshold.value,
}
  }

function resetDefaults() {
    theme.value = DEFAULTS.theme
    numSpeakers.value = DEFAULTS.numSpeakers
    enableLlmProtocol.value = DEFAULTS.enableLlmProtocol
    enableLlmCorrection.value = DEFAULTS.enableLlmCorrection
    numThreads.value = DEFAULTS.numThreads
    clusterThreshold.value = DEFAULTS.clusterThreshold
    vadThreshold.value = DEFAULTS.vadThreshold
}

async function loadModels() {
    modelsError.value = null
    try {
      models.value = (await (await getSteno()).getModelsInfo()).items
    } catch (e) {
      modelsError.value = errorMessage(e)
}
  }

  /** Ручки мока доступны только в dev-режиме браузера. */
const isMock = import.meta.env.DEV
async function mockControls(): Promise<StenoMockControls | null> {
    if (!import.meta.env.DEV) return null
    const p = (await getSteno()) as unknown as Partial<StenoMockControls>
    return typeof p.devFailNext === 'function' ? (p as StenoMockControls) : null
}
async function devFailNext(code: StenoErrorCode | null) {
    ;(await mockControls())?.devFailNext(code)
}
async function devSetSpeed(x: number) {
    ;(await mockControls())?.devSetSpeed(x)
}
export function useSettingsStore() {
  return {
    numSpeakers,
    theme,
    enableLlmProtocol,
    enableLlmCorrection,
    numThreads,
    clusterThreshold,
    vadThreshold,
    models,
    modelsError,
    pipelineOptions,
    resetDefaults,
    loadModels,
    isMock,
    devFailNext,
    devSetSpeed,
  }
}
