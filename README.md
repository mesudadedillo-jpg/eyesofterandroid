# EYESOFTER Suite — Backend (Ktor)

Servidor de la app móvil de **EYESOFTER Suite**, el proyecto escolar de tamizaje
visual para la Escuela Primaria-Secundaria METANOIA (Tulancingo, Hgo.).
Recibe el inicio de sesión y entrega la lista de ópticas cercanas a la app.

- **Repositorio:** `https://github.com/mesudadedillo-jpg/eyesofterandroid`
- **En producción:** `https://eyesofterandroid-production.up.railway.app` (Railway)
- **La app que lo usa:** proyecto `eyesofter-app`, repositorio
  `https://github.com/mesudadedillo-jpg/eyesofterandroidfront`
- **Contrato entre ambos:** [`docs/API.md`](docs/API.md)

Hasta noviembre de 2026 el servidor y la app vivían juntos en un solo proyecto
Gradle. Se separaron para que cada uno se compile, pruebe y despliegue por su
cuenta. El historial anterior quedó en el respaldo del repositorio original.

## Tecnologías

Kotlin 2.4.20 · Ktor 3.6.0 (Netty) · kotlinx.serialization · Gradle 9.5.1 · Java 21 · Docker

## Estructura de carpetas

```
eyesofter-backend/
├── build.gradle.kts            Cómo se compila: plugins, librerías, clase main y nombre del JAR
├── settings.gradle.kts         Nombre del proyecto y repositorios de descarga
├── gradle.properties           Opciones de Gradle (memoria, caché)
├── gradle/
│   ├── libs.versions.toml      Versiones de todas las librerías en un solo lugar
│   ├── gradle-daemon-jvm.properties  Pide Java 21 para correr Gradle (se descarga solo)
│   └── wrapper/                Descarga la versión correcta de Gradle (no hay que instalarlo)
├── gradlew / gradlew.bat       Ejecutan Gradle (bash / cmd)
├── Dockerfile                  Cómo construir la imagen que corre en Railway
├── docs/API.md                 Contrato con la app
└── src/
    ├── main/kotlin/com/example/eyesofterapp/
    │   ├── Application.kt      main(): lee PORT y arranca Netty; module(): junta todo
    │   ├── plugins/            Funciones de Ktor que se instalan una vez
    │   │   ├── Serialization.kt   JSON ⇄ clases Kotlin
    │   │   └── Cors.kt            Permite llamadas desde la versión web de la app
    │   ├── routes/             Qué hace cada URL
    │   │   ├── Routing.kt         Índice de rutas + GET /
    │   │   ├── SaludRoutes.kt     GET /salud
    │   │   ├── AuthRoutes.kt      POST /api/login
    │   │   └── OpticasRoutes.kt   GET /opticas/cercanas (con validación)
    │   ├── data/               De dónde salen los datos (hoy archivos JSON)
    │   │   ├── UserRepository.kt  Lee users.json
    │   │   ├── OpticaRepository.kt Lee opticas.json, filtra y ordena
    │   │   └── Distancia.kt       Fórmula de Haversine
    │   └── model/              Forma del JSON que viaja (copia del contrato)
    │       ├── AuthDtos.kt        LoginRequestDto, UserDto, LoginResponseDto
    │       └── Optica.kt          Optica y SaludDto
    ├── main/resources/
    │   ├── users.json          Usuarios de prueba (contraseñas en texto plano, ver Pendientes)
    │   ├── opticas.json        Catálogo de 8 ópticas
    │   └── logback.xml         Formato de los mensajes en consola
    └── test/kotlin/...         Pruebas: ApplicationTest (login) y OpticasRoutesTest
```

La idea de la separación por carpetas: **`routes/` no sabe de dónde vienen los
datos y `data/` no sabe de HTTP**. Así, cuando llegue la base de datos, solo
cambia `data/`.

## Cómo ejecutarlo en Windows (Git Bash)

### 1. Requisito: Java 17 o más nuevo para arrancar Gradle

Si `java -version` dice `1.8`, usa el Java que trae Android Studio (solo para
esa terminal):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Para no repetirlo, agrégalo a `~/.bashrc`. Gradle descarga solo el Java 21 que
usa para compilar.

### 2. Pruebas

```bash
./gradlew test
```

Debe terminar en `BUILD SUCCESSFUL`. El reporte detallado queda en
`build/reports/tests/test/index.html`.

### 3. Correr el servidor

```bash
./gradlew run
```

Cuando veas `Responding at http://127.0.0.1:8080`, ya escucha (en realidad en
todas las redes, `0.0.0.0`). Detenlo con `Ctrl+C`. En otra terminal:

```bash
curl http://localhost:8080/salud
curl -X POST http://localhost:8080/api/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}'
curl "http://localhost:8080/opticas/cercanas?lat=20.0825&lng=-98.3622"
```

### 4. Construir el JAR (lo mismo que hace Docker)

```bash
./gradlew buildFatJar
java -jar build/libs/eyesofter-backend.jar
```

## Variables de entorno

| Variable | Para qué | Si no existe |
|---|---|---|
| `PORT` | Puerto donde escucha. Railway la asigna sola | `8080` |
| `USERS_FILE` | Ruta a otro `users.json` | Busca `src/main/resources/users.json`; si no está (Docker), usa la copia dentro del JAR |

## Despliegue en Railway

Railway detecta el `Dockerfile` en la raíz del repositorio y lo usa para
construir. El `Dockerfile` tiene dos etapas:

1. **build** — con un JDK 21 corre `./gradlew buildFatJar` y genera
   `build/libs/eyesofter-backend.jar` (un solo JAR con todas las librerías).
2. **ejecución** — con solo un JRE 21 copia ese JAR y lo arranca con
   `java -jar`. La imagen final es más ligera y no lleva el código fuente.

No hay que configurar comando de compilación ni de arranque en Railway.

## Cómo se conecta con la app

La app (`eyesofter-app`) tiene la URL de este servidor en **un solo lugar**:
`app/shared/src/commonMain/kotlin/com/example/eyesofterapp/data/network/PlatformConfig.kt`
→ `BASE_URL`. Para probar la app contra este servidor en tu computadora:

1. Corre `./gradlew run` aquí.
2. En la app cambia `BASE_URL` a `http://10.0.2.2:8080` (emulador) o a
   `http://<IP-de-tu-PC>:8080` (teléfono físico en la misma Wi-Fi).
3. Compila e instala la app.

Ya no comparten código: comparten el **contrato** de `docs/API.md`. Si cambias
un campo en `model/`, cámbialo también en la app y en las dos copias de `API.md`.

## Dónde entrará MySQL (todavía NO está conectado)

1. **Dependencias:** volver a agregar a `gradle/libs.versions.toml` y a
   `build.gradle.kts` HikariCP (`com.zaxxer:HikariCP:6.2.1`), MySQL Connector/J
   (`com.mysql:mysql-connector-j:9.2.0`) y bcrypt (`at.favre.lib:bcrypt:0.10.2`),
   las mismas versiones que tenía el proyecto original.
2. **Conexión:** un archivo nuevo `plugins/Database.kt` que cree el pool de
   HikariCP leyendo las variables que Railway da al agregar MySQL
   (`MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`).
   Se llamaría desde `Application.module()`.
3. **`data/UserRepository.kt`:** cambiar la lectura de `users.json` por una
   consulta a la tabla de usuarios, comparando la contraseña con bcrypt.
4. **`data/OpticaRepository.kt`:** cambiar `opticas.json` por una consulta a la
   tabla de ópticas.
5. **`routes/SaludRoutes.kt`:** hacer un `SELECT 1` y responder `db: true` si funciona.

Las rutas (`routes/`) y los DTOs (`model/`) no cambian, así que la app no se entera.

## Pendientes conocidos

- Contraseñas en texto plano en `users.json`, y ese archivo está en el repositorio.
- El token es simulado (`jwt-mock-token-…`); ninguna ruta lo exige todavía.
- CORS permite cualquier dominio (`anyHost()`).
- `logback.xml` está en nivel `trace`: la consola muestra muchísimos mensajes.
- Un JSON inválido en `/api/login` responde 500; lo correcto sería 400.
- El `Dockerfile` no se ha probado localmente (falta Docker en la computadora de desarrollo); Railway lo valida al desplegar.
