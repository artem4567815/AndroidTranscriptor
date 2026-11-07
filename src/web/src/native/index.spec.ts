import { describe, expect, it } from 'vitest'
import { wrapNativePlugin, type StenoPlugin } from './index'

describe('wrapNativePlugin', () => {
  /** Имитация Proxy Capacitor: любое свойство — «нативный метод», а `then` падает, как на телефоне. */
  function capacitorLikeProxy(calls: string[]): StenoPlugin {
    return new Proxy({} as StenoPlugin, {
      get(_t, prop) {
        if (prop === 'then') throw new Error('"Steno.then()" is not implemented on android')
        return (...args: unknown[]) => {
          calls.push(String(prop))
          return Promise.resolve({ ready: true, args })
        }
      },
    })
  }

  it('обёртку можно отдать через Promise — then не вызывается', async () => {
    const calls: string[] = []
    const plugin = await Promise.resolve(wrapNativePlugin(capacitorLikeProxy(calls)))
    expect(await plugin.prepareModels()).toMatchObject({ ready: true })
    expect(calls).toEqual(['prepareModels'])
    expect('then' in plugin).toBe(false)
  })

  it('пробрасывает аргументы', async () => {
    const calls: string[] = []
    const plugin = wrapNativePlugin(capacitorLikeProxy(calls))
    const res = (await plugin.getResult({ id: '42' })) as unknown as { args: unknown[] }
    expect(res.args).toEqual([{ id: '42' }])
  })
})
