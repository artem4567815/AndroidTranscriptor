import { ref } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { getSteno, type Recording, type RecordingStatus } from '@/native'
import { errorCode, errorMessage } from '@/utils/errors'

const items = ref<Recording[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const isRecording = ref(false)
const level = ref(0)
const recordStartedAt = ref<number | null>(null)
let levelHandle: PluginListenerHandle | null = null

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const steno = await getSteno()
    items.value = (await steno.listRecordings()).items
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

  function upsert(rec: Recording) {
    const i = items.value.findIndex((r) => r.id === rec.id)
    if (i >= 0) items.value[i] = rec
    else items.value.unshift(rec)
  }

  /** Возвращает null, если пользователь закрыл диалог выбора файла. */
  async function importAudio(): Promise<Recording | null> {
    error.value = null
    try {
      const rec = await (await getSteno()).importAudio()
      upsert(rec)
      return rec
    } catch (e) {
      if (errorCode(e) === 'CANCELLED') return null
      error.value = errorMessage(e)
      return null
    }
  }

  async function startRecording() {
    error.value = null
    const steno = await getSteno()
    try {
      levelHandle ??= await steno.addListener('recordingLevel', (e) => (level.value = e.level))
      await steno.startRecording()
      isRecording.value = true
      recordStartedAt.value = Date.now()
    } catch (e) {
      error.value = errorMessage(e)
    }
  }

  async function stopRecording(): Promise<Recording | null> {
    const steno = await getSteno()
    try {
      const rec = await steno.stopRecording()
      upsert(rec)
      return rec
    } catch (e) {
      error.value = errorMessage(e)
      return null
    } finally {
      isRecording.value = false
      recordStartedAt.value = null
      level.value = 0
      await levelHandle?.remove()
      levelHandle = null
    }
  }

  async function remove(id: string) {
    try {
      await (await getSteno()).deleteRecording({ id })
      items.value = items.value.filter((r) => r.id !== id)
    } catch (e) {
      error.value = errorMessage(e)
    }
  }

  function byId(id: string): Recording | undefined {
    return items.value.find((r) => r.id === id)
  }

  function setStatus(id: string, status: RecordingStatus) {
    const r = byId(id)
    if (r) r.status = status
  }

export function useRecordingsStore() {
  return {
    items,
    loading,
    error,
    isRecording,
    level,
    recordStartedAt,
    refresh,
    importAudio,
    startRecording,
    stopRecording,
    remove,
    byId,
    setStatus,
  }
}
