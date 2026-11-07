import { describe, expect, it } from 'vitest'
import router from './index'
import { useModelsStore } from '@/stores/models'

describe('router: первый запуск', () => {
  it('пока модели не подготовлены, любой путь ведёт на экран моделей', async () => {
    await router.push('/library')
    expect(router.currentRoute.value.name).toBe('setup')
    expect(router.currentRoute.value.query.next).toBe('/library')
  })

  it('после подготовки моделей пускает дальше', async () => {
    useModelsStore().ready.value = true
    await router.push('/settings')
    expect(router.currentRoute.value.name).toBe('settings')
  })
})
