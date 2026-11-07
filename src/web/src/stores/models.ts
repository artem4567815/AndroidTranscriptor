import { ref } from 'vue'
import { getSteno } from '@/native'
import { errorMessage } from '@/utils/errors'

/** Состояние первого запуска: копирование моделей из assets. */
const ready = ref(false)
const preparing = ref(false)
const fraction = ref(0)
const file = ref('')
const error = ref<string | null>(null)

async function prepare(): Promise<boolean> {
  if (ready.value) return true
  preparing.value = true
  error.value = null
  const steno = await getSteno()
  const handle = await steno.addListener('modelsProgress', (e) => {
    fraction.value = e.fraction
    file.value = e.file
  })
  try {
    ready.value = (await steno.prepareModels()).ready
    return ready.value
  } catch (e) {
    error.value = errorMessage(e)
    return false
  } finally {
    preparing.value = false
    await handle.remove()
  }
}

export function useModelsStore() {
  return { ready, preparing, fraction, file, error, prepare }
}
