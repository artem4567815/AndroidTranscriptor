#!/usr/bin/env bash
# Скачивает Android-сборку sherpa-onnx: нативные .so (arm64-v8a) и официальные Kotlin API-файлы.
# Только для разработки. .so лежат в .gitignore; Kotlin API-файлы коммитятся (исходники, Apache-2.0).
# Версия зафиксирована здесь и в docs/MODELS.md. Источник: https://github.com/k2-fsa/sherpa-onnx/releases
set -euo pipefail

VERSION="2.0.1"
ARCHIVE="sherpa-onnx-v${VERSION}-android.tar.bz2"
ARCHIVE_SHA256="39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330"
BASE="https://github.com/k2-fsa/sherpa-onnx"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/src/android/ml/sherpa"
JNI_DIR="$MODULE/src/main/jniLibs/arm64-v8a"
KT_DIR="$MODULE/src/main/kotlin/com/k2fsa/sherpa/onnx"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

sha256() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1 || sha256sum "$1" | cut -d' ' -f1; }

echo "==> sherpa-onnx v$VERSION: нативные библиотеки"
curl -fL --retry 3 -o "$TMP/$ARCHIVE" "$BASE/releases/download/v$VERSION/$ARCHIVE"
ACTUAL="$(sha256 "$TMP/$ARCHIVE")"
if [[ "$ACTUAL" != "$ARCHIVE_SHA256" ]]; then
  echo "Ошибка: sha256 не совпадает ($ACTUAL)" >&2
  exit 1
fi
mkdir -p "$TMP/x" && tar -xjf "$TMP/$ARCHIVE" -C "$TMP/x"
SRC_JNI="$(find "$TMP/x" -type d -path '*arm64-v8a' | head -1)"
if [[ -z "$SRC_JNI" ]]; then
  echo "Ошибка: в архиве нет каталога arm64-v8a" >&2
  exit 1
fi
mkdir -p "$JNI_DIR"
rm -f "$JNI_DIR"/*.so
# Kotlin API через JNI нужны только эти две библиотеки (c-api/cxx-api — для C/C++).
cp "$SRC_JNI/libsherpa-onnx-jni.so" "$SRC_JNI/libonnxruntime.so" "$JNI_DIR/"
ls -la "$JNI_DIR"

echo "==> sherpa-onnx v$VERSION: Kotlin API (пакет com.k2fsa.sherpa.onnx не менять — к нему привязаны JNI-имена)"
FILES=(
  FeatureConfig.kt HomophoneReplacerConfig.kt QnnConfig.kt
  OfflineRecognizer.kt OfflineStream.kt OfflinePunctuation.kt
  OnlineStream.kt Vad.kt
  OfflineSpeakerDiarization.kt Speaker.kt SpeakerEmbeddingExtractorConfig.kt
  OfflineSpeechDenoiser.kt DenoisedAudio.kt
  VersionInfo.kt WaveReader.kt
)
mkdir -p "$KT_DIR"
for f in "${FILES[@]}"; do
  curl -fsSL --retry 3 -o "$KT_DIR/$f" "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$VERSION/sherpa-onnx/kotlin-api/$f"
  echo "   $f"
done
curl -fsSL -o "$MODULE/LICENSE-sherpa-onnx" "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$VERSION/LICENSE"
echo "$VERSION" > "$MODULE/SHERPA_VERSION"
echo "Готово."
