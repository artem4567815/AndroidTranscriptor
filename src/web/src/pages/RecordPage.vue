<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonText,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { mic, stop } from 'ionicons/icons'
import { storeToRefs } from 'pinia'
import { useRecordingsStore } from '@/stores/recordings'
import { ru } from '@/i18n/ru'
import { formatMs } from '@/utils/time'

const store = useRecordingsStore()
const { isRecording, level, recordStartedAt, error } = storeToRefs(store)
const router = useRouter()

const now = ref(Date.now())
const ticker = setInterval(() => (now.value = Date.now()), 200)
const elapsed = computed(() => (recordStartedAt.value ? now.value - recordStartedAt.value : 0))

async function toggle() {
  if (!isRecording.value) {
    await store.startRecording()
    return
  }
  const rec = await store.stopRecording()
  if (rec) await router.replace(`/processing/${rec.id}`)
}

onBeforeUnmount(() => {
  clearInterval(ticker)
  // Уход со страницы во время записи сохраняет то, что успели записать.
  if (isRecording.value) void store.stopRecording()
})
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/library" :text="ru.common.back" />
        </ion-buttons>
        <ion-title>{{ ru.record.title }}</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content class="ion-padding">
      <div class="center-block recorder">
        <div class="timer mono">{{ formatMs(elapsed) }}</div>
        <div class="meter" aria-hidden="true">
          <div class="meter-fill" :style="{ width: `${Math.round(level * 100)}%` }" />
        </div>
        <ion-button
          shape="round"
          size="large"
          :color="isRecording ? 'danger' : 'primary'"
          @click="toggle"
        >
          <ion-icon slot="start" :icon="isRecording ? stop : mic" />
          {{ isRecording ? ru.record.stop : ru.record.start }}
        </ion-button>
        <p class="muted">{{ ru.record.hint }}</p>
        <ion-text v-if="error" color="danger"
          ><p>{{ error }}</p></ion-text
        >
      </div>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.recorder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  padding-top: 10vh;
  text-align: center;
}
.timer {
  font-size: 56px;
  font-weight: 300;
}
.meter {
  width: 80%;
  height: 10px;
  border-radius: 5px;
  background: var(--ion-color-step-150, #ddd);
  overflow: hidden;
}
.meter-fill {
  height: 100%;
  background: var(--ion-color-primary);
  transition: width 0.1s linear;
}
</style>
