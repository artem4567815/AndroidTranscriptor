<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  IonButton,
  IonContent,
  IonHeader,
  IonPage,
  IonProgressBar,
  IonText,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { storeToRefs } from 'pinia'
import { useModelsStore } from '@/stores/models'
import { ru } from '@/i18n/ru'

const models = useModelsStore()
const { preparing, fraction, file, error } = storeToRefs(models)
const route = useRoute()
const router = useRouter()

async function run() {
  if (await models.prepare()) {
    const next = typeof route.query.next === 'string' ? route.query.next : '/library'
    await router.replace(next === '/setup' ? '/library' : next)
  }
}
onMounted(run)
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>{{ ru.setup.title }}</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content class="ion-padding">
      <div class="center-block">
        <h2>{{ ru.appName }}</h2>
        <p class="muted">{{ ru.setup.hint }}</p>
        <template v-if="!error">
          <ion-progress-bar
            :value="fraction"
            :type="preparing && fraction === 0 ? 'indeterminate' : 'determinate'"
          />
          <p class="muted mono">
            {{ ru.setup.copying }}: {{ file || '…' }} ({{ Math.round(fraction * 100) }}%)
          </p>
        </template>
        <template v-else>
          <ion-text color="danger"
            ><p>{{ error }}</p></ion-text
          >
          <ion-button expand="block" @click="run">{{ ru.common.retry }}</ion-button>
        </template>
      </div>
    </ion-content>
  </ion-page>
</template>
