# CONTROL

This is a Kotlin Multiplatform project targeting Android and Web (Kotlin/Wasm).

* [/shared](./shared/src) contains code shared across all Compose Multiplatform targets:
  * [commonMain](./shared/src/commonMain/kotlin) — common code for all platforms.
  * [androidMain](./shared/src/androidMain/kotlin) — Android-specific implementations.
  * [wasmJsMain](./shared/src/wasmJsMain/kotlin) — Web (Wasm) specific implementations.
* [/androidApp](./androidApp) — Android application entry point.
* [/webApp](./webApp) — Web (Wasm) application entry point.

### Running the apps

* Android app: `./gradlew :androidApp:assembleDebug`
* Web app (Wasm): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`

### Running tests

* Android host tests: `./gradlew :shared:testAndroidHostTest`
* Web (Wasm) tests: `./gradlew :shared:wasmJsTest`