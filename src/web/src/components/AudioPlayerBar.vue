<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { formatMs } from '@/utils/time'

const props = defineProps<{ src: string; title: string; durationMs: number; seekToMs: number | null }>()
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
    <div class="player-content">
      <div class="track-info">
        <strong>{{ props.title }}</strong>
        <span>{{ formatMs(positionMs) }} / {{ formatMs(durationMs) }}</span>
      </div>
      <div class="player-controls">
        <button class="player-button skip" aria-label="-5 с" @click="seek(positionMs - 5000)">↶</button>
        <button class="player-button main" aria-label="Пуск/пауза" @click="toggle"><svg viewBox="0 0 24 24"><path v-if="!playing" d="m9 6 9 6-9 6V6Z" /><path v-else d="M8 6v12M16 6v12" /></svg></button>
        <button class="player-button skip" aria-label="+5 с" @click="seek(positionMs + 5000)">↷</button>
      </div>
    </div>
    <input class="seek-range" type="range" min="0" :max="Math.max(1, durationMs)" :value="positionMs" :style="{ '--progress': `${Math.min(100, (positionMs / Math.max(1, durationMs)) * 100)}%` }" aria-label="Позиция" @input="seek(Number(($event.target as HTMLInputElement).value))" />
  </div>
</template>

<style scoped>
.player {
  position: relative;
  padding: 0 16px max(12px, env(safe-area-inset-bottom));
  border: 1px solid var(--app-border);
  border-radius: 22px;
  background: color-mix(in srgb, var(--app-surface-solid) 96%, transparent);
  backdrop-filter: blur(18px);
}
.player-content {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0 10px;
}
.track-info {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 4px;
}
.track-info strong {
  overflow: hidden;
  color: var(--app-ink);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.track-info span {
  color: var(--app-muted);
  font-size: 11px;
}
.player-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.player-button {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  color: var(--app-muted);
  background: transparent;
  font: inherit;
  font-size: 22px;
}
.player-button.main {
  width: 52px;
  height: 52px;
  color: #fff;
  background: linear-gradient(145deg, var(--app-primary-soft), var(--app-primary));
  box-shadow: 0 8px 18px rgba(97,87,232,.28);
}
.player-button:active { transform: scale(.9); }
.player-button svg {
  width: 24px;
  height: 24px;
  fill: currentColor;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}
.seek-range {
  width: 100%;
  height: 4px;
  margin: 0;
  appearance: none;
  border-radius: 999px;
  outline: 0;
  background: linear-gradient(90deg, color-mix(in srgb, var(--app-primary) 88%, transparent) 0%, color-mix(in srgb, var(--app-primary) 88%, transparent) var(--progress, 0%), color-mix(in srgb, var(--app-ink) 16%, transparent) var(--progress, 0%), color-mix(in srgb, var(--app-ink) 16%, transparent) 100%);
  cursor: pointer;
}
.seek-range::-webkit-slider-thumb {
  width: 14px;
  height: 14px;
  appearance: none;
  border: 3px solid color-mix(in srgb, var(--app-primary) 38%, transparent);
  border-radius: 50%;
  background: var(--app-primary);
  box-shadow: 0 2px 8px rgba(97, 87, 232, .35);
  transition: transform .2s ease, box-shadow .2s ease;
}
.seek-range:active::-webkit-slider-thumb { transform: scale(1.3); box-shadow: 0 0 0 5px color-mix(in srgb, var(--app-primary) 18%, transparent); }
.seek-range::-moz-range-thumb {
  width: 10px;
  height: 10px;
  border: 3px solid color-mix(in srgb, var(--app-primary) 38%, transparent);
  border-radius: 50%;
  background: var(--app-primary);
}
</style>
