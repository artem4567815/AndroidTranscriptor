<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import type { Utterance } from '@/native'
import { formatMs } from '@/utils/time'
import { speakerColorVar } from '@/utils/speakers'
import SpeakerChip from './SpeakerChip.vue'

const props = defineProps<{
  utterance: Utterance
  index: number
  active: boolean
  names: Record<string, string>
}>()
const emit = defineEmits<{
  seek: [startMs: number]
  longpress: [index: number]
  speaker: [index: number]
}>()

const color = computed(() => speakerColorVar(props.utterance.speaker_id))

// Долгое нажатие без Gesture-API: 500 мс удержания, отмена при движении/отпускании.
let timer: ReturnType<typeof setTimeout> | null = null
let longFired = false
function down() {
  longFired = false
  timer = setTimeout(() => {
    longFired = true
    emit('longpress', props.index)
  }, 500)
}
function up() {
  if (timer) clearTimeout(timer)
  timer = null
}
function click() {
  if (!longFired) emit('seek', props.utterance.start_ms)
}
onBeforeUnmount(up)
</script>

<template>
  <div
    class="utterance"
    :class="{ active }"
    :style="{ '--u-color': color }"
    role="button"
    :aria-current="active ? 'true' : undefined"
    @pointerdown="down"
    @pointerup="up"
    @pointerleave="up"
    @pointercancel="up"
    @contextmenu.prevent
    @click="click"
  >
    <div class="head">
      <SpeakerChip
        :speaker-id="utterance.speaker_id"
        :names="names"
        @click="emit('speaker', index)"
      />
      <span class="time mono"
        >{{ formatMs(utterance.start_ms) }} – {{ formatMs(utterance.end_ms) }}</span
      >
    </div>
    <p class="text">{{ utterance.text || '…' }}</p>
  </div>
</template>

<style scoped>
.utterance {
  padding: 10px 14px 10px 12px;
  border-left: 4px solid var(--u-color);
  margin: 6px 12px;
  border-radius: 8px;
  background: var(--ion-color-step-50, var(--ion-background-color));
  user-select: none;
  -webkit-user-select: none;
  transition: background 0.15s;
}
.utterance.active {
  background: var(--utterance-active-bg);
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.time {
  font-size: 12px;
  color: var(--ion-color-medium);
}
.text {
  margin: 6px 0 0;
  line-height: 1.45;
}
</style>
