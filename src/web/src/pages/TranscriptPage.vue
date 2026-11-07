<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  actionSheetController,
  alertController,
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonFooter,
  IonHeader,
  IonIcon,
  IonLabel,
  IonModal,
  IonPage,
  IonSegment,
  IonSegmentButton,
  IonSpinner,
  IonText,
  IonTitle,
  IonToggle,
  IonToolbar,
  toastController,
} from '@ionic/vue'
import { ellipsisVertical } from 'ionicons/icons'
import { storeToRefs } from 'pinia'
import AudioPlayerBar from '@/components/AudioPlayerBar.vue'
import SpeakerPicker from '@/components/SpeakerPicker.vue'
import UtteranceCard from '@/components/UtteranceCard.vue'
import { playableSrc, type ExportFormat } from '@/native'
import { useRecordingsStore } from '@/stores/recordings'
import { useTranscriptStore } from '@/stores/transcript'
import { ru } from '@/i18n/ru'
import { speakerLabel } from '@/utils/speakers'

const props = defineProps<{ id: string }>()
const store = useTranscriptStore()
const recordings = useRecordingsStore()
const {
  result,
  loading,
  error,
  utterances,
  activeIndex,
  speakerIds,
  speakerNames,
  showCorrected,
  editable,
} = storeToRefs(store)

const tab = ref<'transcript' | 'protocol'>('transcript')
const seekToMs = ref<number | null>(null)
const pickerIndex = ref<number | null>(null)
const pickerValue = ref('')
const list = ref<HTMLElement | null>(null)

const recording = computed(() => recordings.byId(props.id))
const audioSrc = computed(() => (recording.value ? playableSrc(recording.value.audioPath) : ''))

onMounted(async () => {
  if (!recordings.items.length) await recordings.refresh()
  await store.load(props.id)
})

// Прокрутка к текущей реплике, если она ушла за край экрана.
watch(activeIndex, async (i) => {
  if (i < 0 || tab.value !== 'transcript') return
  await nextTick()
  list.value
    ?.querySelector<HTMLElement>(`[data-index="${i}"]`)
    ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
})

function seek(ms: number) {
  // Новое значение каждый раз, чтобы повторный тап по той же реплике тоже перематывал.
  seekToMs.value = ms + Math.random() * 1e-3
}

async function toast(message: string) {
  const t = await toastController.create({ message, duration: 1800, position: 'top' })
  await t.present()
}

async function editText(index: number) {
  const u = utterances.value[index]
  const alert = await alertController.create({
    header: ru.transcript.editText,
    inputs: [{ name: 'text', type: 'textarea', value: u.text }],
    buttons: [
      { text: ru.common.cancel, role: 'cancel' },
      {
        text: ru.common.save,
        handler: (v: { text: string }) => void store.updateText(index, v.text.trim()),
      },
    ],
  })
  await alert.present()
}

function openPicker(index: number) {
  if (!editable.value) return
  pickerIndex.value = index
  pickerValue.value = utterances.value[index].speaker_id
}

async function onPick(speakerId: string) {
  const i = pickerIndex.value
  pickerIndex.value = null
  if (i !== null && speakerId !== utterances.value[i].speaker_id) {
    await store.changeSpeaker(i, speakerId)
  }
}

async function onLongPress(index: number) {
  if (!editable.value) return
  const sheet = await actionSheetController.create({
    header: utterances.value[index].text.slice(0, 60),
    buttons: [
      { text: ru.transcript.editText, handler: () => void editText(index) },
      { text: ru.transcript.changeSpeaker, handler: () => openPicker(index) },
      { text: ru.common.cancel, role: 'cancel' },
    ],
  })
  await sheet.present()
}

async function renameSpeakers() {
  const alert = await alertController.create({
    header: ru.transcript.renameSpeakers,
    inputs: speakerIds.value.map((id) => ({
      name: id,
      type: 'text' as const,
      placeholder: speakerLabel(id, {}),
      value: speakerNames.value[id] ?? '',
    })),
    buttons: [
      { text: ru.common.cancel, role: 'cancel' },
      {
        text: ru.common.save,
        handler: (v: Record<string, string>) => {
          for (const id of speakerIds.value) {
            const name = (v[id] ?? '').trim()
            if (name !== (speakerNames.value[id] ?? '')) void store.renameSpeaker(id, name)
          }
        },
      },
    ],
  })
  await alert.present()
}

async function doExport(format: ExportFormat) {
  if (await store.exportAs(format)) await toast(ru.transcript.exported)
}

async function openMenu() {
  const sheet = await actionSheetController.create({
    buttons: [
      { text: ru.transcript.renameSpeakers, handler: () => void renameSpeakers() },
      { text: ru.transcript.exportJson, handler: () => void doExport('json') },
      { text: ru.transcript.exportTxt, handler: () => void doExport('txt') },
      { text: ru.transcript.exportSrt, handler: () => void doExport('srt') },
      { text: `${ru.transcript.share} (JSON)`, handler: () => void store.share('json') },
      { text: `${ru.transcript.share} (TXT)`, handler: () => void store.share('txt') },
      { text: ru.common.cancel, role: 'cancel' },
    ],
  })
  await sheet.present()
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/library" :text="ru.common.back" />
        </ion-buttons>
        <ion-title>{{ recording?.title ?? ru.transcript.title }}</ion-title>
        <ion-buttons slot="end">
          <ion-button aria-label="Меню" :disabled="!result" @click="openMenu">
            <ion-icon slot="icon-only" :icon="ellipsisVertical" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
      <ion-toolbar>
        <ion-segment v-model="tab">
          <ion-segment-button value="transcript">
            <ion-label>{{ ru.transcript.tabTranscript }}</ion-label>
          </ion-segment-button>
          <ion-segment-button value="protocol">
            <ion-label>{{ ru.transcript.tabProtocol }}</ion-label>
          </ion-segment-button>
        </ion-segment>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <div v-if="loading" class="ion-padding ion-text-center"><ion-spinner /></div>
      <ion-text v-else-if="error" color="danger"
        ><p class="ion-padding">{{ error }}</p></ion-text
      >

      <template v-else-if="result">
        <div v-show="tab === 'transcript'" ref="list" class="center-block">
          <div class="meta muted">
            <span class="mono">{{ ru.transcript.rtf }}: {{ result.rtf.toFixed(2) }}</span>
            <ion-toggle v-if="result.llmCorrected" v-model="showCorrected" label-placement="start">
              {{ ru.transcript.showCorrected }}
            </ion-toggle>
          </div>
          <UtteranceCard
            v-for="(u, i) in utterances"
            :key="`${i}-${u.start_ms}`"
            :data-index="i"
            :utterance="u"
            :index="i"
            :active="i === activeIndex"
            :names="speakerNames"
            @seek="seek"
            @longpress="onLongPress"
            @speaker="openPicker"
          />
        </div>
        <div v-show="tab === 'protocol'" class="ion-padding center-block">
          <pre v-if="result.protocol" class="protocol">{{ result.protocol }}</pre>
          <p v-else class="muted">{{ ru.transcript.noProtocol }}</p>
        </div>
      </template>

      <ion-modal
        :is-open="pickerIndex !== null"
        :initial-breakpoint="0.5"
        :breakpoints="[0, 0.5, 0.9]"
        @did-dismiss="pickerIndex = null"
      >
        <ion-content>
          <ion-toolbar>
            <ion-title>{{ ru.transcript.changeSpeaker }}</ion-title>
          </ion-toolbar>
          <SpeakerPicker
            :speaker-ids="speakerIds"
            :names="speakerNames"
            :model-value="pickerValue"
            @update:model-value="onPick"
          />
        </ion-content>
      </ion-modal>
    </ion-content>

    <ion-footer v-if="recording">
      <AudioPlayerBar
        :src="audioSrc"
        :duration-ms="recording.durationMs"
        :seek-to-ms="seekToMs"
        @position="store.positionMs = $event"
      />
    </ion-footer>
  </ion-page>
</template>

<style scoped>
.meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px 0;
  font-size: 13px;
  min-height: 40px;
}
.meta ion-toggle {
  font-size: 13px;
}
.protocol {
  white-space: pre-wrap;
  font-family: inherit;
  line-height: 1.5;
}
</style>
