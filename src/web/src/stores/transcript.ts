import { computed, ref } from 'vue'
import {
  getSteno,
  type ExportFormat,
  type StenoPlugin,
  type TranscriptResult,
  type Utterance,
} from '@/native'
import { errorMessage } from '@/utils/errors'
import { activeUtteranceIndex } from '@/utils/time'

const recordingId = ref<string | null>(null)
const result = ref<TranscriptResult | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const positionMs = ref(0)
const showCorrected = ref(false)
/** LLM-анализ этой записи ещё идёт: итоги и имена спикеров подтянутся сами. */
const analysisPending = ref(false)
let listening: Promise<void> | null = null
let listeningPlugin: StenoPlugin | null = null

/** Подписка на окончание LLM-анализа — одна на экземпляр плагина. */
async function ensureAnalysisListener(): Promise<void> {
  const steno = await getSteno()
  if (listeningPlugin === steno && listening) return listening
  listeningPlugin = steno
  listening = steno
    .addListener('analysis', (e) => {
      if (e.id !== recordingId.value) return
      if (e.running) analysisPending.value = true
      else void refreshAnalysis(e.id)
    })
    .then(() => undefined)
  return listening
}

/**
 * Итоги, имена спикеров и правку LLM перечитываем, а реплики не трогаем: пользователь мог уже
 * поправить текст (правки сразу уходят в Kotlin, но так не сбивается открытый диалог и прокрутка).
 */
async function refreshAnalysis(id: string) {
  try {
    const fresh = await (await getSteno()).getResult({ id })
    const r = result.value
    if (recordingId.value !== id || !r) return
    r.protocol = fresh.protocol
    r.speakerNames = fresh.speakerNames
    r.llmCorrected = fresh.llmCorrected
    analysisPending.value = Boolean(fresh.analysisPending)
  } catch (e) {
    analysisPending.value = false
    error.value = errorMessage(e)
  }
}

/** Что показываем: сырой ASR (по умолчанию) или правку LLM по переключателю. */
const utterances = computed<Utterance[]>(() => {
  const r = result.value
  if (!r) return []
  return (showCorrected.value && r.llmCorrected ? r.llmCorrected : r.transcript).transcript
})
const activeIndex = computed(() => activeUtteranceIndex(utterances.value, positionMs.value))
const speakerIds = computed(() => {
  const ids = new Set<string>(result.value?.transcript.transcript.map((u) => u.speaker_id))
  Object.keys(result.value?.speakerNames ?? {}).forEach((id) => ids.add(id))
  return [...ids].sort((a, b) => a.localeCompare(b, 'en', { numeric: true }))
})
const speakerNames = computed(() => result.value?.speakerNames ?? {})
const editable = computed(() => !showCorrected.value || !result.value?.llmCorrected)

async function load(id: string) {
  recordingId.value = id
  loading.value = true
  error.value = null
  positionMs.value = 0
  try {
    await ensureAnalysisListener()
    result.value = await (await getSteno()).getResult({ id })
    analysisPending.value = Boolean(result.value.analysisPending)
  } catch (e) {
    result.value = null
    analysisPending.value = false
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function updateText(index: number, text: string) {
  const r = result.value
  if (!r || !recordingId.value) return
  await (await getSteno()).updateUtterance({ id: recordingId.value, index, text })
  r.transcript.transcript[index].text = text
}

async function changeSpeaker(index: number, speakerId: string) {
  const r = result.value
  if (!r || !recordingId.value) return
  await (await getSteno()).updateUtterance({ id: recordingId.value, index, speakerId })
  r.transcript.transcript[index].speaker_id = speakerId
}

async function renameSpeaker(speakerId: string, name: string) {
  const r = result.value
  if (!r || !recordingId.value) return
  await (await getSteno()).renameSpeaker({ id: recordingId.value, speakerId, name })
  r.speakerNames = { ...r.speakerNames, [speakerId]: name }
}

async function exportAs(format: ExportFormat | 'audio'): Promise<boolean> {
  if (!recordingId.value) return false
  try {
    return (await (await getSteno()).exportResult({ id: recordingId.value, format })).saved
  } catch (e) {
    error.value = errorMessage(e)
    return false
  }
}

async function share(format: ExportFormat) {
  if (!recordingId.value) return
  try {
    await (await getSteno()).share({ id: recordingId.value, format })
  } catch (e) {
    error.value = errorMessage(e)
  }
}

export function useTranscriptStore() {
  return {
    recordingId,
    result,
    loading,
    error,
    positionMs,
    showCorrected,
    analysisPending,
    utterances,
    activeIndex,
    speakerIds,
    speakerNames,
    editable,
    load,
    updateText,
    changeSpeaker,
    renameSpeaker,
    exportAs,
    share,
  }
}
