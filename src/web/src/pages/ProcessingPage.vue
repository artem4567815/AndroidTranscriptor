<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useProcessingStore } from '@/stores/processing'
import { useRecordingsStore } from '@/stores/recordings'
import { useSettingsStore } from '@/stores/settings'
import { STAGES } from '@/native'
import { ru } from '@/i18n/ru'
import { formatMs } from '@/utils/time'

const props = defineProps<{ id: string }>()
const router = useRouter()
const processing = useProcessingStore()
const recordings = useRecordingsStore()
const settings = useSettingsStore()
const recording = () => recordings.byId(props.id)
const speakerOptions = [0, 2, 3, 4, 5, 6]
const speakerOpen = ref(false)
const estimatedRemaining = computed(() => {
  if (!processing.running.value || processing.elapsedMs.value < 1000) return null
  const progress =
    STAGES.reduce((sum, stage) => sum + processing.stages[stage].fraction, 0) / STAGES.length
  if (progress < 0.05 || progress >= 1) return null
  return Math.max(1000, processing.elapsedMs.value * ((1 - progress) / progress))
})
onMounted(async () => {
  await processing.ensureListeners()
  if (!recordings.items.value.length) await recordings.refresh()
  if (!processing.running.value) processing.select(props.id)
})
watch(
  () => processing.completed.value,
  (done) => {
    if (done) void router.replace(`/transcript/${props.id}`)
  },
)
function start() {
  void processing.start(props.id, settings.pipelineOptions())
}
function selectSpeaker(count: number) {
  settings.numSpeakers.value = count
  speakerOpen.value = false
}
</script>

<template>
  <div class="page">
    <header class="topbar compact">
      <RouterLink class="back-button" to="/library">‹</RouterLink
      ><span class="topbar-title">{{ ru.processing.title }}</span
      ><span class="topbar-spacer" />
    </header>
    <main class="screen processing-screen">
      <p class="eyebrow">Рабочий процесс</p>
      <h1>{{ recording()?.title ?? 'Подготовка записи' }}</h1>
      <p v-if="recording()" class="muted mono">{{ formatMs(recording()!.durationMs) }}</p>
      <section
        v-if="!processing.running.value && !processing.completed.value"
        class="options-card surface"
      >
        <div class="option-row">
          <span>Число спикеров</span>
          <div class="select-control">
            <button class="select-trigger" @click="speakerOpen = !speakerOpen">
              {{ settings.numSpeakers.value === 0 ? ru.common.auto : settings.numSpeakers.value }}
              <span>⌄</span></button
            ><Transition name="dropdown"
              ><div v-if="speakerOpen" class="select-menu">
                <button
                  v-for="n in speakerOptions"
                  :key="n"
                  :class="{ selected: settings.numSpeakers.value === n }"
                  @click="selectSpeaker(n)"
                >
                  {{ n === 0 ? ru.common.auto : n
                  }}<span v-if="settings.numSpeakers.value === n">✓</span>
                </button>
              </div></Transition
            >
          </div>
        </div>
        <label class="switch-row"
          ><span
            ><b>{{ ru.processing.llmProtocol }}</b
            ><small>Сформирует краткий протокол встречи</small></span
          ><input
            v-model="settings.enableLlmProtocol.value"
            class="toggle-input"
            type="checkbox" /><i class="toggle" aria-hidden="true"
        /></label>
        <label class="switch-row"
          ><span
            ><b>{{ ru.processing.llmCorrection }}</b
            ><small>{{ ru.processing.llmCorrectionHint }}</small></span
          ><input
            v-model="settings.enableLlmCorrection.value"
            class="toggle-input"
            type="checkbox" /><i class="toggle" aria-hidden="true"
        /></label>
      </section>
      <section
        v-if="processing.running.value || processing.completed.value || processing.failure.value"
        class="stage-card surface"
      >
        <div class="stage-heading">
          <span>Обработка записи</span><strong>{{ formatMs(processing.elapsedMs.value) }}</strong>
        </div>
        <p v-if="estimatedRemaining" class="estimate">
          Осталось примерно {{ formatMs(estimatedRemaining) }}
        </p>
        <div v-for="stage in STAGES" :key="stage" class="stage-row">
          <span
            class="stage-dot"
            :class="{
              done: processing.stages[stage].done,
              active: processing.currentStage.value === stage,
            }"
          />
          <span>{{ ru.stages[stage] }}</span
          ><b>{{
            processing.stages[stage].done
              ? '✓'
              : processing.currentStage.value === stage
                ? `${Math.round(processing.stages[stage].fraction * 100)}%`
                : ''
          }}</b>
        </div>
      </section>
      <p v-if="processing.failure.value" class="inline-error">
        {{ processing.failure.value.text }}
      </p>
      <button
        v-if="processing.running.value"
        class="action secondary-action full"
        @click="processing.cancel"
      >
        {{ ru.common.cancel }}
      </button>
      <button v-else class="action primary-action full" @click="start">
        {{ processing.failure.value ? ru.common.retry : ru.processing.start }}
      </button>
    </main>
  </div>
</template>

<style scoped>
.processing-screen {
  padding-top: 24px;
}
.processing-screen h1 {
  margin: 0;
  font-size: 30px;
  letter-spacing: -0.06em;
}
.options-card,
.stage-card {
  display: grid;
  gap: 4px;
  margin: 28px 0 16px;
  padding: 8px;
  overflow: visible;
}
.option-row,
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 58px;
  padding: 12px;
  font-size: 14px;
  font-weight: 700;
}
.switch-row > span {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.switch-row small {
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.35;
}
.select-control {
  position: relative;
}
.select-trigger {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 10px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  color: var(--app-primary);
  background: var(--app-surface-solid);
  font-weight: 750;
}
.select-trigger span {
  font-size: 17px;
}
.select-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 8;
  display: grid;
  min-width: 150px;
  padding: 6px;
  border: 1px solid var(--app-border);
  border-radius: 15px;
  background: var(--app-surface-solid);
  box-shadow: var(--app-shadow);
}
.select-menu button {
  display: flex;
  justify-content: space-between;
  padding: 12px 10px;
  border: 0;
  border-radius: 10px;
  color: var(--app-ink);
  background: transparent;
  text-align: left;
}
.select-menu button.selected {
  color: var(--app-primary);
  background: color-mix(in srgb, var(--app-primary) 10%, transparent);
  font-weight: 750;
}
.switch-row {
  position: relative;
  border-top: 1px solid var(--app-border);
}
.toggle-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.toggle {
  position: relative;
  display: block;
  flex: 0 0 auto;
  width: 46px;
  height: 27px;
  border-radius: 999px;
  background: var(--app-border);
  transition: background 0.2s ease;
}
.toggle::after {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 21px;
  height: 21px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 6px rgba(20, 18, 45, 0.18);
  content: '';
  transition: transform 0.2s ease;
}
.toggle-input:checked + .toggle {
  background: var(--app-primary);
}
.toggle-input:checked + .toggle::after {
  transform: translateX(19px);
}
.stage-heading {
  display: flex;
  justify-content: space-between;
  padding: 14px 12px;
  color: var(--app-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.estimate {
  margin: 2px 12px 8px;
  color: var(--app-muted);
  font-size: 12px;
}
.stage-heading strong {
  color: var(--app-ink);
}
.stage-row {
  display: grid;
  grid-template-columns: 14px 1fr auto;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 0 12px;
  font-size: 14px;
}
.stage-row b {
  color: var(--app-primary);
  font-size: 12px;
}
.stage-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--app-border);
}
.stage-dot.active {
  background: #ed725d;
  box-shadow: 0 0 0 5px rgba(237, 114, 93, 0.14);
}
.stage-dot.done {
  background: #17a86b;
}
.full {
  width: 100%;
}
</style>
