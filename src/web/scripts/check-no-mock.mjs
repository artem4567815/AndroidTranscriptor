// Проверка правила 2: мок-плагин и тестовая расшифровка не попали в production-сборку.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const MARKERS = ['[steno.mock]', 'Открываем заседание совета котиков', 'synthWav']
const found = []
function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p)
    else if (/\.(js|html|css)$/.test(name)) {
      const text = readFileSync(p, 'utf8')
      for (const m of MARKERS) if (text.includes(m)) found.push(`${p}: ${m}`)
    }
  }
}
walk('dist')
if (found.length) {
  console.error('Мок попал в production-сборку:\n' + found.join('\n'))
  process.exit(1)
}
console.log('OK: мока в dist нет')
