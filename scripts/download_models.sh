#!/usr/bin/env bash
# Скачивает модели в src/android/app/src/main/assets/models с проверкой sha256 и обновляет manifest.json
# (размеры и sha256 фактических файлов). ТОЛЬКО для разработки: приложение моделей из сети не качает.
#
#   scripts/download_models.sh            # ASR + VAD + диаризация
#   scripts/download_models.sh --with-llm # + LLM (нужен URL, см. TODO ниже)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/src/android/app/src/main/assets/models"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
REL="https://github.com/k2-fsa/sherpa-onnx/releases/download"

sha256() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1 || sha256sum "$1" | cut -d' ' -f1; }

# fetch <url> <файл> <sha256 или пусто>
fetch() {
  local url="$1" out="$2" expected="$3"
  echo "==> $url"
  curl -fL --retry 5 --retry-all-errors --retry-delay 2 -o "$out" "$url"
  local actual; actual="$(sha256 "$out")"
  if [[ -n "$expected" && "$actual" != "$expected" ]]; then
    echo "Ошибка sha256 для $(basename "$out"): $actual != $expected" >&2
    exit 1
  fi
  [[ -z "$expected" ]] && echo "   sha256 (зафиксируйте в скрипте): $actual"
  return 0
}

# Берёт первый существующий файл из списка кандидатов.
pick() { for f in "$@"; do [[ -f "$f" ]] && { echo "$f"; return 0; }; done; return 1; }

mkdir -p "$DEST"/{asr,vad,diarization,llm}

# 1. ASR: GigaAM v3 e2e (транcдьюсер с пунктуацией), sherpa-onnx, 2025-12-16
ASR=sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
fetch "$REL/asr-models/$ASR.tar.bz2" "$TMP/asr.tar.bz2" \
  "f9620a0099019c6afcee26525ef9ed3297fa50dd5691c1902af0c948fc1a470b"
tar -xjf "$TMP/asr.tar.bz2" -C "$TMP"
A="$TMP/$ASR"
echo "   содержимое архива:"; ls -la "$A"
cp "$(pick "$A/encoder.int8.onnx" "$A/encoder.onnx")" "$DEST/asr/encoder.int8.onnx"
cp "$(pick "$A/decoder.int8.onnx" "$A/decoder.onnx")" "$DEST/asr/decoder.onnx"
cp "$(pick "$A/joiner.int8.onnx" "$A/joiner.onnx")" "$DEST/asr/joiner.onnx"
cp "$A/tokens.txt" "$DEST/asr/tokens.txt"

# 2. VAD: Silero
fetch "$REL/asr-models/silero_vad.onnx" "$DEST/vad/silero_vad.onnx" \
  "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

# 3. Диаризация: pyannote-segmentation-3.0 + 3D-Speaker CAM++
fetch "$REL/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" "$TMP/seg.tar.bz2" ""
tar -xjf "$TMP/seg.tar.bz2" -C "$TMP"
cp "$(pick "$TMP/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" "$TMP/sherpa-onnx-pyannote-segmentation-3-0/model.onnx")" \
  "$DEST/diarization/segmentation.onnx"
# Отпечатки голоса: CAM++ zh-cn — лучшая из 6 моделей на наших записях (русская игра в слова, синтетика,
# тестовые записи sherpa), см. docs/TESTS.md. Русской модели у sherpa-onnx нет. Сравнить другую:
# EMBEDDING=3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx EMBEDDING_SHA256=... $0
EMBEDDING="${EMBEDDING:-3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx}"
EMBEDDING_SHA256="${EMBEDDING_SHA256:-f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11}"
fetch "$REL/speaker-recongition-models/$EMBEDDING" "$DEST/diarization/embedding.onnx" "$EMBEDDING_SHA256"

# 4. LLM (роль 4)
if [[ "${1:-}" == "--with-llm" ]]; then
  # TODO(роль 4): выбрать модель .litertlm (Gemma 4 E2B или Qwen2.5-0.5B) на https://huggingface.co/litert-community
  # и вписать прямой URL и sha256. Некоторые модели требуют принять лицензию на HF — скачать вручную.
  LLM_URL="${LLM_URL:-}"
  if [[ -z "$LLM_URL" ]]; then
    echo "LLM_URL не задан: LLM_URL=<прямая ссылка на .litertlm> $0 --with-llm" >&2
    exit 1
  fi
  fetch "$LLM_URL" "$DEST/llm/model.litertlm" "${LLM_SHA256:-}"
fi

# Обновляем manifest.json фактическими размерами и sha256.
python3 - "$DEST" <<'PY'
import hashlib, json, os, sys
dest = sys.argv[1]
path = os.path.join(dest, "manifest.json")
m = json.load(open(path, encoding="utf-8"))
for f in m["files"]:
    p = os.path.join(dest, f["path"])
    if os.path.exists(p):
        h = hashlib.sha256()
        with open(p, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        f["sizeBytes"], f["sha256"] = os.path.getsize(p), h.hexdigest()
json.dump(m, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(path, "a").write("\n")
print("manifest.json обновлён")
PY
du -sh "$DEST"/*
