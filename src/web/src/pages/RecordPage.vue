<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRecordingsStore } from '@/stores/recordings'
import { ru } from '@/i18n/ru'
import { formatMs } from '@/utils/time'

const store = useRecordingsStore()
const { isRecording, level, recordStartedAt, error } = store
const router = useRouter()
const now = ref(Date.now())
const ticker = setInterval(() => (now.value = Date.now()), 200)
const elapsed = computed(() => (recordStartedAt.value ? now.value - recordStartedAt.value : 0))
const displayLevel = computed(() => Math.min(1, level.value * 1.7))
const pressed = ref(false)

async function toggle() {
  if (!isRecording.value) await store.startRecording()
  else {
    const rec = await store.stopRecording()
    if (rec) await router.replace(`/processing/${rec.id}`)
  }
}

onBeforeUnmount(() => {
  clearInterval(ticker)
  if (isRecording.value) void store.stopRecording()
})
</script>

<template>
  <div class="page record-page">
    <header class="topbar compact">
      <RouterLink class="back-button" to="/library">‹</RouterLink>
      <span class="topbar-title">{{ ru.record.title }}</span>
      <span class="topbar-spacer" />
    </header>
    <main class="screen record-screen">
      <section class="record-intro">
        <p class="eyebrow">Новая запись</p>
        <h1>Готовы<br /><span>запечатлеть разговор?</span></h1>
        <p class="muted">{{ ru.record.hint }}</p>
      </section>
      <button
        class="record-orb"
        :class="{ recording: isRecording, pressed }"
        :aria-label="isRecording ? ru.record.stop : ru.record.start"
        @pointerdown="pressed = true"
        @pointerup="pressed = false"
        @pointercancel="pressed = false"
        @pointerleave="pressed = false"
        @click="toggle"
      >
        <div class="orb-ring ring-one" />
        <div class="orb-ring ring-two" />
        <div class="orb-core">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 14.5a3.5 3.5 0 0 0 3.5-3.5V6a3.5 3.5 0 0 0-7 0v5a3.5 3.5 0 0 0 3.5 3.5Z" />
            <path d="M18.5 11a6.5 6.5 0 0 1-13 0M12 17.5V21M8.5 21h7" />
          </svg>
        </div>
      </button>
      <div class="timer mono">{{ formatMs(elapsed) }}</div>
      <div class="meter">
        <div class="meter-fill" :style="{ width: `${Math.round(displayLevel * 100)}%` }" />
      </div>
      <button class="record-button" :class="{ 'is-recording': isRecording }" @click="toggle">
        <span class="record-button-icon">{{ isRecording ? '■' : '●' }}</span
        >{{ isRecording ? ru.record.stop : ru.record.start }}
      </button>
      <p v-if="error" class="inline-error">{{ error }}</p>
    </main>
  </div>
</template>

<style scoped>
.record-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 7vh;
  text-align: center;
}
.record-intro h1 {
  margin: 0;
  font-size: clamp(32px, 9vw, 48px);
  line-height: 0.98;
  letter-spacing: -0.07em;
}
.record-intro h1 span {
  color: var(--app-primary);
}
.record-intro p:last-child {
  max-width: 290px;
  margin: 14px auto 0;
  line-height: 1.5;
}
.record-orb {
  position: relative;
  display: grid;
  place-items: center;
  width: 188px;
  height: 188px;
  margin: 32px 0 8px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  transition: transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.record-orb::after {
  position: absolute;
  width: 116px;
  height: 116px;
  border: 2px solid color-mix(in srgb, var(--app-primary) 40%, transparent);
  border-radius: 50%;
  content: '';
  opacity: 0;
  transform: scale(0.75);
}
.record-orb.pressed {
  transform: scale(0.91);
}
.record-orb.pressed::after {
  animation: orb-press 0.42s ease-out;
}
.orb-core {
  z-index: 1;
  display: grid;
  place-items: center;
  width: 116px;
  height: 116px;
  border: 8px solid var(--app-bg);
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #8177f5, #6157e8);
  box-shadow: 0 18px 34px rgba(97, 87, 232, 0.3);
}
.orb-core svg {
  width: 43px;
  height: 43px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}
.orb-ring {
  position: absolute;
  border: 1px solid rgba(97, 87, 232, 0.2);
  border-radius: 50%;
}
.ring-one {
  width: 156px;
  height: 156px;
}
.ring-two {
  width: 188px;
  height: 188px;
}
.recording .ring-one {
  animation: breathe 1.5s ease-in-out infinite;
}
.recording .ring-two {
  animation: breathe 1.5s 0.35s ease-in-out infinite;
}
@keyframes breathe {
  50% {
    transform: scale(1.1);
    opacity: 0.35;
  }
}
@keyframes orb-press {
  0% {
    opacity: 0.65;
    transform: scale(0.82);
  }
  100% {
    opacity: 0;
    transform: scale(1.45);
  }
}
.timer {
  color: var(--app-ink);
  font-size: 42px;
  font-weight: 850;
  letter-spacing: -0.06em;
}
.meter {
  width: min(100%, 280px);
  height: 6px;
  margin-top: 16px;
  border-radius: 99px;
  background: var(--app-border);
  overflow: hidden;
}
.meter-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #8177f5, #ed725d, #17a86b);
  transition: width 0.16s ease-out;
}
.record-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: min(100%, 280px);
  min-height: 54px;
  margin-top: 22px;
  border: 0;
  border-radius: 18px;
  color: var(--app-bg);
  background: var(--app-ink);
  font: inherit;
  font-weight: 750;
}
.record-button.is-recording {
  color: #fff;
  background: #e76470;
}
.record-button:active {
  transform: scale(0.97);
}
.record-button-icon {
  font-size: 13px;
}
.inline-error {
  color: #ce4c5c;
}
</style>
