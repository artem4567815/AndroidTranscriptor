<script setup lang="ts">
import { useRouter } from 'vue-router'
import { onMounted, ref } from 'vue'
import { useRecordingsStore } from '@/stores/recordings'
import type { Recording, RecordingStatus } from '@/native'
import { ru } from '@/i18n/ru'
import { formatMs } from '@/utils/time'
import ConfirmDialog from '@/components/ConfirmDialog.vue'

const store = useRecordingsStore()
const { items, error, loading } = store
const router = useRouter()
const pendingDelete = ref<Recording | null>(null)

const statusColor: Record<RecordingStatus, string> = {
  NEW: 'medium',
  PROCESSING: 'warning',
  DONE: 'success',
  ERROR: 'danger',
}

onMounted(() => void store.refresh())

function open(r: Recording) {
  void router.push(r.status === 'DONE' ? `/transcript/${r.id}` : `/processing/${r.id}`)
}

async function importFile() {
  const rec = await store.importAudio()
  if (rec) void router.push(`/processing/${rec.id}`)
}

function confirmDelete(r: Recording) {
  pendingDelete.value = r
}
async function removePending() {
  if (pendingDelete.value) await store.remove(pendingDelete.value.id)
  pendingDelete.value = null
}
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="topline">
        <div>
          <p class="eyebrow">Локальная студия</p>
          <h1>{{ ru.library.title }}</h1>
        </div>
        <div class="header-actions">
          <RouterLink class="icon-button" :aria-label="ru.library.settings" to="/settings"
            >⚙</RouterLink
          >
        </div>
      </div>
    </header>
    <main class="screen">
      <div class="page-content center-block safe-bottom">
        <section class="welcome-card color-wash fade-up">
          <div class="welcome-copy">
            <p class="eyebrow">Ваши мысли, в тексте</p>
            <h1>Слушайте.<br /><span>Понимайте.</span></h1>
            <p>Локальная расшифровка разговоров без облака и лишнего шума.</p>
          </div>
          <div class="wave-art" aria-hidden="true">
            <i v-for="n in 9" :key="n" :style="{ '--i': n }" />
          </div>
          <div class="brand-avatar" aria-label="Нейро-стенографист">
            <svg viewBox="0 0 48 48" aria-hidden="true">
              <circle cx="24" cy="24" r="8" />
              <path d="M24 7v5M24 36v5M7 24h5M36 24h5M12 12l4 4M32 32l4 4M36 12l-4 4M16 32l-4 4" />
            </svg>
          </div>
        </section>
        <p v-if="error" class="error surface">{{ error }}</p>
        <div v-if="!loading && items.length === 0" class="empty surface fade-up">
          <div class="empty-icon">◉</div>
          <h2>Начнём с первой записи</h2>
          <p class="muted">{{ ru.library.empty }}</p>
        </div>
        <div class="recordings-list" aria-label="Записи, листайте по горизонтали">
          <article
            v-for="(r, index) in items"
            :key="r.id"
            class="record surface fade-up"
            :style="{ '--delay': `${index * 50}ms` }"
            @click="open(r)"
          >
            <div class="record-icon" :class="`record-icon-${r.status.toLowerCase()}`">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3Z" />
                <path d="M5 11a7 7 0 0 0 14 0M12 18v3M8 21h8" />
              </svg>
            </div>
            <div class="record-label">
              <h2>{{ r.title }}</h2>
              <p class="mono">
                {{ formatMs(r.durationMs) }} ·
                {{ new Date(r.createdAt).toLocaleDateString('ru-RU') }}
              </p>
            </div>
            <span class="badge" :class="`badge-${statusColor[r.status]}`">{{
              ru.status[r.status]
            }}</span>
            <button
              class="delete-button"
              :aria-label="ru.common.delete"
              @click.stop="confirmDelete(r)"
            >
              ×
            </button>
          </article>
        </div>
      </div>
    </main>
    <footer class="bottom-actions">
      <div class="actions">
        <button class="action secondary-action" @click="importFile">
          ↥ <span>{{ ru.library.import }}</span>
        </button>
        <RouterLink class="action primary-action" to="/record"
          >● <span>{{ ru.library.record }}</span></RouterLink
        >
      </div>
    </footer>
    <ConfirmDialog
      v-if="pendingDelete"
      :open="Boolean(pendingDelete)"
      title="Удалить запись?"
      :message="`${pendingDelete.title}. ${ru.library.deleteConfirm}`"
      @cancel="pendingDelete = null"
      @confirm="removePending"
    />
  </div>
</template>

<style scoped>
.actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 8px 16px max(12px, env(safe-area-inset-bottom));
  background: color-mix(in srgb, var(--app-bg) 88%, transparent);
  backdrop-filter: blur(18px);
}
.topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.topline h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 850;
  letter-spacing: -0.05em;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.status-indicator {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-right: 6px;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 750;
}
.status-indicator i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #21b573;
  box-shadow: 0 0 0 5px rgba(33, 181, 115, 0.14);
}
.welcome-card {
  position: relative;
  min-height: 208px;
  margin: 8px 0 30px;
  padding: 26px 24px;
  overflow: hidden;
  border-radius: var(--app-radius-lg);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(231, 225, 255, 0.76)), var(--app-lilac);
  box-shadow: var(--app-shadow);
}
:root[data-theme='dark'] .welcome-card {
  background:
    linear-gradient(135deg, rgba(47, 45, 80, 0.96), rgba(56, 38, 57, 0.92)), var(--app-lilac);
}
:root[data-theme='dark'] .welcome-copy h1 {
  color: #fff;
}
:root[data-theme='dark'] .welcome-copy p:last-child {
  color: #d3d0e6;
}
.welcome-copy {
  position: relative;
  z-index: 1;
  max-width: 220px;
}
.welcome-copy h1 {
  margin: 0;
  color: var(--app-ink);
  font-size: 34px;
  font-weight: 850;
  line-height: 0.98;
  letter-spacing: -0.07em;
}
.welcome-copy h1 span {
  color: var(--app-primary);
}
.welcome-copy p:last-child {
  max-width: 190px;
  margin: 16px 0 0;
  color: #777591;
  font-size: 13px;
  line-height: 1.4;
}
.wave-art {
  position: absolute;
  right: 12px;
  bottom: 20px;
  display: flex;
  align-items: center;
  gap: 5px;
  height: 106px;
  transform: rotate(-12deg);
}
.brand-avatar {
  position: absolute;
  top: 20px;
  right: 20px;
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border: 1px solid rgba(255, 255, 255, 0.62);
  border-radius: 17px;
  color: #fff;
  background: linear-gradient(145deg, #7d73f6, #6157e8);
  box-shadow: 0 10px 24px rgba(97, 87, 232, 0.26);
}
.brand-avatar svg {
  width: 30px;
  height: 30px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 2;
}
.wave-art i {
  display: block;
  width: 7px;
  height: calc(26px + (var(--i) * 8px));
  border-radius: 999px;
  background: linear-gradient(180deg, #ff917a, #6d63ed);
  opacity: 0.86;
}
.wave-art i:nth-child(odd) {
  height: calc(75px - (var(--i) * 5px));
}
.status-dot {
  width: 10px;
  height: 10px;
  margin-right: 44px;
  border-radius: 50%;
  background: #21b573;
  box-shadow: 0 0 0 5px rgba(33, 181, 115, 0.14);
}
.recordings-list {
  display: flex;
  gap: 10px;
  margin-top: 20px;
  padding: 2px 2px 12px;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  scrollbar-width: none;
  overscroll-behavior-x: contain;
}
.recordings-list::-webkit-scrollbar {
  display: none;
}
.record {
  flex: 0 0 100%;
  scroll-snap-align: center;
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto 30px;
  align-items: center;
  gap: 12px;
  min-height: 94px;
  padding: 14px;
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  box-shadow: 0 10px 24px rgba(74, 64, 146, 0.07);
  animation-delay: var(--delay);
  cursor: pointer;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}
.record:active {
  transform: scale(0.985);
}
.record h2 {
  margin: 0 0 6px;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 15px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.record p {
  margin: 0;
  color: var(--app-muted);
  font-size: 12px;
}
.record-icon,
.empty-icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 46px;
  height: 46px;
  margin-right: 12px;
  border-radius: 15px;
  color: #fff;
  background: linear-gradient(145deg, #7d73f6, #6157e8);
  font-size: 21px;
}
.record-icon {
  margin-right: 0;
}
.record-icon svg {
  width: 23px;
  height: 23px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}
.record-label {
  min-width: 0;
}
.badge {
  display: inline-flex;
  align-items: center;
  min-height: 25px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
  white-space: nowrap;
}
.badge-medium {
  color: #665ed0;
  background: #e9e6ff;
}
.badge-warning {
  color: #a06a14;
  background: #fff0c8;
}
.badge-success {
  color: #16835a;
  background: #d9f5e8;
}
.badge-danger {
  color: #be4d5b;
  background: #ffe0e4;
}
.delete-button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 10px;
  color: var(--app-muted);
  background: transparent;
  font-size: 22px;
  cursor: pointer;
}
.delete-button:active {
  color: #be4d5b;
  background: #ffe0e4;
}
.record-icon-new {
  color: #7765df;
  background: rgba(109, 93, 252, 0.12);
}
.record-icon-processing {
  color: #d79b28;
  background: rgba(215, 155, 40, 0.14);
}
.empty {
  margin-top: 24px;
  padding: 28px 22px;
  text-align: center;
}
.empty-icon {
  margin: 0 auto 16px;
}
.empty h2 {
  margin: 0 0 8px;
  font-size: 20px;
}
.empty p {
  margin: 0;
  line-height: 1.5;
}
.error {
  margin: 20px 0;
  padding: 14px 16px;
  color: #ce4c5c;
}
.primary-action {
  color: #fff;
  border-color: var(--app-primary);
  background: linear-gradient(135deg, var(--app-primary-soft), var(--app-primary));
  box-shadow: 0 8px 18px rgba(97, 87, 232, 0.28);
}
.secondary-action {
  color: var(--app-ink);
  background: var(--app-surface-solid);
}
</style>
