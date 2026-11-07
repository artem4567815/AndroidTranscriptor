<script setup lang="ts">
import { speakerColorVar, speakerLabel } from '@/utils/speakers'
defineProps<{ speakerIds: string[]; names: Record<string, string>; modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()
</script>
<template>
  <div class="speaker-picker">
    <button
      v-for="id in speakerIds"
      :key="id"
      :class="{ selected: id === modelValue }"
      :style="{ color: speakerColorVar(id) }"
      @click="emit('update:modelValue', id)"
    >
      {{ speakerLabel(id, names) }}
    </button>
  </div>
</template>
<style scoped>
.speaker-picker {
  display: grid;
  gap: 8px;
  padding: 16px;
}
.speaker-picker button {
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  background: var(--app-surface-solid);
  font: inherit;
  text-align: left;
}
.speaker-picker button.selected {
  border-color: currentColor;
  box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 15%, transparent);
}
</style>
