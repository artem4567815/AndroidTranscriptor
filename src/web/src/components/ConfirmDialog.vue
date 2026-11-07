<script setup lang="ts">
defineProps<{ open: boolean; title: string; message: string }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="open" class="dialog-backdrop" role="presentation" @click.self="emit('cancel')">
        <section class="dialog" role="alertdialog" aria-modal="true" :aria-label="title">
          <div class="dialog-mark">!</div>
          <h2>{{ title }}</h2>
          <p>{{ message }}</p>
          <div class="dialog-actions">
            <button class="dialog-button secondary" @click="emit('cancel')">Отмена</button>
            <button class="dialog-button danger" @click="emit('confirm')">Удалить</button>
          </div>
        </section>
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
  width: min(100%, 360px);
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
  color: #b94d5a;
  background: #ffe2e6;
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
  margin: 9px 0 22px;
  color: var(--app-muted);
  line-height: 1.45;
}
.dialog-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
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
.dialog-button.danger {
  color: #fff;
  border-color: #ce5967;
  background: #ce5967;
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
