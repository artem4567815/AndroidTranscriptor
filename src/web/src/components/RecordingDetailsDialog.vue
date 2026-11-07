<script setup lang="ts">
import { ref, watch } from 'vue'
import { speakerColorVar, speakerLabel } from '@/utils/speakers'

const props = defineProps<{
  open: boolean
  title: string
  speakerIds: string[]
  names: Record<string, string>
}>()
const emit = defineEmits<{ save: [title: string, names: Record<string, string>]; cancel: [] }>()
const draftTitle = ref('')
const draftNames = ref<Record<string, string>>({})

watch(
  () => props.open,
  (open) => {
    if (!open) return
    draftTitle.value = props.title.replace(/\.[^/.]+$/, '')
    draftNames.value = Object.fromEntries(
      props.speakerIds.map((id) => [id, props.names[id] ?? speakerLabel(id, {})]),
    )
  },
  { immediate: true },
)
function save() {
  const names = Object.fromEntries(
    Object.entries(draftNames.value).map(([id, name]) => [id, name.trim() || speakerLabel(id, {})]),
  )
  emit('save', draftTitle.value.trim() || 'Новая запись', names)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="open" class="details-backdrop" role="presentation" @click.self="emit('cancel')">
        <section class="details-dialog" role="dialog" aria-modal="true" aria-label="Данные записи">
          <div class="details-mark">✦</div>
          <h2>О записи</h2>
          <label class="details-field">
            <span>Название</span>
            <input v-model="draftTitle" maxlength="80" placeholder="Например, Планёрка команды" />
          </label>
          <div class="participants-heading">
            <span>Участники</span><small>{{ speakerIds.length }}</small>
          </div>
          <div class="participants-list">
            <label v-for="id in speakerIds" :key="id" class="participant">
              <i :style="{ background: speakerColorVar(id) }" />
              <input
                v-model="draftNames[id]"
                :aria-label="speakerLabel(id, names)"
                maxlength="40"
              />
            </label>
          </div>
          <div class="details-actions">
            <button class="details-button secondary" @click="emit('cancel')">Отмена</button>
            <button class="details-button primary" @click="save">Сохранить</button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.details-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  padding: 22px;
  background: rgba(15, 14, 28, 0.52);
  backdrop-filter: blur(7px);
}
.details-dialog {
  width: min(100%, 380px);
  max-height: min(620px, calc(100vh - 44px));
  overflow: auto;
  padding: 24px;
  border: 1px solid var(--app-border);
  border-radius: 26px;
  background: var(--app-surface-solid);
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.3);
}
.details-mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  margin-bottom: 14px;
  border-radius: 13px;
  color: #fff;
  background: linear-gradient(145deg, var(--app-primary-soft), var(--app-primary));
}
.details-dialog h2 {
  margin: 0 0 20px;
  color: var(--app-ink);
  font-size: 22px;
}
.details-field,
.participant {
  display: grid;
  gap: 7px;
}
.details-field span,
.participants-heading span {
  color: var(--app-ink);
  font-size: 12px;
  font-weight: 800;
}
.details-field input,
.participant input {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--app-border);
  border-radius: 13px;
  outline: 0;
  color: var(--app-ink);
  background: var(--app-surface);
}
.details-field input:focus,
.participant input:focus {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--app-primary) 14%, transparent);
}
.participants-heading {
  display: flex;
  justify-content: space-between;
  margin: 20px 0 9px;
}
.participants-heading small {
  color: var(--app-muted);
}
.participants-list {
  display: grid;
  gap: 8px;
}
.participant {
  position: relative;
}
.participant i {
  position: absolute;
  top: 15px;
  left: 12px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.participant input {
  padding-left: 31px;
}
.details-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
  margin-top: 20px;
}
.details-button {
  min-height: 46px;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  font: inherit;
  font-weight: 750;
}
.details-button.secondary {
  color: var(--app-ink);
  background: var(--app-surface);
}
.details-button.primary {
  color: #fff;
  border-color: var(--app-primary);
  background: var(--app-primary);
}
</style>
