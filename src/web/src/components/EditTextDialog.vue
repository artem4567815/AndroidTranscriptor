<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { speakerColorVar, speakerLabel } from '@/utils/speakers'

const props = defineProps<{
  open: boolean
  initialText: string
  initialSpeakerId: string
  speakerIds: string[]
  speakerNames: Record<string, string>
}>()
const emit = defineEmits<{ save: [text: string, speakerId: string]; cancel: [] }>()
const text = ref('')
const speakerId = ref('')
const input = ref<HTMLTextAreaElement | null>(null)

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    text.value = props.initialText
    speakerId.value = props.initialSpeakerId
    await nextTick()
    input.value?.focus()
    input.value?.select()
  },
)

function save() {
  emit('save', text.value.trim(), speakerId.value)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="open" class="dialog-backdrop" role="presentation" @click.self="emit('cancel')">
        <form
          class="dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="edit-text-title"
          @submit.prevent="save"
        >
          <div class="dialog-mark" aria-hidden="true">✎</div>
          <h2 id="edit-text-title">Изменить реплику</h2>
          <p>Исправьте текст или выберите другого спикера для этой реплики.</p>
          <div class="speaker-section">
            <span class="section-label">Спикер реплики</span>
            <div class="speaker-options">
              <button
                v-for="id in speakerIds"
                :key="id"
                type="button"
                class="speaker-option"
                :class="{ selected: speakerId === id }"
                :style="{ '--speaker-color': speakerColorVar(id) }"
                @click="speakerId = id"
              >
                <i aria-hidden="true" />
                {{ speakerLabel(id, speakerNames) }}
              </button>
            </div>
          </div>
          <textarea
            ref="input"
            v-model="text"
            class="edit-input"
            rows="5"
            placeholder="Введите текст реплики"
            aria-label="Текст реплики"
          />
          <div class="dialog-actions">
            <button type="button" class="dialog-button secondary" @click="emit('cancel')">
              Отмена
            </button>
            <button type="submit" class="dialog-button primary">Сохранить</button>
          </div>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  padding: 22px;
  background: rgba(15, 14, 28, 0.52);
  backdrop-filter: blur(7px);
}
.dialog {
  width: min(100%, 390px);
  padding: 24px;
  border: 1px solid var(--app-border);
  border-radius: 26px;
  background: var(--app-surface-solid);
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.3);
}
.dialog-mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  margin-bottom: 16px;
  border-radius: 13px;
  color: var(--app-primary);
  background: color-mix(in srgb, var(--app-primary) 14%, transparent);
  font-size: 20px;
  font-weight: 850;
}
.dialog h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 21px;
  letter-spacing: -0.03em;
}
.dialog p {
  margin: 9px 0 14px;
  color: var(--app-muted);
  line-height: 1.45;
}
.edit-input {
  display: block;
  width: 100%;
  min-height: 118px;
  padding: 13px 14px;
  resize: vertical;
  border: 1px solid var(--app-border);
  border-radius: 15px;
  outline: 0;
  color: var(--app-ink);
  background: var(--app-surface);
  font: inherit;
  line-height: 1.5;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}
.speaker-section {
  margin-bottom: 14px;
}
.section-label {
  display: block;
  margin-bottom: 8px;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 750;
}
.speaker-options {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.speaker-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid var(--app-border);
  border-radius: 999px;
  color: var(--app-ink);
  background: var(--app-surface);
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}
.speaker-option i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--speaker-color);
}
.speaker-option.selected {
  border-color: var(--speaker-color);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--speaker-color) 16%, transparent);
}
.edit-input:focus {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--app-primary) 20%, transparent);
}
.dialog-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
  margin-top: 16px;
}
.dialog-button {
  min-height: 46px;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  font: inherit;
  font-weight: 750;
  cursor: pointer;
  transition: transform 0.16s ease;
}
.dialog-button:active {
  transform: scale(0.96);
}
.dialog-button.secondary {
  color: var(--app-ink);
  background: var(--app-surface);
}
.dialog-button.primary {
  color: #fff;
  border-color: var(--app-primary);
  background: linear-gradient(135deg, var(--app-primary-soft), var(--app-primary));
}
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.2s ease;
}
.dialog-enter-active .dialog,
.dialog-leave-active .dialog {
  transition:
    transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1),
    opacity 0.2s ease;
}
.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
.dialog-enter-from .dialog,
.dialog-leave-to .dialog {
  opacity: 0;
  transform: translateY(12px) scale(0.96);
}
</style>
