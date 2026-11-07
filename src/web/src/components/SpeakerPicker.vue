<script setup lang="ts">
import { IonItem, IonLabel, IonList, IonRadio, IonRadioGroup } from '@ionic/vue'
import { speakerColorVar, speakerLabel } from '@/utils/speakers'

const props = defineProps<{
  speakerIds: string[]
  names: Record<string, string>
  modelValue: string
}>()
const emit = defineEmits<{ 'update:modelValue': [speakerId: string] }>()

/** Позволяет назначить нового спикера, которого ещё нет в расшифровке. */
function nextId(): string {
  const max = props.speakerIds.reduce((m, id) => Math.max(m, Number(id.replace(/\D/g, '')) || 0), 0)
  return `speaker_${max + 1}`
}
</script>

<template>
  <ion-list>
    <ion-radio-group
      :value="modelValue"
      @ion-change="emit('update:modelValue', $event.detail.value)"
    >
      <ion-item v-for="id in [...speakerIds, nextId()]" :key="id">
        <ion-radio :value="id" label-placement="end" justify="start">
          <ion-label :style="{ color: speakerColorVar(id) }">
            {{ speakerLabel(id, names) }}
            <span v-if="!speakerIds.includes(id)" class="muted"> (новый)</span>
          </ion-label>
        </ion-radio>
      </ion-item>
    </ion-radio-group>
  </ion-list>
</template>
