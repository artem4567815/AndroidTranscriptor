# Python-стенд оценки

Прогоняет **тот же пайплайн**, что приложение (те же ONNX-модели через sherpa-onnx и те же параметры
из `config/pipeline.default.json`), и считает метрики хакатона. Пороги подбираются за минуты,
без пересборки APK. Работа на ПК разрешена: запрет касается только самого приложения.

## Установка

```bash
cd src/eval
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

Модели: `scripts/download_models.sh` (кладёт их в `src/android/app/src/main/assets/models`, стенд читает оттуда).

## Данные

`src/eval/data/` (в `.gitignore`): `<имя>.wav` — запись, `<имя>.ref.json` — эталон в формате организаторов.
m4a/mp3 лучше сначала перевести: `ffmpeg -i in.m4a -ac 1 -ar 16000 data/<имя>.wav`.

## Команды

```bash
python run_pipeline.py data/*.wav -o out/                       # гипотезы out/<имя>.json + timings
python run_pipeline.py data/cats_1.wav --cluster-threshold 0.6  # подбор порога
python eval_wer.py --data data --out out                         # WER (нижний регистр, ё→е, без пунктуации)
python eval_wer.py --data data --out out --keep-punct            # WER с пунктуацией
python eval_der.py --data data --out out --collar-ms 250         # DER
python eval_timestamps.py --data data --out out                  # ошибка границ реплик
python report.py --data data --out out > ../../docs/report.md    # сводная таблица для docs/TESTS.md
```

RTF телефона: debug-сборка пишет `Android/data/hackuna.matata/files/exports/<id>.timings.json`;
`adb pull` их, переименуйте в `<имя>.timings.json` и передайте `report.py --timings <каталог>`.

Все скрипты поддерживают `--help`. Склейка реплик в `common.py` — порт `UtteranceMerger`/`SegmentPlanner`
из Kotlin; меняете логику там — меняйте и здесь.
