<script setup lang="ts">
import { computed } from 'vue'
import { PROCESSING_STAGES as STAGES, type Stage } from '@/native'
import { ru } from '@/i18n/ru'
const props = defineProps<{
  stages: Record<Stage, { fraction: number; done: boolean }>
  current: Stage | null
  failedStage?: Stage | null
  running: boolean
}>()
const active = computed(() =>
  props.running ? (STAGES.find((stage) => !props.stages[stage].done) ?? null) : null,
)
</script>
<template>
  <div class="stage-list surface">
    <div v-for="stage in STAGES" :key="stage" class="stage-item">
      <span
        class="stage-dot"
        :class="{
          done: stages[stage].done,
          active: active === stage,
          failed: failedStage === stage,
        }"
      /><span>{{ ru.stages[stage] }}</span
      ><b>{{
        stages[stage].done
          ? '✓'
          : active === stage
            ? `${Math.round(stages[stage].fraction * 100)}%`
            : ''
      }}</b>
    </div>
  </div>
</template>
<style scoped>
.stage-list {
  padding: 10px 16px;
}
.stage-item {
  display: grid;
  grid-template-columns: 12px 1fr auto;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  font-size: 14px;
}
.stage-item b {
  color: var(--app-primary);
  font-size: 12px;
}
.stage-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-border);
}
.stage-dot.done {
  background: #17a86b;
}
.stage-dot.active {
  background: #ed725d;
  box-shadow: 0 0 0 5px rgba(237, 114, 93, 0.14);
}
.stage-dot.failed {
  background: #ce4c5c;
}
</style>
