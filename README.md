# EYESOFTER Suite — Backend (Ktor)

Servidor de la app móvil de **EYESOFTER Suite**, el proyecto escolar de tamizaje
visual para la Escuela Primaria-Secundaria METANOIA (Tulancingo, Hgo.).
Recibe el inicio de sesión, entrega la lista de ópticas cercanas, recibe los
resultados de tamizaje del sistema de escritorio y avisa a los tutores con
notificaciones push (y correo de respaldo).

- **Repositorio:** `https://github.com/mesudadedillo-jpg/eyesofterandroid`
- **En producción:** `https://eyesofterandroid-production.up.railway.app` (Railway)
- **La app que lo usa:** proyecto `eyesofter-app`, repositorio
  `https://github.com/mesudadedillo-jpg/eyesofterandroidfront`
- **Contrato entre ambos:** [`docs/API.md`](docs/API.md)

Hasta noviembre de 2026 el servidor y la app vivían juntos en un solo proyecto
Gradle. Se separaron para que cada uno se compile, pruebe y despliegue por su
cuenta. El historial anterior quedó en el respaldo del repositorio original.

## Tecnologías

Kotlin 2.4.20 · Ktor 3.6.0 (Netty, JWT, cliente CIO) · kotlinx.serialization · Firebase Cloud Messaging (API HTTP v1) · Resend (correo) · Gradle 9.5.1 · Java 21 · Docker

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
├── railway.json                Configuración de Railway: usar el Dockerfile, healthcheck en /salud, reinicio si falla
├── docs/API.md                 Contrato con la app
└── src/
    ├── main/kotlin/com/example/eyesofterapp/
    │   ├── Application.kt      main(): lee PORT y arranca Netty; module(): junta todo
    │   ├── Servicios.kt        Arma y conecta repositorios, canales, notificador y recordatorios
    │   ├── config/
    │   │   └── Configuracion.kt   Todas las variables de entorno en un solo lugar
    │   ├── plugins/            Funciones de Ktor que se instalan una vez
    │   │   ├── Serialization.kt   JSON ⇄ clases Kotlin
    │   │   ├── Cors.kt            Permite llamadas desde la versión web de la app
    │   │   ├── Security.kt        Crea y verifica el JWT; call.sesion() dice quién llama
    │   │   └── Errores.kt         Excepciones → {"error": ...} con 400/500
    │   ├── routes/             Qué hace cada URL
    │   │   ├── Routing.kt         Índice de rutas (públicas, escritorio y con sesión)
    │   │   ├── SaludRoutes.kt     GET /salud
    │   │   ├── AuthRoutes.kt      POST /api/login
    │   │   ├── OpticasRoutes.kt   GET /opticas/cercanas (con validación)
    │   │   ├── NotificacionesRoutes.kt  Bandeja, lectura, avisos y seguimiento
    │   │   ├── DispositivosRoutes.kt    Registro del token de push
    │   │   ├── TamizajesRoutes.kt       Recepción desde escritorio y consulta del tutor
    │   │   ├── ComprobantesRoutes.kt    Subida de recetas/comprobantes y su revisión
    │   │   └── Conversiones.kt          Registros → DTOs, bitácora de auditoría
    │   ├── notificaciones/     Cómo se le avisa a la gente
    │   │   ├── CanalNotificacion.kt  Interfaz común (push, correo, y en el futuro SMS/WhatsApp)
    │   │   ├── CanalPushFcm.kt       Firebase Cloud Messaging
    │   │   ├── CanalCorreoResend.kt  Correo de respaldo
    │   │   ├── Notificador.kt        Guarda en la bandeja y prueba los canales en orden
    │   │   └── Recordatorios.kt      Reenvía avisos sin leer a los 3 días y alerta a Enfermería
    │   ├── data/               De dónde salen los datos (hoy archivos JSON)
    │   │   ├── UserRepository.kt  Lee users.json
    │   │   ├── OpticaRepository.kt Lee opticas.json, filtra y ordena
    │   │   ├── Distancia.kt       Fórmula de Haversine
    │   │   ├── AlmacenJson.kt     Lista guardada en un archivo JSON (reemplazo provisional de tabla)
    │   │   ├── NotificacionRepository.kt  Bandeja de todos los usuarios
    │   │   ├── DispositivoRepository.kt   Tokens de push por usuario
    │   │   ├── TamizajeRepository.kt      Resultados recibidos del escritorio
    │   │   ├── ComprobanteRepository.kt   Recetas y comprobantes (archivos en DATA_DIR/comprobantes/)
    │   │   └── BloqueoLogin.kt            Bloqueo tras 3 intentos fallidos
    │   └── model/              Forma del JSON que viaja (copia del contrato)
    │       ├── AuthDtos.kt        LoginRequestDto, UserDto, LoginResponseDto
    │       ├── Optica.kt          Optica y SaludDto
    │       ├── NotificacionDtos.kt Bandeja, dispositivos, avisos, seguimiento
    │       └── TamizajeDtos.kt    Entrada del escritorio y lo que ve el tutor
    ├── main/resources/
    │   ├── users.json          Usuarios demo (contraseñas en texto plano, ver Pendientes). Campo "email" opcional
    │   ├── opticas.json        Catálogo de 8 ópticas
    │   └── logback.xml         Formato de los mensajes en consola
    └── test/
        ├── kotlin/...          Pruebas: login, ópticas, notificaciones de punta a punta, canales
        └── resources/users-test.json  Usuarios fijos de las pruebas (no dependen de users.json)
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

Probar el flujo de notificaciones (arranca con `ESCRITORIO_API_KEY=k123 ./gradlew run`):

```bash
# 1. El escritorio manda un resultado para el tutor "cliente"
curl -X POST http://localhost:8080/api/escritorio/tamizajes -H "X-Api-Key: k123" -H "Content-Type: application/json" -d '{"folio":"F1","alumnoNombre":"Ana","tutorUsername":"cliente","fecha":"2026-11-18","semaforo":"VERDE"}'
# 2. El tutor inicia sesión y ve su bandeja (copia el token de la respuesta del login)
curl -X POST http://localhost:8080/api/login -H "Content-Type: application/json" -d '{"username":"cliente","password":"999"}'
curl http://localhost:8080/api/notificaciones -H "Authorization: Bearer <token>"
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
| `JWT_SECRET` | Secreto para firmar las sesiones. **Obligatoria en Railway** (texto largo y aleatorio) | Genera uno temporal: todas las sesiones se pierden al reiniciar |
| `JWT_DIAS` | Duración de la sesión | `30` |
| `ESCRITORIO_API_KEY` | Clave que manda el sistema de escritorio en `X-Api-Key` | `POST /api/escritorio/tamizajes` responde 503 |
| `DATA_DIR` | Carpeta de `notificaciones.json`, `dispositivos.json` y `tamizajes.json` | `data` (en Docker: `/app/data`) |
| `FIREBASE_SERVICE_ACCOUNT` | **Contenido** del JSON de la cuenta de servicio de Firebase | Sin push (avisa en la consola al arrancar) |
| `FIREBASE_SERVICE_ACCOUNT_FILE` | Alternativa: ruta a ese JSON | — |
| `RESEND_API_KEY` | API key de [Resend](https://resend.com) para el correo de respaldo | Sin correo |
| `CORREO_REMITENTE` | Remitente, p. ej. `EYESOFTER <avisos@tudominio.com>` (dominio verificado en Resend) | Sin correo |
| `RECORDATORIO_DIAS` | Días sin leer antes de reenviar y alertar a Enfermería | `3` |
| `RECORDATORIO_MAX` | Máximo de reenvíos por aviso | `3` |
| `RECORDATORIO_REVISION_MIN` | Cada cuántos minutos se buscan avisos sin leer | `60` |
| `LOGIN_INTENTOS_MAX` | Intentos fallidos seguidos antes de bloquear | `3` |
| `LOGIN_BLOQUEO_MIN` | Minutos de bloqueo | `15` |
| `RAILWAY_GIT_COMMIT_SHA` | La pone Railway sola; `/salud` la muestra como `version` | `dev` |

## Despliegue en Railway

Railway detecta el `Dockerfile` en la raíz del repositorio y lo usa para
construir. El `Dockerfile` tiene dos etapas:

1. **build** — con un JDK 21 corre `./gradlew buildFatJar` y genera
   `build/libs/eyesofter-backend.jar` (un solo JAR con todas las librerías).
2. **ejecución** — con solo un JRE 21 copia ese JAR y lo arranca con
   `java -jar`. La imagen final es más ligera y no lleva el código fuente.

No hay que configurar comando de compilación ni de arranque en Railway: `railway.json`
le dice que use el `Dockerfile`, que revise `GET /salud` antes de dar por bueno un
despliegue (si el servidor no arranca, Railway mantiene la versión anterior) y que
lo reinicie si se cae.

**Railway despliega la rama `main`.** Lo que esté en otras ramas no llega a
producción hasta que se fusione. Para confirmar qué versión está corriendo:

```bash
curl https://eyesofterandroid-production.up.railway.app/salud
```

`version` debe coincidir con los primeros 7 caracteres del último commit de `main`.

### Checklist para el despliegue con notificaciones

1. **Variables** en Railway → servicio → *Variables*: `JWT_SECRET`, `ESCRITORIO_API_KEY`,
   `FIREBASE_SERVICE_ACCOUNT` y, si habrá correo, `RESEND_API_KEY` y `CORREO_REMITENTE`.
   Para generar secretos: `openssl rand -base64 32`.
2. **Volumen** (sin esto, cada despliegue borra notificaciones, dispositivos y tamizajes):
   Railway → servicio → *Settings* → *Volumes* → montar en `/app/data`.
3. **Firebase** (el mismo proyecto que use la app):
   consola de Firebase → ⚙ *Configuración del proyecto* → *Cuentas de servicio* →
   *Generar nueva clave privada*. Pega el contenido completo del JSON en
   `FIREBASE_SERVICE_ACCOUNT`. Para iOS, sube además la llave APNs en *Cloud Messaging*.
   **Nunca subas ese JSON al repositorio.**
4. Al arrancar, la consola de Railway no debe mostrar los avisos
   `FIREBASE_SERVICE_ACCOUNT no está definida` ni `JWT_SECRET no está definida`.

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

- Contraseñas en texto plano en `users.json`, y ese archivo está en el repositorio (C-RC-05 pide bcrypt).
- Falta la relación tutor ↔ alumno: el escritorio indica el tutor con `tutorUsername`.
  Cuando haya MySQL debería salir de una tabla de alumnos.
- `users.json` no tiene correos todavía: sin `email` no hay correo de respaldo.
- Los datos de notificaciones viven en archivos JSON (`DATA_DIR`), pensados para el piloto;
  pasarán a MySQL junto con lo demás.
- No hay recuperación de contraseña por correo (C-RFNN-02).
- Falta el pase médico con código QR (C-RFN-05) y el registro de atenciones de las clínicas.
- CORS permite cualquier dominio (`anyHost()`).
- El `Dockerfile` no se ha probado localmente (falta Docker en la computadora de desarrollo); Railway lo valida al desplegar.
