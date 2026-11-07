<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { IonButton, IonIcon, IonRange } from '@ionic/vue'
import { pause, play, playBack, playForward } from 'ionicons/icons'
import { formatMs } from '@/utils/time'

const props = defineProps<{ src: string; durationMs: number; seekToMs: number | null }>()
const emit = defineEmits<{ position: [ms: number] }>()

const audio = ref<HTMLAudioElement | null>(null)
const playing = ref(false)
const positionMs = ref(0)
const durationMs = ref(props.durationMs)

function toggle() {
  const a = audio.value
  if (!a) return
  if (a.paused) void a.play()
  else a.pause()
}
function seek(ms: number) {
  const a = audio.value
  if (!a) return
  a.currentTime = Math.max(0, ms) / 1000
  positionMs.value = ms
  emit('position', ms)
}
function onTime() {
  const a = audio.value
  if (!a) return
  positionMs.value = a.currentTime * 1000
  emit('position', positionMs.value)
}
function onMeta() {
  const d = audio.value?.duration
  if (d && Number.isFinite(d)) durationMs.value = d * 1000
}

watch(
  () => props.seekToMs,
  (ms) => {
    if (ms === null) return
    seek(ms)
    if (audio.value?.paused) void audio.value.play()
  },
)
onBeforeUnmount(() => audio.value?.pause())
defineExpose({ seek })
</script>

<template>
  <div class="player">
    <audio
      ref="audio"
      :src="src"
      preload="metadata"
      @timeupdate="onTime"
      @loadedmetadata="onMeta"
      @play="playing = true"
      @pause="playing = false"
      @ended="playing = false"
    />
    <div class="controls">
      <ion-button fill="clear" size="small" aria-label="-5 с" @click="seek(positionMs - 5000)">
        <ion-icon slot="icon-only" :icon="playBack" />
      </ion-button>
      <ion-button fill="clear" aria-label="Пуск/пауза" @click="toggle">
        <ion-icon slot="icon-only" :icon="playing ? pause : play" />
      </ion-button>
      <ion-button fill="clear" size="small" aria-label="+5 с" @click="seek(positionMs + 5000)">
        <ion-icon slot="icon-only" :icon="playForward" />
      </ion-button>
      <span class="mono time">{{ formatMs(positionMs) }} / {{ formatMs(durationMs) }}</span>
    </div>
    <ion-range
      :min="0"
      :max="Math.max(1, durationMs)"
      :value="positionMs"
      aria-label="Позиция"
      @ion-change="seek(Number($event.detail.value))"
    />
  </div>
</template>

<style scoped>
.player {
  padding: 4px 12px 0;
}
.controls {
  display: flex;
  align-items: center;
  gap: 4px;
}
.time {
  margin-left: auto;
  font-size: 13px;
  color: var(--ion-color-medium);
}
ion-range {
  padding-top: 0;
  --bar-height: 4px;
}
</style>
