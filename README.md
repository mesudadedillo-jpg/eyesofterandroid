# EyesofterSuite

**EyesofterSuite** es una aplicación Kotlin Multiplatform (KMP) diseñada para tutores de alumnos en escuelas. Permite consultar el resultado del tamizaje visual de sus hijos presentado de forma clara e intuitiva mediante un indicador tipo semáforo (Verde, Amarillo, Rojo) con iconografía accesible y recomendaciones pertinentes.

---

## 🛠️ Estructura de Módulos y Arquitectura

El proyecto está organizado en tres módulos principales y un proyecto cliente iOS:

### 1. `composeApp` (App Móvil Multiplataforma)
* **Descripción:** Capa de interfaz gráfica de usuario desarrollada con Jetpack Compose Multiplatform para Android e iOS.
* **Componentes:**
  * `navigation/`: Definición de rutas (`NavRoutes.kt`) y NavHost compartido (`AppNavigation.kt`).
  * `ui/screens/resultado/`: Pantalla de consulta de tamizaje (`ResultadoScreen.kt`) y su gestión de estado reactive (`ResultadoViewModel.kt`).
  * `ui/components/`: Componentes gráficos reutilizables como `SemaforoIndicador.kt` (combina color, símbolos e íconos para accesibilidad).
  * `ui/theme/`: Estilos de la aplicación con paleta Teal (`#164E59`) y Naranja (`#F39C12`).
  * `di/`: Módulo de inyección de dependencias Koin (`AppModule.kt`).

### 2. `shared` (Lógica de Negocio y Conectividad)
* **Descripción:** Módulo de código compartido entre la app móvil y el servidor backend.
* **Componentes:**
  * `model/`: Transferencia de datos (`ResultadoTamizaje.kt`, `Semaforo.kt`) serializables con `kotlinx.serialization`.
  * `network/`: Cliente HTTP Ktor (`HttpClientFactory.kt`) y servicio API (`ApiService.kt`).
  * `data/local/`: Almacenamiento y caché con DataStore Preferences (`ResultadoLocalDataSource.kt`) e implementación `expect`/`actual` (`DataStoreFactory`).
  * `data/repository/`: Repositorio offline-first (`ResultadoRepository.kt`) que intenta la consulta remota y respalda en almacenamiento local ante desconexión.
  * `config/`: Lectura de la URL base del backend desde `local.properties` mediante `BuildKonfig`.

### 3. `server` (Backend en Ktor)
* **Descripción:** Servidor HTTP ligero desarrollado en Ktor con motor Netty.
* **Componentes:**
  * `Application.kt`: Servidor embobado Netty ejecutado en el puerto `8080`.
  * `routes/`: Endpoints como `GET /api/alumnos/{id}/resultado` que sirven los datos del tamizaje.
  * `plugins/`: Configuración de ContentNegotiation con JSON, CORS y Routing.

### 4. `iosApp` (Proyecto Xcode para iOS)
* **Descripción:** Proyecto Xcode nativo que inicializa la vista `MainViewController` provista por `composeApp`.

---

## 💻 Código Compartido vs. Específico de Plataforma

* **Código Compartido (`shared/commonMain` y `composeApp/commonMain`):**
  * Lógica de dominio, DTOs, llamadas a la API REST, estrategia de caché local, ViewModels, estado de UI y vistas Compose.
* **Código Específico (`androidMain`, `iosMain`, `jvmMain`):**
  * **Android (`androidMain`):** `MainActivity.kt`, `AndroidManifest.xml` e instanciación de DataStore a partir del `Context` de Android.
  * **iOS (`iosMain`):** `MainViewController.kt` y resolución de rutas de almacenamiento en el sandbox de iOS (`NSDocumentDirectory`).
  * **JVM (`jvmMain`):** Implementación de DataStore para ejecuciones de escritorio.

---

## 🚀 Cómo Ejecutar el Proyecto

### Requisitos Previos
* JDK 17 o superior.
* Android Studio Ladybug (o posterior) con plugins de Kotlin Multiplatform.
* Xcode 15+ (opcional, para compilar y ejecutar en simulador/dispositivo iOS).

### 1. Iniciar el Servidor Backend (Ktor)
Ejecuta el siguiente comando Gradle desde la terminal del proyecto:

```bash
./gradlew :server:run
```

El servidor iniciará en `http://localhost:8080` (disponible para el emulador Android en `http://10.0.2.2:8080`).

Endpoint de prueba disponible:
* `GET http://localhost:8080/api/alumnos/1/resultado`
* `GET http://localhost:8080/api/alumnos/2/resultado`
* `GET http://localhost:8080/api/alumnos/3/resultado`

### 2. Ejecutar la Aplicación Móvil en Android
Asegúrate de tener un emulador Android en ejecución y corre:

```bash
./gradlew :composeApp:assembleDebug
```

O selecciona la configuración **composeApp** -> **Run** en Android Studio.

### 3. Compilar el Módulo Compartido
Para verificar la compilación de todos los módulos:

```bash
./gradlew assemble
```
