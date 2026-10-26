# Eyesofter Suite - Kotlin Multiplatform

Este es un proyecto Kotlin Multiplatform que incluye aplicaciones para Android, iOS, Web, Desktop (JVM) y Servidor Ktor.

## Backend en Producción (Railway)

El servidor Ktor backend se encuentra desplegado y activo en la nube en Railway:
- **URL Base Producción:** `https://eyesofterandroid-production.up.railway.app`

Todas las aplicaciones cliente (Android, iOS, Web, Desktop) están configuradas por defecto para conectarse directamente a este servidor en la nube sin necesidad de ejecutar un servidor local.

---

## Estructura del Proyecto

* [/app/iosApp](./app/iosApp/iosApp) contiene la aplicación iOS (SwiftUI).
* [/app/shared](./app/shared/src) contiene el código compartido entre todas las aplicaciones cliente:
  - [commonMain](./app/shared/src/commonMain/kotlin) define la constante `BASE_URL`, modelos, ViewModels y lógica común.
* [/core](./core/src) contiene utilidades compartidas adicionales.
* [/server](./server/src/main/kotlin) contiene la aplicación servidor Ktor.

---

## Ejecución de las Aplicaciones

Puedes ejecutar las aplicaciones cliente directamente (se conectan a Railway en la nube):

- **Android App:** `./gradlew :app:androidApp:assembleDebug`
- **Desktop App:**
  - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
  - Ejecución estándar: `./gradlew :app:desktopApp:run`
- **Web App:**
  - Wasm Target: `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
  - JS Target: `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- **iOS App:** Abre el directorio [/app/iosApp](./app/iosApp) en Xcode y ejecútalo.
- **Servidor Ktor Local (Opcional solo para desarrollo del servidor):** `./gradlew :server:run`

---

Aprende más sobre [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html) y [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/).
