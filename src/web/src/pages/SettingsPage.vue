<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonItem,
  IonLabel,
  IonList,
  IonListHeader,
  IonNote,
  IonPage,
  IonRange,
  IonSelect,
  IonSelectOption,
  IonText,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { storeToRefs } from 'pinia'
import { useSettingsStore } from '@/stores/settings'
import type { StenoErrorCode } from '@/native'
import { ru } from '@/i18n/ru'
import { formatBytes } from '@/utils/time'

const settings = useSettingsStore()
const { numThreads, clusterThreshold, vadThreshold, models, modelsError } = storeToRefs(settings)
const failCode = ref<StenoErrorCode | 'none'>('none')
const speed = ref(1)
const failCodes = Object.keys(ru.errors) as StenoErrorCode[]
const version = __APP_VERSION__

onMounted(() => void settings.loadModels())

function onFailCode(v: StenoErrorCode | 'none') {
  failCode.value = v
  void settings.devFailNext(v === 'none' ? null : v)
}
function onSpeed(v: number) {
  speed.value = v
  void settings.devSetSpeed(v)
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/library" :text="ru.common.back" />
        </ion-buttons>
        <ion-title>{{ ru.settings.title }}</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <div class="center-block">
        <ion-list inset>
          <ion-item>
            <ion-range
              v-model="numThreads"
              :min="1"
              :max="8"
              :step="1"
              :snaps="true"
              :pin="true"
              :label="`${ru.settings.threads}: ${numThreads}`"
              label-placement="stacked"
            />
          </ion-item>
          <ion-item>
            <ion-range
              v-model="clusterThreshold"
              :min="0.1"
              :max="1"
              :step="0.05"
              :pin="true"
              :label="`${ru.settings.clusterThreshold}: ${clusterThreshold.toFixed(2)}`"
              label-placement="stacked"
            />
          </ion-item>
          <ion-item>
            <ion-range
              v-model="vadThreshold"
              :min="0.1"
              :max="0.9"
              :step="0.05"
              :pin="true"
              :label="`${ru.settings.vadThreshold}: ${vadThreshold.toFixed(2)}`"
              label-placement="stacked"
            />
          </ion-item>
          <ion-item button @click="settings.resetDefaults()">
            <ion-label color="primary">Сбросить по умолчанию</ion-label>
          </ion-item>
        </ion-list>

        <ion-list inset>
          <ion-list-header>{{ ru.settings.models }}</ion-list-header>
          <ion-text v-if="modelsError" color="danger"
            ><p class="ion-padding-horizontal">{{ modelsError }}</p></ion-text
          >
          <ion-item v-for="m in models" :key="m.id">
            <ion-label>
              <h3>{{ m.purpose }}</h3>
              <p>{{ m.version }} · {{ formatBytes(m.sizeBytes) }} · {{ m.license }}</p>
            </ion-label>
            <ion-note v-if="!m.present" slot="end" color="danger">{{
              ru.settings.modelMissing
            }}</ion-note>
          </ion-item>
        </ion-list>

        <ion-list inset>
          <ion-list-header>{{ ru.settings.about }}</ion-list-header>
          <ion-item>
            <ion-label class="ion-text-wrap">
              <h3>{{ ru.appName }} {{ version }}</h3>
              <p>{{ ru.settings.aboutText }}</p>
            </ion-label>
          </ion-item>
        </ion-list>

        <ion-list v-if="settings.isMock" inset>
          <ion-list-header>{{ ru.settings.dev }}</ion-list-header>
          <ion-item>
            <ion-select
              :value="failCode"
              :label="ru.settings.failNext"
              label-placement="stacked"
              interface="popover"
              @ion-change="onFailCode($event.detail.value)"
            >
              <ion-select-option value="none">{{ ru.settings.noFail }}</ion-select-option>
              <ion-select-option v-for="c in failCodes" :key="c" :value="c">{{
                c
              }}</ion-select-option>
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-label>{{ ru.settings.speed }}</ion-label>
            <ion-button
              v-for="x in [1, 3, 10]"
              :key="x"
              slot="end"
              size="small"
              :fill="speed === x ? 'solid' : 'outline'"
              @click="onSpeed(x)"
            >
              ×{{ x }}
            </ion-button>
          </ion-item>
        </ion-list>
      </div>
    </ion-content>
  </ion-page>
</template>
