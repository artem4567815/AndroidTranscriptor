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

Быстрая проверка ASR без своих записей (только macOS): `python make_tts_dialog.py data/` создаёт русский
диалог 3 «спикеров» с эталоном. Для подбора порогов диаризации он не годится — голос синтетический и один.

## Команды

```bash
python run_pipeline.py data/*.wav -o out/                       # гипотезы out/<имя>.json + timings
python run_pipeline.py data/cats_1.wav --cluster-threshold 0.6  # подбор порога
python run_pipeline.py data/*.wav --diarization-only -o out_diar  # только «кто когда» (без ASR), секунды на запись
python run_pipeline.py data/*.wav --diarization-only --window-shift 0.2  # шаг окна сегментации: скорость vs точность
python run_pipeline.py data/*.wav --diarization-only --embedding other.onnx  # другая модель отпечатков голоса
python eval_wer.py --data data --out out                         # WER (нижний регистр, ё→е, без пунктуации)
python eval_wer.py --data data --out out --keep-punct            # WER с пунктуацией
python eval_der.py --data data --out out --collar-ms 250         # DER
python eval_timestamps.py --data data --out out                  # ошибка границ реплик
python report.py --data data --out out > ../../docs/report.md    # сводная таблица для docs/TESTS.md
python run_pipeline.py data/*.wav --set mergeGapMs=400 --set minSpeakerSilhouette=0.15 -o out  # любой параметр
python tune.py --param minSpeakerSilhouette=0.05,0.1,0.15 --param embeddingPaddingMs=100,200   # перебор + DER
python tune.py --param regroupSpeakers=true,false --full                                       # + WER
```

RTF телефона: debug-сборка пишет `Android/data/hackuna.matata/files/exports/<id>.timings.json`;
`adb pull` их, переименуйте в `<имя>.timings.json` и передайте `report.py --timings <каталог>`.

Перед VAD и диаризацией стенд делает ту же предобработку, что приложение (`common.preprocess` — порт
`DefaultAudioPreprocessor`): без неё результаты диаризации на ПК и телефоне расходились.

Все скрипты поддерживают `--help`. Склейка реплик в `common.py` — порт `UtteranceMerger`/`SegmentPlanner`
из Kotlin; меняете логику там — меняйте и здесь.
