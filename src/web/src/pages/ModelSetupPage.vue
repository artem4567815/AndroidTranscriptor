<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useModelsStore } from '@/stores/models'
import { ru } from '@/i18n/ru'

// reactive() разворачивает ref внутри объекта: в шаблоне models.error / models.fraction — значения, а не ref.
const models = reactive(useModelsStore())
const route = useRoute()
const router = useRouter()
async function run() {
  if (await models.prepare())
    await router.replace(typeof route.query.next === 'string' ? route.query.next : '/library')
}
onMounted(run)
</script>

<template>
  <div class="page setup-page">
    <main class="setup-content">
      <div class="setup-mark">✦</div>
      <p class="eyebrow">Добро пожаловать</p>
      <h1>Ваш личный<br /><span>стенографист</span></h1>
      <p class="muted">{{ ru.setup.hint }}</p>
      <div class="model-visual"><span /><span /><span /><span /><span /></div>
      <div v-if="!models.error" class="setup-progress">
        <div class="progress-track">
          <div class="progress-value" :style="{ width: `${models.fraction * 100}%` }" />
        </div>
        <p>
          {{ models.file || 'Подготавливаем рабочее пространство' }} ·
          {{ Math.round(models.fraction * 100) }}%
        </p>
      </div>
      <div v-else class="setup-error">
        <p>{{ models.error }}</p>
        <button class="action primary-action" @click="run">{{ ru.common.retry }}</button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.setup-page {
  min-height: 100%;
  display: grid;
  place-items: center;
}
.setup-content {
  width: min(100% - 44px, 430px);
  padding: 48px 0;
  text-align: center;
}
.setup-mark {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin: 0 auto 34px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(145deg, #8177f5, #6157e8);
  box-shadow: 0 16px 30px rgba(97, 87, 232, 0.28);
  font-size: 30px;
}
.setup-content h1 {
  margin: 0;
  font-size: 42px;
  line-height: 0.96;
  letter-spacing: -0.08em;
}
.setup-content h1 span {
  color: var(--app-primary);
}
.setup-content > .muted {
  max-width: 290px;
  margin: 18px auto 0;
  line-height: 1.55;
}
.model-visual {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 130px;
  margin: 28px 0;
  border: 1px solid var(--app-border);
  border-radius: 30px;
  background: linear-gradient(135deg, var(--app-lilac), var(--app-mint));
}
.model-visual span {
  width: 7px;
  border-radius: 99px;
  background: var(--app-primary);
  animation: model-wave 1.2s ease-in-out infinite alternate;
}
.model-visual span:nth-child(2) {
  height: 58px;
  animation-delay: 0.1s;
}
.model-visual span:nth-child(3) {
  height: 88px;
  animation-delay: 0.2s;
}
.model-visual span:nth-child(4) {
  height: 48px;
  animation-delay: 0.3s;
}
.model-visual span:nth-child(5) {
  height: 72px;
  animation-delay: 0.4s;
}
@keyframes model-wave {
  to {
    transform: scaleY(0.55);
    opacity: 0.5;
  }
}
.progress-track {
  height: 8px;
  border-radius: 99px;
  background: var(--app-border);
  overflow: hidden;
}
.progress-value {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #6157e8, #ed725d);
  transition: width 0.3s ease;
}
.setup-progress p {
  color: var(--app-muted);
  font-size: 12px;
}
.setup-error {
  color: #ce4c5c;
}
.setup-error .action {
  margin: 12px auto;
}
</style>
