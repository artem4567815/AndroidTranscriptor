<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { TUNABLES, useSettingsStore, type TunableKey } from '@/stores/settings'
import type { StenoErrorCode } from '@/native'
import { ru } from '@/i18n/ru'
import { formatBytes } from '@/utils/time'

const settings = useSettingsStore()
const failCode = ref<StenoErrorCode | 'none'>('none')
const speed = ref(1)
const failCodes = Object.keys(ru.errors) as StenoErrorCode[]
onMounted(() => {
  void settings.loadModels()
  void settings.loadDefaults()
})

type Tunable = (typeof TUNABLES)[number]
const BASIC: readonly TunableKey[] = ['numThreads', 'clusterThreshold', 'vadThreshold']
const basic = TUNABLES.filter((t) => BASIC.includes(t.key))
const advanced = TUNABLES.filter((t) => !BASIC.includes(t.key))

function display(t: Tunable): string {
  const v = settings.tunableValue(t.key)
  if (v === undefined) return '…'
  if (typeof v === 'boolean') return v ? 'вкл' : 'выкл'
  return t.kind === 'float' ? v.toFixed(2) : String(v)
}
function onRange(t: Tunable, e: Event) {
  settings.setTunable(t.key, Number((e.target as HTMLInputElement).value))
}
function onToggle(t: Tunable, e: Event) {
  settings.setTunable(t.key, (e.target as HTMLInputElement).checked)
}
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
      <RouterLink class="back-button" to="/library" aria-label="Назад">
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <path d="m10 3-5 5 5 5" />
        </svg>
      </RouterLink>
      <span class="topbar-title">{{ ru.settings.title }}</span>
      <span class="topbar-spacer" />
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
            @click="settings.setTheme(option.value as 'light' | 'dark' | 'system')"
          >
            <span class="theme-preview" :class="`preview-${option.value}`"><i /></span
            ><span class="theme-option-label">{{ option.icon }} {{ option.label }}</span
            ><span v-if="settings.theme.value === option.value" class="theme-check">✓</span>
          </button>
        </div>
      </section>
      <section
        v-for="group in [
          { title: 'Обработка', hint: '', items: basic },
          { title: ru.settings.advanced, hint: ru.settings.advancedHint, items: advanced },
        ]"
        :key="group.title"
        class="settings-card surface"
      >
        <h2>{{ group.title }}</h2>
        <p v-if="group.hint" class="muted tunable-hint">{{ group.hint }}</p>
        <p v-if="settings.defaultsError.value" class="inline-error">
          {{ settings.defaultsError.value }}
        </p>
        <label v-for="t in group.items" :key="t.key" class="setting-row tunable-row"
          ><span
            ><b
              >{{ ru.settings.tunables[t.key][0]
              }}<i v-if="settings.isChanged(t.key)" class="changed-dot" aria-label="изменено" /></b
            ><small
              >{{ ru.settings.tunables[t.key][1] }} · <b class="tunable-value">{{ display(t) }}</b>
              <template v-if="settings.isChanged(t.key)">
                ({{ ru.settings.byDefault }}: {{ settings.pipelineDefaults.value?.[t.key] }})
                <button
                  class="link-button"
                  type="button"
                  @click.prevent="settings.resetTunable(t.key)"
                >
                  {{ ru.settings.resetOne }}
                </button>
              </template></small
            ></span
          ><template v-if="t.kind === 'bool'"
            ><input
              class="toggle-input"
              type="checkbox"
              :checked="Boolean(settings.tunableValue(t.key))"
              @change="onToggle(t, $event)" /><i class="toggle" aria-hidden="true" /></template
          ><input
            v-else
            class="range-control"
            type="range"
            :min="t.min"
            :max="t.max"
            :step="t.step"
            :value="Number(settings.tunableValue(t.key) ?? t.min)"
            @input="onRange(t, $event)"
        /></label>
        <button v-if="group.items === advanced" class="text-button" @click="settings.resetDefaults">
          Сбросить настройки
        </button>
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
.tunable-hint {
  margin: -4px 0 8px;
  font-size: 13px;
}
.tunable-row {
  position: relative;
}
.tunable-value {
  color: var(--app-text, inherit);
}
.changed-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--app-primary);
  vertical-align: middle;
}
.link-button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--app-primary);
  font: inherit;
  text-decoration: underline;
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
</style>
