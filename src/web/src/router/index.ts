import { createRouter, createWebHistory } from '@ionic/vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useModelsStore } from '@/stores/models'

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
    component: () => import('@/pages/TranscriptPage.vue'),
    props: true,
  },
  { path: '/settings', name: 'settings', component: () => import('@/pages/SettingsPage.vue') },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

// Пока модели не подготовлены, все пути ведут на страницу первого запуска.
router.beforeEach((to) => {
  const models = useModelsStore()
  if (!models.ready && to.name !== 'setup') {
    return { name: 'setup', query: { next: to.fullPath } }
  }
})

export default router
