<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonItem,
  IonList,
  IonListHeader,
  IonNote,
  IonPage,
  IonSelect,
  IonSelectOption,
  IonText,
  IonTitle,
  IonToggle,
  IonToolbar,
} from '@ionic/vue'
import { storeToRefs } from 'pinia'
import StageProgressList from '@/components/StageProgressList.vue'
import { useProcessingStore } from '@/stores/processing'
import { useRecordingsStore } from '@/stores/recordings'
import { useSettingsStore } from '@/stores/settings'
import { ru } from '@/i18n/ru'
import type { Stage } from '@/native'
import { formatMs } from '@/utils/time'

const props = defineProps<{ id: string }>()
const router = useRouter()
const processing = useProcessingStore()
const recordings = useRecordingsStore()
const settings = useSettingsStore()
const {
  running,
  completed,
  failure,
  stages,
  currentStage,
  elapsedMs,
  recordingId,
  cancelRequested,
} = storeToRefs(processing)
const { numSpeakers, enableLlmProtocol, enableLlmCorrection } = storeToRefs(settings)

const recording = computed(() => recordings.byId(props.id))
const isThis = computed(() => recordingId.value === props.id)
const showForm = computed(() => !isThis.value || (!running.value && !completed.value))
const speakerOptions = [0, 2, 3, 4, 5, 6]

/** Этапы, которые нативный код не умеет прерывать на середине. */
const UNINTERRUPTIBLE: readonly Stage[] = ['DIARIZATION']
const cancelText = computed(() => {
  const stage = currentStage.value
  return stage && UNINTERRUPTIBLE.includes(stage)
    ? ru.processing.cancelAfterStage(ru.stages[stage])
    : ru.processing.cancelling
})

onMounted(async () => {
  await processing.ensureListeners()
  if (!recordings.items.length) await recordings.refresh()
  // Если обработка этой записи уже идёт (например, вернулись на страницу) — показываем её.
  if (!running.value) processing.select(props.id)
})

watch(completed, (done) => {
  if (done && isThis.value) void router.replace(`/transcript/${props.id}`)
})

function start() {
  void processing.start(props.id, settings.pipelineOptions())
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/library" :text="ru.common.back" />
        </ion-buttons>
        <ion-title>{{ ru.processing.title }}</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <div class="center-block">
        <div class="ion-padding">
          <h2>{{ recording?.title ?? '…' }}</h2>
          <p v-if="recording" class="muted mono">{{ formatMs(recording.durationMs) }}</p>
        </div>

        <ion-list v-if="showForm && !running" inset>
          <ion-item>
            <ion-select v-model="numSpeakers" :label="ru.processing.speakers" interface="popover">
              <ion-select-option v-for="n in speakerOptions" :key="n" :value="n">
                {{ n === 0 ? ru.common.auto : n }}
              </ion-select-option>
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-toggle v-model="enableLlmProtocol">{{ ru.processing.llmProtocol }}</ion-toggle>
          </ion-item>
          <ion-item>
            <ion-toggle v-model="enableLlmCorrection">
              {{ ru.processing.llmCorrection }}
              <ion-note class="block">{{ ru.processing.llmCorrectionHint }}</ion-note>
            </ion-toggle>
          </ion-item>
        </ion-list>

        <template v-if="isThis && (running || completed || failure)">
          <ion-list-header>
            {{ ru.processing.elapsed }}: <span class="mono">&nbsp;{{ formatMs(elapsedMs) }}</span>
          </ion-list-header>
          <StageProgressList
            :stages="stages"
            :current="currentStage"
            :failed-stage="failure?.code !== 'CANCELLED' ? failure?.stage : null"
            :running="running"
          />
        </template>

        <div class="ion-padding">
          <ion-text v-if="isThis && failure" color="danger">
            <p>
              <strong>{{ ru.processing.failed }}.</strong> {{ failure.text }}
            </p>
          </ion-text>
          <ion-button
            v-if="running && isThis"
            expand="block"
            color="medium"
            :disabled="cancelRequested"
            @click="processing.cancel()"
          >
            {{ ru.common.cancel }}
          </ion-button>
          <ion-text v-if="running && isThis && cancelRequested" color="medium">
            <p class="ion-text-center">{{ cancelText }}</p>
          </ion-text>
          <ion-button v-else-if="!running" expand="block" @click="start">
            {{ failure && isThis ? ru.common.retry : ru.processing.start }}
          </ion-button>
          <ion-button
            v-if="completed && isThis"
            expand="block"
            fill="outline"
            :router-link="`/transcript/${id}`"
          >
            {{ ru.processing.openResult }}
          </ion-button>
        </div>
      </div>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.block {
  display: block;
}
</style>
