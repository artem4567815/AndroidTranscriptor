import type { CapacitorConfig } from '@capacitor/cli'

// В конфиге нет server.url и cleartext: release-сборка отдаёт Vue из assets без сети.
// Live reload включается только флагом `npx cap run android -l --external`.
const config: CapacitorConfig = {
  appId: 'hackuna.matata',
  appName: 'Нейро-стенографист',
  webDir: 'dist',
  android: {
    path: '../android',
  },
}

export default config
