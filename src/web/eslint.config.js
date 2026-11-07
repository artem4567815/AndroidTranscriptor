import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default defineConfigWithVueTs(
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  {
    rules: {
      // Правило моста: страницы и компоненты не ходят в нативный слой напрямую.
      'no-restricted-imports': [
        'error',
        {
          paths: [
            { name: '@capacitor/core', message: 'Используйте сторы и src/native/*.' },
          ],
        },
      ],
      'vue/multi-word-component-names': 'off',
      // Ionic — веб-компоненты, у них нативный атрибут slot (как в официальном стартере Ionic Vue).
      'vue/no-deprecated-slot-attribute': 'off',
    },
  },
  {
    files: ['src/native/**', 'src/stores/**', '*.config.ts'],
    rules: { 'no-restricted-imports': 'off' },
  },
  skipFormatting,
)
