#!/usr/bin/env bash
# Сборка релиза: Vue → cap sync → подписанный APK с версией в имени → artifacts/apk/.
# Подпись: src/android/keystore.properties (storeFile, storePassword, keyAlias, keyPassword) — вне git.
# Без него APK подписывается debug-ключом (Gradle предупредит).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Vue: npm ci, lint, test, build"
cd "$ROOT/src/web"
npm ci
npm run lint
npm run test
npm run build
npm run check:no-mock
npx cap sync android

echo "==> Android: assembleRelease"
cd "$ROOT/src/android"
./gradlew --no-daemon assembleRelease

APK="$ROOT/src/android/app/build/outputs/apk/release/app-release.apk"
VERSION="$(grep -m1 'versionName' app/build.gradle | sed -E 's/.*"(.*)".*/\1/')"
BT="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)"

echo "==> Проверка: в release нет INTERNET"
if "$BT/aapt2" dump permissions "$APK" | grep -q "android.permission.INTERNET"; then
  echo "Ошибка: в release-манифесте есть INTERNET" >&2
  exit 1
fi

OUT="$ROOT/artifacts/apk"
mkdir -p "$OUT"
cp "$APK" "$OUT/neuro-stenographer-$VERSION.apk"
SIZE=$(du -h "$OUT/neuro-stenographer-$VERSION.apk" | cut -f1)
echo "Готово: artifacts/apk/neuro-stenographer-$VERSION.apk ($SIZE). Лимит GitHub Releases — 2 ГБ на файл."
