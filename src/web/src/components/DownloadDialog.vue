<script setup lang="ts">
defineProps<{ open: boolean; title: string }>()
const emit = defineEmits<{ audio: []; json: []; cancel: [] }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="open" class="download-backdrop" role="presentation" @click.self="emit('cancel')">
        <section class="download-dialog" role="dialog" aria-modal="true" :aria-label="title">
          <div class="download-mark">↓</div>
          <h2>{{ title }}</h2>
          <p>Выберите, что сохранить на устройство.</p>
          <div class="download-options">
            <button class="download-option" @click="emit('audio')">
              <span class="download-option-icon">◖</span>
              <span><b>Аудиозапись</b><small>Исходный файл записи</small></span>
            </button>
            <button class="download-option" @click="emit('json')">
              <span class="download-option-icon">{ }</span>
              <span><b>Текстовая сводка</b><small>Расшифровка и метаданные JSON</small></span>
            </button>
          </div>
          <button class="download-cancel" @click="emit('cancel')">Отмена</button>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.download-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  padding: 22px;
  background: rgba(15, 14, 28, 0.52);
  backdrop-filter: blur(7px);
}
.download-dialog {
  width: min(100%, 360px);
  padding: 24px;
  border: 1px solid var(--app-border);
  border-radius: 26px;
  background: var(--app-surface-solid);
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.3);
}
.download-mark,
.download-option-icon {
  display: grid;
  place-items: center;
  color: var(--app-primary);
  background: color-mix(in srgb, var(--app-primary) 13%, transparent);
}
.download-mark {
  width: 38px;
  height: 38px;
  margin-bottom: 16px;
  border-radius: 13px;
  font-size: 21px;
  font-weight: 850;
}
.download-dialog h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 21px;
}
.download-dialog p {
  margin: 9px 0 18px;
  color: var(--app-muted);
}
.download-options {
  display: grid;
  gap: 9px;
}
.download-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--app-border);
  border-radius: 15px;
  color: var(--app-ink);
  background: var(--app-surface);
  text-align: left;
}
.download-option-icon {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 850;
}
.download-option span:last-child {
  display: grid;
  gap: 3px;
}
.download-option small {
  color: var(--app-muted);
  font-size: 11px;
}
.download-cancel {
  width: 100%;
  min-height: 44px;
  margin-top: 14px;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  color: var(--app-ink);
  background: var(--app-surface);
  font-weight: 750;
}
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.2s ease;
}
.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
</style>
