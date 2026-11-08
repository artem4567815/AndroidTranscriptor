import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useModelsStore } from '@/stores/models'
import TranscriptPage from '@/pages/TranscriptPage.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/library' },
  { path: '/setup', name: 'setup', component: () => import('@/pages/ModelSetupPage.vue') },
  { path: '/library', name: 'library', component: () => import('@/pages/LibraryPage.vue') },
  { path: '/record', name: 'record', component: () => import('@/pages/RecordPage.vue') },
  {
    path: '/processing/:id',
    name: 'processing',
    component: () => import('@/pages/ProcessingPage.vue'),
    props: true,
  },
  {
    path: '/transcript/:id',
    name: 'transcript',
    component: TranscriptPage,
    props: true,
  },
  { path: '/settings', name: 'settings', component: () => import('@/pages/SettingsPage.vue') },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach((to, from) => {
  const forward = to.path.split('/').length >= from.path.split('/').length
  to.meta.transition = forward ? 'slide-forward' : 'slide-back'
})

// Пока модели не подготовлены, все пути ведут на страницу первого запуска.
router.beforeEach((to) => {
  const models = useModelsStore()
  // ready — это ref: проверяем .value, иначе объект ref всегда «истинный» и экран моделей не откроется.
  if (!models.ready.value && to.name !== 'setup') {
    return { name: 'setup', query: { next: to.fullPath } }
  }
})

export default router
