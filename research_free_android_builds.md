# Бесплатные альтернативы EAS Build

## GitHub Actions

Источник: https://docs.github.com/en/billing/concepts/product-billing/github-actions

- Стандартные GitHub-hosted runners бесплатны для публичных репозиториев.
- Для приватных репозиториев план GitHub Free включает 2 000 минут в месяц и 500 МБ хранилища артефактов; лимит обновляется в начале расчётного периода.
- Артефакт APK можно сохранять в рамках лимита хранилища и скачивать после каждой сборки.

Источник: https://docs.github.com/en/actions/reference/runners/github-hosted-runners

- GitHub-hosted Linux runners поддерживают Android SDK tools и аппаратное ускорение для Android-инструментов.
- Это подходит для Gradle-сборки текущего проекта: `expo prebuild --platform android`, затем `./gradlew :app:assembleRelease`.

## Вывод

GitHub Actions является бесплатной практичной альтернативой для текущего Expo-проекта с Kotlin, AIDL и Shizuku. Сервис не использует EAS-квоту и работает с тем же сгенерированным Android/Gradle-проектом.

## GitLab CI

Источник: https://docs.gitlab.com/ci/pipelines/compute_minutes/

- На GitLab.com бесплатные пространства имён получают 400 вычислительных минут в месяц.
- GitLab CI может запускать Gradle-сборку Android и публиковать APK как артефакт, но лимит существенно ниже, чем в GitHub Free для приватного репозитория.

## Сравнение

Для приватного исходного кода GitHub Actions предлагает 2 000 минут в месяц, GitLab CI — 400 минут. Оба решения сохраняют APK как артефакт и не используют EAS Build.
