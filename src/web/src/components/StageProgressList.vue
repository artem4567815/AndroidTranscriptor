<script setup lang="ts">
import { IonIcon, IonItem, IonLabel, IonList, IonProgressBar, IonSpinner } from '@ionic/vue'
import { checkmarkCircle, closeCircle, ellipseOutline } from 'ionicons/icons'
import { computed } from 'vue'
import { STAGES, type Stage } from '@/native'
import { ru } from '@/i18n/ru'

const props = defineProps<{
  stages: Record<Stage, { fraction: number; done: boolean }>
  current: Stage | null
  failedStage?: Stage | null
  running: boolean
}>()

/** Активный этап — первый незавершённый: спиннер не пропадает в паузе между событиями. */
const active = computed<Stage | null>(() =>
  props.running ? (STAGES.find((s) => !props.stages[s].done) ?? null) : null,
)
</script>

<template>
  <ion-list lines="none">
    <ion-item v-for="s in STAGES" :key="s">
      <ion-icon v-if="failedStage === s" slot="start" :icon="closeCircle" color="danger" />
      <ion-icon v-else-if="stages[s].done" slot="start" :icon="checkmarkCircle" color="success" />
      <ion-spinner v-else-if="active === s" slot="start" name="crescent" />
      <ion-icon v-else slot="start" :icon="ellipseOutline" color="medium" />
      <ion-label>
        <div>{{ ru.stages[s] }}</div>
        <ion-progress-bar v-if="active === s && current === s" :value="stages[s].fraction" />
      </ion-label>
    </ion-item>
  </ion-list>
</template>
