# Артефакты

| Что | Где |
|---|---|
| APK (финальный) | [релиз v0.3.1](https://github.com/artem4567815/AndroidTranscriptor/releases/tag/v0.3.1), [скачать APK](https://github.com/artem4567815/AndroidTranscriptor/releases/download/v0.3.1/neuro-stenographer-0.3.1.apk): 1,2 ГБ, versionCode 4, модели и LLM внутри |
| SHA-256 | `2d7af761ab40d28c75beb6dc104073407dd6477c66eaff220ffb916c3fc1733b` |
| Видеодемонстрация | [Яндекс Диск](https://disk.yandex.ru/i/xV-XShYDMeQerA) |
| Результаты тестов | [docs/TESTS.md](../docs/TESTS.md) |

APK весит больше 100 МБ, а это лимит GitHub на файл в репозитории, поэтому в git его нет
(`artifacts/apk/` в `.gitignore`): он выложен в GitHub Releases.

Проверить скачанный файл: `shasum -a 256 neuro-stenographer-0.3.1.apk`.
Пересобрать: `scripts/build_release.sh` кладёт APK в `artifacts/apk/`.
