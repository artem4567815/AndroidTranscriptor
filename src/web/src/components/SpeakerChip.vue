<script setup lang="ts">
import { computed } from 'vue'
import { speakerColorVar, speakerLabel } from '@/utils/speakers'

const props = defineProps<{ speakerId: string; names?: Record<string, string> }>()
const emit = defineEmits<{ click: [speakerId: string] }>()

const color = computed(() => speakerColorVar(props.speakerId))
const label = computed(() => speakerLabel(props.speakerId, props.names ?? {}))
</script>

<template>
  <button
    class="speaker-chip"
    type="button"
    :style="{ '--chip-color': color }"
    @click.stop="emit('click', speakerId)"
  >
    <span class="dot" />{{ label }}
  </button>
</template>

<style scoped>
.speaker-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--chip-color);
  background: transparent;
  color: var(--chip-color);
  font-size: 13px;
  font-weight: 600;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--chip-color);
}
</style>
