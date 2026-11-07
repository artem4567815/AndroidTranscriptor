<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { StenoErrorCode } from '@/native'
import { ru } from '@/i18n/ru'
import { formatBytes } from '@/utils/time'

const settings = useSettingsStore()
const failCode = ref<StenoErrorCode | 'none'>('none')
const speed = ref(1)
const failCodes = Object.keys(ru.errors) as StenoErrorCode[]
onMounted(() => void settings.loadModels())
function onFailCode(code: StenoErrorCode | 'none') {
  failCode.value = code
  void settings.devFailNext(code === 'none' ? null : code)
}
function setSpeed(value: number) {
  speed.value = value
  settings.devSetSpeed(value)
}
</script>

<template>
  <div class="page">
    <header class="topbar compact">
      <RouterLink class="back-button" to="/library">‹</RouterLink
      ><span class="topbar-title">{{ ru.settings.title }}</span
      ><span class="topbar-spacer" />
    </header>
    <main class="screen settings-screen">
      <section class="settings-hero">
        <p class="eyebrow">Ваше пространство</p>
        <h1>Настройки</h1>
        <p class="muted">Настройте приложение под свой ритм работы.</p>
      </section>
      <section class="settings-card surface">
        <h2>{{ ru.settings.appearance }}</h2>
        <div class="theme-heading">
          <span
            ><b>{{ ru.settings.theme }}</b
            ><small>Спокойная палитра для долгой работы</small></span
          >
        </div>
        <div class="theme-options">
          <button
            v-for="option in [
              { value: 'light', label: ru.settings.lightTheme, icon: '☼' },
              { value: 'dark', label: ru.settings.darkTheme, icon: '☾' },
              { value: 'system', label: ru.settings.systemTheme, icon: '◐' },
            ]"
            :key="option.value"
            class="theme-option"
            :class="{ selected: settings.theme.value === option.value }"
            @click="settings.theme.value = option.value as 'light' | 'dark' | 'system'"
          >
            <span class="theme-preview" :class="`preview-${option.value}`"><i /></span
            ><span class="theme-option-label">{{ option.icon }} {{ option.label }}</span
            ><span v-if="settings.theme.value === option.value" class="theme-check">✓</span>
          </button>
        </div>
      </section>
      <section class="settings-card surface">
        <h2>Обработка</h2>
        <label class="setting-row"
          ><span
            ><b>{{ ru.settings.threads }}</b
            ><small>Параллельные вычисления</small></span
          ><input
            v-model.number="settings.numThreads.value"
            class="range-control"
            type="range"
            min="1"
            max="8"
        /></label>
        <label class="setting-row"
          ><span
            ><b>{{ ru.settings.clusterThreshold }}</b
            ><small>{{ settings.clusterThreshold.value.toFixed(2) }}</small></span
          ><input
            v-model.number="settings.clusterThreshold.value"
            class="range-control"
            type="range"
            min=".1"
            max="1"
            step=".05"
        /></label>
        <button class="text-button" @click="settings.resetDefaults">Сбросить настройки</button>
      </section>
      <section class="settings-card surface">
        <h2>{{ ru.settings.models }}</h2>
        <p v-if="settings.modelsError.value" class="inline-error">
          {{ settings.modelsError.value }}
        </p>
        <div v-for="model in settings.models.value" :key="model.id" class="model-row">
          <span
            ><b>{{ model.purpose }}</b
            ><small>{{ model.version }} · {{ formatBytes(model.sizeBytes) }}</small></span
          ><i :class="{ missing: !model.present }">{{ model.present ? 'Готово' : 'Нет' }}</i>
        </div>
      </section>
      <section v-if="settings.isMock" class="settings-card surface">
        <h2>{{ ru.settings.dev }}</h2>
        <select v-model="failCode" class="dev-select" @change="onFailCode(failCode)">
          <option value="none">{{ ru.settings.noFail }}</option>
          <option v-for="code in failCodes" :key="code" :value="code">{{ code }}</option>
        </select>
        <div class="speed-row">
          <button
            v-for="x in [1, 3, 10]"
            :key="x"
            class="speed-button"
            :class="{ selected: speed === x }"
            @click="setSpeed(x)"
          >
            ×{{ x }}
          </button>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.settings-screen {
  padding-top: 20px;
}
.settings-hero h1 {
  margin: 0;
  font-size: 34px;
  letter-spacing: -0.07em;
}
.settings-hero p:last-child {
  margin-top: 10px;
}
.settings-card {
  margin: 18px 0;
  padding: 18px;
}
.settings-card h2 {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--app-muted);
  text-transform: uppercase;
  letter-spacing: 0.1em;
}
.setting-row,
.model-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0;
  border-top: 1px solid var(--app-border);
}
.setting-row span,
.model-row span,
.theme-heading span {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.setting-row small,
.model-row small,
.theme-heading small {
  color: var(--app-muted);
  font-size: 12px;
}
.theme-heading {
  padding: 8px 0 14px;
}
.theme-options {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.theme-option {
  position: relative;
  display: grid;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--app-border);
  border-radius: 15px;
  color: var(--app-ink);
  background: transparent;
  text-align: left;
}
.theme-option.selected {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary) 18%, transparent);
}
.theme-preview {
  display: block;
  height: 48px;
  padding: 7px;
  border-radius: 10px;
  background: #f5f3ff;
}
.theme-preview i {
  display: block;
  width: 65%;
  height: 8px;
  margin-top: 6px;
  border-radius: 5px;
  background: #d7d1ff;
  box-shadow: 0 14px 0 #fff;
}
.preview-dark {
  background: #242338;
}
.preview-dark i {
  background: #6e65eb;
  box-shadow: 0 14px 0 #3b3954;
}
.preview-system {
  background: linear-gradient(110deg, #f5f3ff 50%, #242338 50%);
}
.preview-system i {
  background: #6e65eb;
  box-shadow: 0 14px 0 rgba(255, 255, 255, 0.7);
}
.theme-option-label {
  overflow: hidden;
  font-size: 11px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.theme-check {
  position: absolute;
  top: 8px;
  right: 8px;
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  background: var(--app-primary);
  font-size: 12px;
}
.range-control {
  width: 120px;
  accent-color: var(--app-primary);
}
.text-button {
  padding: 12px 0 0;
  border: 0;
  color: var(--app-primary);
  background: transparent;
  font: inherit;
  font-weight: 750;
}
.model-row i {
  color: #17a86b;
  font-size: 12px;
  font-style: normal;
  font-weight: 750;
}
.model-row i.missing {
  color: #ce4c5c;
}
.dev-select {
  width: 100%;
  max-width: none;
  padding: 12px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
}
.speed-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.speed-button {
  flex: 1;
  padding: 10px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  color: var(--app-ink);
  background: transparent;
  font: inherit;
}
.speed-button.selected {
  color: #fff;
  border-color: var(--app-primary);
  background: var(--app-primary);
}
</style>
