# Android APK через GitHub Actions

Workflow `.github/workflows/android-apk.yml` является основным способом сборки приложения.

## Правила

- ветка `main` собирает APK и публикует GitHub Release;
- ветки `work-*` собирают только проверочный Actions artifact и не создают теги или релизы;
- перед публикацией из `main` проверяется, что тег текущей версии ещё не существует;
- каждый новый релиз использует одинаковый числовой `version` и Android `versionCode`;
- перед сборкой обязательно проходят `pnpm test`, `pnpm check` и `pnpm lint`.

## Сборка

Workflow устанавливает pnpm, Node.js 22, Java 17 и Android SDK, выполняет `expo prebuild`, затем Gradle `:app:assembleRelease`.

APK рабочей ветки доступен в Artifacts соответствующего запуска. Для `main` тот же APK публикуется в GitHub Release с тегом `v<version>`.

Не переиспользуйте уже опубликованный номер версии: workflow намеренно остановит такую публикацию.
