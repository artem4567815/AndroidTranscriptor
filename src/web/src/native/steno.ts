/**
 * Контракт моста Vue ↔ Kotlin (плагин `Steno`).
 *
 * Меняется только вместе с `StenoPlugin.kt` одним PR, с ревью ролей 1 и 3.
 * Через мост не ходит аудио и большие массивы: только id, пути, настройки, прогресс и JSON.
 */
import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export interface Utterance {
  speaker_id: string
  start_ms: number
  end_ms: number
  text: string
}
export interface Transcript {
  transcript: Utterance[]
}
export type Stage =
  'DECODE' | 'PREPROCESS' | 'VAD' | 'DIARIZATION' | 'ASR' | 'MERGE' | 'LLM' | 'SAVE'
export const STAGES: readonly Stage[] = [
  'DECODE',
  'PREPROCESS',
  'VAD',
  'DIARIZATION',
  'ASR',
  'MERGE',
  'LLM',
  'SAVE',
]
/** Этапы расшифровки на экране обработки. LLM-анализ идёт отдельно — уже после показа результата. */
export const PROCESSING_STAGES: readonly Stage[] = STAGES.filter((s) => s !== 'LLM')
export type RecordingStatus = 'NEW' | 'PROCESSING' | 'DONE' | 'ERROR'
export type ExportFormat = 'json' | 'txt' | 'srt'

/** Фиксированный список кодов ошибок `call.reject` (NO_SPACE — расширение для первого запуска). */
export type StenoErrorCode =
  | 'MODELS_MISSING'
  | 'NO_PERMISSION'
  | 'DECODE_FAILED'
  | 'OUT_OF_MEMORY'
  | 'CANCELLED'
  | 'NO_SPACE'
  | 'UNKNOWN'

export interface Recording {
  id: string
  title: string
  durationMs: number
  createdAt: number
  status: RecordingStatus
  /** Путь к файлу, который умеет играть WebView. */
  audioPath: string
}
export interface PipelineOptions {
  numSpeakers: number
  enableLlmProtocol: boolean
  enableLlmCorrection: boolean
  /** Расширение для SettingsPage; если не задано — значения из PipelineConfig по умолчанию. */
  numThreads?: number
  clusterThreshold?: number
  vadThreshold?: number
  /** Расширенные параметры диаризации (экран настроек → «Расширенные»). Не задано — значение из Kotlin. */
  regroupSpeakers?: boolean
  minSpeakerSilhouette?: number
  embeddingPaddingMs?: number
  mergeGapMs?: number
  diarizationWindowShift?: number
}

/** Значения PipelineConfig по умолчанию из Kotlin — единственный источник правды. */
export interface PipelineDefaults {
  numSpeakers: number
  clusterThreshold: number
  mergeGapMs: number
  maxSegmentMs: number
  segmentPaddingMs: number
  minUtteranceMs: number
  numThreads: number
  enableDenoise: boolean
  enableLlmProtocol: boolean
  enableLlmCorrection: boolean
  vadThreshold: number
  diarizationWindowShift: number
  regroupSpeakers: boolean
  embeddingPaddingMs: number
  minSpeakerSilhouette: number
}
export interface TranscriptResult {
  recordingId: string
  transcript: Transcript
  llmCorrected?: Transcript
  protocol?: string
  speakerNames: Record<string, string>
  rtf: number
  /** LLM-анализ ещё идёт: итоги и имена спикеров придут позже (событие `analysis`). */
  analysisPending?: boolean
}
/** Сведения о модели для SettingsPage (расширение контракта). */
export interface ModelInfo {
  id: string
  purpose: string
  version: string
  sizeBytes: number
  license: string
  present: boolean
}

export interface ProgressEvent {
  id: string
  stage: Stage
  fraction: number
}
export interface CompletedEvent {
  id: string
}
/** LLM-анализ расшифровки после её показа: running — идёт, false — закончился (итоги можно перечитать). */
export interface AnalysisEvent {
  id: string
  running: boolean
}
export interface FailedEvent {
  id: string
  stage: Stage
  code: StenoErrorCode
  message: string
}
export interface RecordingLevelEvent {
  /** 0..1 */
  level: number
}
export interface ModelsProgressEvent {
  /** 0..1 */
  fraction: number
  file: string
}

export interface StenoPlugin {
  /** Первый запуск: копирование моделей из assets в filesDir. */
  prepareModels(): Promise<{ ready: boolean }>
  getModelsInfo(): Promise<{ items: ModelInfo[] }>
  /** Значения параметров обработки по умолчанию (PipelineConfig в Kotlin). */
  getPipelineDefaults(): Promise<PipelineDefaults>
  listRecordings(): Promise<{ items: Recording[] }>
  /** Системный выбор файла. Отмена пользователем — reject с кодом CANCELLED. */
  importAudio(): Promise<Recording>
  startRecording(): Promise<void>
  stopRecording(): Promise<Recording>
  deleteRecording(o: { id: string }): Promise<void>
  /**
   * Резолвится сразу после старта; итог — событие `completed` или `failed`. После `completed`, если включены
   * итоги/правка LLM, идёт анализ: события `analysis` (running: true → false).
   */
  process(o: { id: string; options: PipelineOptions }): Promise<void>
  cancel(): Promise<void>
  getResult(o: { id: string }): Promise<TranscriptResult>
  updateUtterance(o: {
    id: string
    index: number
    text?: string
    speakerId?: string
  }): Promise<void>
  renameSpeaker(o: { id: string; speakerId: string; name: string }): Promise<void>
  /** Системный диалог сохранения; JSON собирает только Kotlin. */
  exportResult(o: { id: string; format: ExportFormat }): Promise<{ saved: boolean }>
  share(o: { id: string; format: ExportFormat }): Promise<void>
  addListener(e: 'progress', cb: (e: ProgressEvent) => void): Promise<PluginListenerHandle>
  addListener(e: 'completed', cb: (e: CompletedEvent) => void): Promise<PluginListenerHandle>
  addListener(e: 'failed', cb: (e: FailedEvent) => void): Promise<PluginListenerHandle>
  addListener(e: 'analysis', cb: (e: AnalysisEvent) => void): Promise<PluginListenerHandle>
  addListener(
    e: 'recordingLevel',
    cb: (e: RecordingLevelEvent) => void,
  ): Promise<PluginListenerHandle>
  addListener(
    e: 'modelsProgress',
    cb: (e: ModelsProgressEvent) => void,
  ): Promise<PluginListenerHandle>
}

export const Steno = registerPlugin<StenoPlugin>('Steno')
