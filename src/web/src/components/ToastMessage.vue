<script setup lang="ts">
defineProps<{ message: string; open: boolean }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="toast">
      <div v-if="open" class="toast" role="status">{{ message }}</div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.toast {
  position: fixed;
  right: 18px;
  bottom: max(24px, env(safe-area-inset-bottom));
  left: 18px;
  z-index: 30;
  padding: 13px 16px;
  border: 1px solid var(--app-border);
  border-radius: 15px;
  color: var(--app-ink);
  background: var(--app-surface-solid);
  box-shadow: var(--app-shadow);
  text-align: center;
  font-size: 13px;
  font-weight: 700;
}
.toast-enter-active,
.toast-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>
