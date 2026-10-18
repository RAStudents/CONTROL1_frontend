# CONTROL

Кроссплатформенное приложение на базе **Kotlin Multiplatform (KMP)** и **Compose Multiplatform** с таргетами под **Android** и **Web (Kotlin/Wasm)**.

---

## Архитектура проекта

Проект использует conventions-плагины в `build-logic` для унификации конфигурации сборки и линтинга.

* **`:shared`** — общая бизнес-логика и Compose UI компоненты:
    * `commonMain` — платформонезависимый код, общие экраны и UI-компоненты.
    * `commonTest` — общие unit-тесты.
    * `androidHostTest` — юнит-тесты под Android на базе JVM.
    * `webTest` — тесты для Wasm-таргета (запускаются в Headless Chromium через Karma).
* **`:androidApp`** — точка входа для Android (`MainActivity`, манифест, иконки, темы).
* **`:webApp`** — точка входа для WebAssembly (`index.html`, запуск `ComposeViewport`).
* **`build-logic`** — Gradle Convention Plugins (`control.convention.*`).

---

## Требования к окружению

* **JDK 21** (управляется автоматически через Gradle Daemon Toolchain).
* **Android SDK**: compileSdk 37, minSdk 24.
* **Google Chrome / Chromium** (необходим для выполнения Wasm браузерных тестов).

---

## Запуск приложений

### Android

* Сборка Debug APK:
  ```bash
  ./gradlew :androidApp:assembleDebug
  ```

  Готовый APK: `androidApp/build/outputs/apk/debug/`

### Web (Wasm)

* Запуск локального dev-сервера с hot reload:
  ```bash
  ./gradlew :webApp:wasmJsBrowserDevelopmentRun
  ```

* Сборка production-дистрибутива:
  ```bash
  ./gradlew :webApp:wasmJsBrowserDistribution
  ```

  Готовый бандл: `webApp/build/dist/wasmJs/productionExecutable/`

---

## Тестирование и качество кода

### Тесты

* Запуск всех тестов проекта:
  ```bash
  ./gradlew check
  ```

* Запуск тестов Android (Host):
  ```bash
  ./gradlew :shared:testAndroidHostTest
  ```

* Запуск тестов Web (Wasm в браузере):
  ```bash
  ./gradlew :shared:wasmJsBrowserTest
  ```

### Проверка стиля (ktlint)

* Проверка стиля кода:
  ```bash
  ./gradlew ktlintCheck
  ```

* Автоматическое форматирование:
  ```bash
  ./gradlew ktlintFormat
  ```
