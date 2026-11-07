<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  alertController,
  IonBadge,
  IonButton,
  IonButtons,
  IonContent,
  IonFooter,
  IonHeader,
  IonIcon,
  IonItem,
  IonItemOption,
  IonItemOptions,
  IonItemSliding,
  IonLabel,
  IonList,
  IonPage,
  IonRefresher,
  IonRefresherContent,
  IonText,
  IonTitle,
  IonToolbar,
  onIonViewWillEnter,
} from '@ionic/vue'
import { cloudUploadOutline, micOutline, settingsOutline, trashOutline } from 'ionicons/icons'
import { storeToRefs } from 'pinia'
import { useRecordingsStore } from '@/stores/recordings'
import type { Recording, RecordingStatus } from '@/native'
import { ru } from '@/i18n/ru'
import { formatMs } from '@/utils/time'

const store = useRecordingsStore()
const { items, error, loading } = storeToRefs(store)
const router = useRouter()

const statusColor: Record<RecordingStatus, string> = {
  NEW: 'medium',
  PROCESSING: 'warning',
  DONE: 'success',
  ERROR: 'danger',
}

onIonViewWillEnter(() => void store.refresh())

function open(r: Recording) {
  void router.push(r.status === 'DONE' ? `/transcript/${r.id}` : `/processing/${r.id}`)
}

async function importFile() {
  const rec = await store.importAudio()
  if (rec) void router.push(`/processing/${rec.id}`)
}

async function confirmDelete(r: Recording) {
  const alert = await alertController.create({
    header: r.title,
    message: ru.library.deleteConfirm,
    buttons: [
      { text: ru.common.cancel, role: 'cancel' },
      { text: ru.common.delete, role: 'destructive', handler: () => void store.remove(r.id) },
    ],
  })
  await alert.present()
}

async function onRefresh(ev: CustomEvent) {
  await store.refresh()
  ;(ev.target as HTMLIonRefresherElement).complete()
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>{{ ru.library.title }}</ion-title>
        <ion-buttons slot="end">
          <ion-button :aria-label="ru.library.settings" router-link="/settings">
            <ion-icon slot="icon-only" :icon="settingsOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <ion-refresher slot="fixed" @ion-refresh="onRefresh">
        <ion-refresher-content />
      </ion-refresher>
      <ion-text v-if="error" color="danger" class="ion-padding"
        ><p class="ion-padding-horizontal">{{ error }}</p></ion-text
      >
      <p v-if="!loading && items.length === 0" class="ion-padding muted">{{ ru.library.empty }}</p>
      <ion-list>
        <ion-item-sliding v-for="r in items" :key="r.id">
          <ion-item button :detail="true" @click="open(r)">
            <ion-label>
              <h2>{{ r.title }}</h2>
              <p class="mono">
                {{ formatMs(r.durationMs) }} · {{ new Date(r.createdAt).toLocaleString('ru-RU') }}
              </p>
            </ion-label>
            <ion-badge slot="end" :color="statusColor[r.status]">{{
              ru.status[r.status]
            }}</ion-badge>
          </ion-item>
          <ion-item-options side="end">
            <ion-item-option color="danger" @click="confirmDelete(r)">
              <ion-icon slot="icon-only" :icon="trashOutline" :aria-label="ru.common.delete" />
            </ion-item-option>
          </ion-item-options>
        </ion-item-sliding>
      </ion-list>
    </ion-content>
    <ion-footer>
      <ion-toolbar>
        <div class="actions">
          <ion-button expand="block" fill="outline" @click="importFile">
            <ion-icon slot="start" :icon="cloudUploadOutline" />{{ ru.library.import }}
          </ion-button>
          <ion-button expand="block" router-link="/record">
            <ion-icon slot="start" :icon="micOutline" />{{ ru.library.record }}
          </ion-button>
        </div>
      </ion-toolbar>
    </ion-footer>
  </ion-page>
</template>

<style scoped>
.actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 0 8px;
}
</style>
