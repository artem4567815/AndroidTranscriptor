import { ref, watch } from 'vue'
import {
  getSteno,
  type ModelInfo,
  type PipelineDefaults,
  type PipelineOptions,
  type StenoErrorCode,
} from '@/native'
import type { StenoMockControls } from '@/native/steno.mock'
import { errorMessage } from '@/utils/errors'

// v2: значения параметров обработки больше не хранятся «на всякий случай» — только то, что поменял пользователь.
const KEY = 'steno.settings.v2'

/**
 * Параметры обработки, которые можно подбирать на экране настроек. Значения по умолчанию берутся
 * из Kotlin (PipelineConfig) через getPipelineDefaults — единственный источник правды.
 * Не тронутый пользователем параметр в Kotlin не отправляется.
 */
export const TUNABLES = [
  { key: 'numThreads', kind: 'int', min: 1, max: 8, step: 1 },
  { key: 'clusterThreshold', kind: 'float', min: 0.1, max: 1, step: 0.05 },
  { key: 'vadThreshold', kind: 'float', min: 0.1, max: 0.9, step: 0.05 },
  { key: 'regroupSpeakers', kind: 'bool' },
  { key: 'minSpeakerSilhouette', kind: 'float', min: 0, max: 0.5, step: 0.02 },
  { key: 'embeddingPaddingMs', kind: 'int', min: 0, max: 400, step: 50 },
  { key: 'mergeGapMs', kind: 'int', min: 0, max: 1500, step: 100 },
  { key: 'diarizationWindowShift', kind: 'float', min: 0.05, max: 0.5, step: 0.05 },
] as const

export type TunableKey = (typeof TUNABLES)[number]['key']
export type TunableValue = number | boolean
type Overrides = Partial<Record<TunableKey, TunableValue>>

interface Persisted {
  theme: 'light' | 'dark' | 'system'
  numSpeakers: number
  enableLlmProtocol: boolean
  enableLlmCorrection: boolean
  overrides: Overrides
}

export const DEFAULTS: Persisted = {
  theme: 'light',
  numSpeakers: 0,
  enableLlmProtocol: true,
  enableLlmCorrection: false,
  overrides: {},
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
const overrides = ref<Overrides>({ ...s.overrides })
const pipelineDefaults = ref<PipelineDefaults | null>(null)
const defaultsError = ref<string | null>(null)
const models = ref<ModelInfo[]>([])
const modelsError = ref<string | null>(null)

watch(
  [theme, numSpeakers, enableLlmProtocol, enableLlmCorrection, overrides],
  () => {
    const data: Persisted = {
      theme: theme.value,
      numSpeakers: numSpeakers.value,
      enableLlmProtocol: enableLlmProtocol.value,
      enableLlmCorrection: enableLlmCorrection.value,
      overrides: overrides.value,
    }
    try {
      localStorage.setItem(KEY, JSON.stringify(data))
    } catch {
      /* приватный режим браузера — настройки живут до перезагрузки */
    }
  },
  { deep: true },
)

/** Текущее значение параметра: поправка пользователя или значение по умолчанию из Kotlin. */
function tunableValue(key: TunableKey): TunableValue | undefined {
  return overrides.value[key] ?? pipelineDefaults.value?.[key]
}

function setTunable(key: TunableKey, value: TunableValue) {
  overrides.value = { ...overrides.value, [key]: value }
}

function isChanged(key: TunableKey): boolean {
  return key in overrides.value && overrides.value[key] !== pipelineDefaults.value?.[key]
}

function resetTunable(key: TunableKey) {
  const next = { ...overrides.value }
  delete next[key]
  overrides.value = next
}

async function loadDefaults() {
  defaultsError.value = null
  try {
    pipelineDefaults.value = await (await getSteno()).getPipelineDefaults()
  } catch (e) {
    defaultsError.value = errorMessage(e)
  }
}

function pipelineOptions(): PipelineOptions {
  return {
    numSpeakers: numSpeakers.value,
    enableLlmProtocol: enableLlmProtocol.value,
    enableLlmCorrection: enableLlmCorrection.value,
    // Только то, что пользователь поменял сам; остальное Kotlin берёт из PipelineConfig.
    ...(overrides.value as Partial<PipelineOptions>),
  }
}

function resetDefaults() {
  theme.value = DEFAULTS.theme
  numSpeakers.value = DEFAULTS.numSpeakers
  enableLlmProtocol.value = DEFAULTS.enableLlmProtocol
  enableLlmCorrection.value = DEFAULTS.enableLlmCorrection
  overrides.value = {}
}

function setTheme(value: Persisted['theme']) {
  theme.value = value
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
    overrides,
    pipelineDefaults,
    defaultsError,
    tunableValue,
    setTunable,
    isChanged,
    resetTunable,
    loadDefaults,
    models,
    modelsError,
    pipelineOptions,
    resetDefaults,
    setTheme,
    loadModels,
    isMock,
    devFailNext,
    devSetSpeed,
  }
}
