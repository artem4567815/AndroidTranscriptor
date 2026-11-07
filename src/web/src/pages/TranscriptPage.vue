<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useTranscriptStore } from '@/stores/transcript'
import { useRecordingsStore } from '@/stores/recordings'
import AudioPlayerBar from '@/components/AudioPlayerBar.vue'
import UtteranceCard from '@/components/UtteranceCard.vue'
import { playableSrc, type ExportFormat } from '@/native'
import { ru } from '@/i18n/ru'
import ToastMessage from '@/components/ToastMessage.vue'
import DownloadDialog from '@/components/DownloadDialog.vue'
import RecordingDetailsDialog from '@/components/RecordingDetailsDialog.vue'

const props = defineProps<{ id: string }>()
const store = useTranscriptStore()
const recordings = useRecordingsStore()
const recording = computed(() => recordings.byId(props.id))
const audioSrc = computed(() => (recording.value ? playableSrc(recording.value.audioPath) : ''))
const tab = ref<'transcript' | 'protocol'>('transcript')
const seekToMs = ref<number | null>(null)
const toastOpen = ref(false)
const downloadOpen = ref(false)
const isPlaying = ref(false)
const detailsOpen = ref(false)
onMounted(async () => {
  if (!recordings.items.value.length) await recordings.refresh()
  await store.load(props.id)
})
watch([store.activeIndex, isPlaying], ([index, playing]) => {
  if (!playing || index < 0) return
  document
    .querySelector<HTMLElement>(`[data-utterance-index="${index}"]`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
})
function seek(ms: number) {
  seekToMs.value = ms + Math.random() * 0.001
}
async function editText(index: number) {
  const text = window.prompt(ru.transcript.editText, store.utterances.value[index].text)
  if (text !== null) await store.updateText(index, text.trim())
}
async function exportAs(format: ExportFormat) {
  if (await store.exportAs(format)) {
    toastOpen.value = true
    window.setTimeout(() => (toastOpen.value = false), 2200)
  }
}
async function downloadAudio() {
  if (!audioSrc.value) return
  const title = recording.value?.title ?? 'recording'
  const baseName = title.replace(/\.[^/.]+$/, '') || 'recording'
  let downloadUrl = audioSrc.value
  let objectUrl: string | null = null
  let extension = title.match(/\.([a-z0-9]+)$/i)?.[1]?.toLowerCase() ?? 'audio'

  try {
    const response = await fetch(audioSrc.value)
    if (!response.ok) throw new Error(`Audio download failed: ${response.status}`)
    const blob = await response.blob()
    const mimeExtension: Record<string, string> = {
      'audio/wav': 'wav',
      'audio/x-wav': 'wav',
      'audio/mpeg': 'mp3',
      'audio/mp4': 'm4a',
      'audio/ogg': 'ogg',
      'audio/webm': 'webm',
    }
    extension = mimeExtension[blob.type.toLowerCase()] ?? extension
    objectUrl = URL.createObjectURL(blob)
    downloadUrl = objectUrl
  } catch (error) {
    // Native file URLs may not be fetchable from the WebView; the anchor still works there.
    console.warn('[transcript] Could not inspect audio format before download', error)
  }

  const link = document.createElement('a')
  link.href = downloadUrl
  link.download = `${baseName}.${extension}`
  link.click()
  if (objectUrl) window.setTimeout(() => URL.revokeObjectURL(objectUrl as string), 1000)
  downloadOpen.value = false
}
function downloadJson() {
  downloadOpen.value = false
  void exportAs('json')
}
async function saveDetails(title: string, names: Record<string, string>) {
  recordings.rename(props.id, title)
  await Promise.all(
    Object.entries(names).map(([speakerId, name]) => store.renameSpeaker(speakerId, name)),
  )
  detailsOpen.value = false
}
</script>

<template>
  <div class="page transcript-page">
    <header class="topbar compact">
      <RouterLink class="back-button" to="/library">‹</RouterLink
      ><button class="topbar-title title-button" @click="detailsOpen = true">
        {{ recording?.title ?? ru.transcript.title }}</button
      ><button class="icon-button" aria-label="Скачать" @click="downloadOpen = true">⇩</button>
    </header>
    <main class="screen transcript-screen">
      <div class="tabs">
        <button :class="{ active: tab === 'transcript' }" @click="tab = 'transcript'">
          {{ ru.transcript.tabTranscript }}</button
        ><button :class="{ active: tab === 'protocol' }" @click="tab = 'protocol'">
          {{ ru.transcript.tabProtocol }}
        </button>
      </div>
      <p v-if="store.loading.value" class="loading">Загружаем расшифровку…</p>
      <p v-else-if="store.error.value" class="inline-error">{{ store.error.value }}</p>
      <template v-else-if="store.result.value">
        <div v-if="tab === 'transcript'" class="utterances">
          <div class="transcript-meta">
            <span class="result-pill"
              ><b>{{ store.utterances.value.length }}</b> реплик <em>·</em> RTF
              {{ store.result.value.rtf.toFixed(2) }}</span
            ><label v-if="store.result.value.llmCorrected" class="corrected-toggle"
              ><input v-model="store.showCorrected.value" type="checkbox" />
              <span>LLM-версия</span></label
            >
          </div>
          <UtteranceCard
            v-for="(u, index) in store.utterances.value"
            :key="`${index}-${u.start_ms}`"
            :utterance="u"
            :index="index"
            :active="index === store.activeIndex.value"
            :names="store.speakerNames.value"
            @seek="seek"
            @longpress="editText"
            @speaker="editText"
          />
        </div>
        <article v-else class="protocol surface">
          <pre v-if="store.result.value.protocol">{{ store.result.value.protocol }}</pre>
          <p v-else class="muted">{{ ru.transcript.noProtocol }}</p>
        </article>
      </template>
    </main>
    <footer v-if="recording" class="player-dock">
      <AudioPlayerBar
        :src="audioSrc"
        :title="recording.title"
        :duration-ms="recording.durationMs"
        :seek-to-ms="seekToMs"
        :markers="store.utterances.value.map((utterance) => utterance.start_ms)"
        @position="store.positionMs.value = $event"
        @playing="isPlaying = $event"
      />
    </footer>
    <ToastMessage :open="toastOpen" :message="ru.transcript.exported" />
    <DownloadDialog
      :open="downloadOpen"
      title="Скачать запись"
      @audio="downloadAudio"
      @json="downloadJson"
      @cancel="downloadOpen = false"
    />
    <RecordingDetailsDialog
      :open="detailsOpen"
      :title="recording?.title ?? ru.transcript.title"
      :speaker-ids="store.speakerIds.value"
      :names="store.speakerNames.value"
      @save="saveDetails"
      @cancel="detailsOpen = false"
    />
  </div>
</template>

<style scoped>
.transcript-screen {
  padding-top: 10px;
  padding-bottom: 170px;
}
.tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 5px;
  padding: 5px;
  border: 1px solid var(--app-border);
  border-radius: 15px;
  background: var(--app-surface);
}
.title-button {
  width: min(70%, 360px);
  overflow: hidden;
  padding: 0 8px;
  border: 0;
  color: var(--app-ink);
  background: transparent;
  font-size: 17px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tabs button {
  padding: 11px;
  border: 0;
  border-radius: 11px;
  color: var(--app-muted);
  background: transparent;
  font: inherit;
  font-size: 13px;
  font-weight: 750;
}
.tabs button.active {
  color: var(--app-ink);
  background: var(--app-surface-solid);
  box-shadow: 0 4px 12px rgba(74, 64, 146, 0.08);
}
.transcript-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 18px 2px 6px;
  color: var(--app-muted);
  font-size: 12px;
}
.result-pill b {
  color: var(--app-ink);
}
.result-pill em {
  padding: 0 4px;
  font-style: normal;
  opacity: 0.5;
}
.corrected-toggle {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--app-ink);
  cursor: pointer;
}
.corrected-toggle input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.corrected-toggle span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.corrected-toggle span::before {
  width: 36px;
  height: 21px;
  border-radius: 99px;
  background: var(--app-border);
  box-shadow: inset 0 0 0 1px var(--app-border);
  content: '';
  transition: background 0.2s ease;
}
.corrected-toggle span::after {
  position: absolute;
  width: 15px;
  height: 15px;
  margin-left: 3px;
  border-radius: 50%;
  background: var(--app-muted);
  content: '';
  transition:
    transform 0.2s ease,
    background 0.2s ease;
}
.corrected-toggle input:checked + span::before {
  background: var(--app-primary);
}
.corrected-toggle input:checked + span::after {
  transform: translateX(15px);
  background: #fff;
}
.protocol {
  margin-top: 20px;
  padding: 20px;
}
.protocol pre {
  white-space: pre-wrap;
  font: inherit;
  line-height: 1.55;
}
.loading {
  padding: 50px 0;
  text-align: center;
  color: var(--app-muted);
}
.player-dock {
  position: fixed;
  right: 12px;
  bottom: max(12px, env(safe-area-inset-bottom));
  left: 12px;
  z-index: 4;
  border-radius: 22px;
  background: transparent;
  box-shadow: 0 16px 40px rgba(14, 14, 28, 0.22);
}
</style>
