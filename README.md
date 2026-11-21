# EYESOFTER Suite — Servidor de la app (Ktor)

Servidor de la app móvil y del sistema de escritorio de **EYESOFTER Suite**, el proyecto
escolar de tamizaje visual para la Escuela Primaria-Secundaria METANOIA (Tulancingo, Hgo.).

- Inicio de sesión de la app con **las mismas cuentas del portal web**.
- Recibe los tamizajes del sistema de escritorio y abre los casos.
- Expediente para el tutor: resultado, destino, **pase médico con QR**, citas y comprobantes.
- **Entrega las notificaciones** (push con Firebase y correo con Resend) de todo el sistema,
  también las que crea el portal.
- Directorio de clínicas para el mapa.

| | |
|---|---|
| Repositorio | `https://github.com/mesudadedillo-jpg/eyesofterandroid` |
| En producción | `https://eyesofterandroid-production.up.railway.app` (Railway) |
| App móvil | `https://github.com/mesudadedillo-jpg/eyesofterandroidfront` |
| Portal web (dueño de la base y de las cuentas) | `https://github.com/mesudadedillo-jpg/eyesofterweb` |
| Contrato de la API | [`docs/API.md`](docs/API.md) |

## Arquitectura

```
  App móvil ───────┐
                   ├──► este servidor (Ktor, Railway) ──┐
  Escritorio ──────┘                                    ├──► PostgreSQL (Railway), UNA sola base
  Navegador ──────────► portal web (ASP.NET, Railway) ──┘
```

- El **portal web** crea las tablas (migraciones de EF Core) y administra las cuentas
  (ASP.NET Identity). **Este servidor no crea tablas ni cuentas**: lee y escribe en las del portal.
- Contraseñas: se verifican contra el hash PBKDF2-HMAC-SHA512 de Identity (C-RC-05).
- Bloqueo: 3 fallos → 15 min con las mismas columnas que la web (C-RFNN-01).
- Datos sensibles cifrados con AES-256-GCM y la **misma llave** que el portal (C-RC-04).
- Notificaciones: la tabla `notificaciones` es una **bandeja de salida**. El portal y este servidor
  dejan ahí lo que hay que avisar; el *despachador* de este servidor lo entrega en segundos.
- `src/main/resources/esquema.sql` es una copia del esquema del portal **solo para las pruebas**.
  Si el portal agrega una migración, regenérala (ver "Pruebas").

## Tecnologías

Kotlin 2.4.20 · Ktor 3.6.0 (Netty, JWT, límite por IP, cliente CIO) · PostgreSQL (JDBC + HikariCP, sin ORM, consultas parametrizadas) · ZXing (QR) · Firebase Cloud Messaging (API HTTP v1) · Resend (correo) · Gradle 9.5.1 · Java 21 · Docker

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
    │   ├── Servicios.kt        Arma y conecta base, repositorios, canales, despachador y recordatorios
    │   ├── config/Configuracion.kt  Todas las variables de entorno en un solo lugar
    │   ├── db/BaseDeDatos.kt   Pool de conexiones, transacciones y ayudantes JDBC
    │   ├── seguridad/
    │   │   ├── PasswordIdentity.kt  Verifica contraseñas con el formato de ASP.NET Identity
    │   │   └── CifradoDatos.kt      AES-256-GCM compatible con el portal
    │   ├── plugins/            Funciones de Ktor que se instalan una vez
    │   │   ├── Security.kt        JWT; en cada petición revisa en la base que la cuenta siga activa
    │   │   ├── Proteccion.kt      IP real tras el proxy, 30 intentos/min por IP, cabeceras
    │   │   ├── Errores.kt         Excepciones → {"error": ...}
    │   │   ├── Cors.kt, Serialization.kt
    │   ├── repos/              SQL contra la base compartida
    │   │   ├── Usuarios.kt        Cuentas de Identity, bloqueo, auditoría, dispositivos
    │   │   ├── Notificaciones.kt  Bandeja compartida y bandeja de salida
    │   │   ├── Expedientes.kt     Tamizajes, casos, destino y pase, citas (mismas reglas que el portal)
    │   │   ├── Comprobantes.kt    Recetas cifradas, consentimientos y reportes
    │   │   ├── Clinicas.kt        Directorio
    │   │   └── Enums.kt           Nombres de la base ⇄ nombres de la app
    │   ├── notificaciones/
    │   │   ├── Despachador.kt     Entrega la bandeja de salida: push → correo
    │   │   ├── Recordatorios.kt   Avisos sin leer (3 días) y casos sin atender
    │   │   ├── CanalPushFcm.kt, CanalCorreoResend.kt, CanalNotificacion.kt
    │   ├── routes/             Qué hace cada URL (ver docs/API.md)
    │   └── model/              Forma del JSON que viaja (copia del contrato)
    ├── main/resources/
    │   ├── esquema.sql         Copia del esquema del portal, solo para las pruebas
    │   └── logback.xml
    └── test/kotlin/...         Pruebas de punta a punta contra PostgreSQL real
```

La idea de la separación por carpetas: **`routes/` no escribe SQL y `repos/` no sabe de HTTP**.

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

Las pruebas usan un PostgreSQL de verdad con el esquema del portal y cuentas con
contraseñas en formato Identity.

```bash
./gradlew test
```

- Sin configurar nada, levantan un **PostgreSQL embebido** (se descarga solo la primera vez).
- Si no puede arrancar (por ejemplo, en un contenedor que corre como `root`), usa un PostgreSQL
  propio. **Su contenido se borra**, así que crea una base solo para esto:

```bash
createdb eyesofter_pruebas
PRUEBAS_DATABASE_URL="jdbc:postgresql://localhost:5432/eyesofter_pruebas?user=postgres&password=postgres" ./gradlew test
```

Si el portal agrega una migración, actualiza la copia del esquema (desde `eyesofterweb/EyesofterWeb`):

```bash
dotnet ef migrations script --idempotent -o ../../eyesofterandroid/src/main/resources/esquema.sql
```

### 3. Correr el servidor

Necesita la base del portal. Lo más fácil: corre primero el portal en tu computadora
(crea la base y las cuentas demo, contraseña `Eyesofter123!`) y luego:

```bash
DATABASE_URL="jdbc:postgresql://localhost:5432/eyesofter?user=postgres&password=postgres" ESCRITORIO_API_KEY=k123 URL_PORTAL=http://localhost:5080 ./gradlew run
```

En otra terminal:

```bash
curl http://localhost:8080/salud
curl -X POST http://localhost:8080/api/login -H "Content-Type: application/json" -d '{"username":"tutor@eyesofter.mx","password":"Eyesofter123!"}'
curl "http://localhost:8080/opticas/cercanas?lat=20.0825&lng=-98.3622"
curl -X POST http://localhost:8080/api/escritorio/tamizajes -H "X-Api-Key: k123" -H "Content-Type: application/json" -d '{"folio":"F1","matricula":"PRI2026003","fecha":"2026-11-19","semaforo":"AMARILLO","agudezaOjoDerecho":"20/40","agudezaOjoIzquierdo":"20/30"}'
curl http://localhost:8080/api/tamizajes -H "Authorization: Bearer <token del login>"
```

### 4. Construir el JAR (lo mismo que hace Docker)

```bash
./gradlew buildFatJar
java -jar build/libs/eyesofter-backend.jar
```

## Variables de entorno

| Variable | Para qué | Si no existe |
|---|---|---|
| `DATABASE_URL` | La base PostgreSQL **del portal**. En Railway: `${{Postgres.DATABASE_URL}}`. Acepta `postgresql://…` o `jdbc:postgresql://…` | `localhost:5432/eyesofter` (postgres/postgres) |
| `EYESOFTER_CLAVE_DATOS` | Llave AES-256 en Base64 (32 bytes). **La misma que el portal.** Obligatoria en Railway | Llave de desarrollo (con aviso). **En Railway no arranca** |
| `JWT_SECRET` | Secreto de las sesiones de la app. **Obligatoria en Railway** | En local genera uno temporal. **En Railway no arranca** |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | Cuenta de administrador que se crea al arrancar si no existe. Sin el portal web es la única forma de tener la primera cuenta | No se crea ninguna (aviso en el log si la base no tiene cuentas) |
| `CREAR_ESQUEMA` | `false` para NO crear las tablas del portal al arrancar. Por defecto se corre `esquema.sql`, que es idempotente y deja anotada la migración en `__EFMigrationsHistory`, así que el portal no choca después | Se crean si faltan |
| `JWT_DIAS` | Duración de la sesión | `30` |
| `ESCRITORIO_API_KEY` | Clave del sistema de escritorio (`X-Api-Key`) | Las rutas `/api/escritorio/…` responden 503 |
| `URL_PORTAL` | URL pública del portal: va dentro del QR del pase y en los enlaces de privacidad | `http://localhost:5080` |
| `VERSION_AVISO_PRIVACIDAD` | Igual que `Eyesofter__VersionAvisoPrivacidad` del portal | `2026-11` |
| `DIAS_VIGENCIA_PASE` | Igual que `Eyesofter__DiasVigenciaPase` del portal | `60` |
| `FIREBASE_SERVICE_ACCOUNT` | **Contenido** del JSON de la cuenta de servicio de Firebase | Sin push (aviso al arrancar) |
| `FIREBASE_SERVICE_ACCOUNT_FILE` | Alternativa: ruta a ese JSON | — |
| `RESEND_API_KEY` / `CORREO_REMITENTE` | Correo de respaldo con [Resend](https://resend.com); remitente de un dominio verificado | Sin correo |
| `DESPACHO_SEGUNDOS` | Cada cuántos segundos se entrega la bandeja de salida | `3` |
| `RECORDATORIO_DIAS` / `RECORDATORIO_MAX` / `RECORDATORIO_REVISION_MIN` | Avisos sin leer: días, máximo de reenvíos, cada cuántos minutos se revisa | `3` / `3` / `60` |
| `LOGIN_INTENTOS_MAX` / `LOGIN_BLOQUEO_MIN` | Bloqueo de cuenta (deben coincidir con el portal) | `3` / `15` |
| `PORT`, `RAILWAY_ENVIRONMENT`, `RAILWAY_GIT_COMMIT_SHA` | Las pone Railway | `8080`, —, `dev` |

Para generar secretos: `openssl rand -base64 32`.

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

### Checklist de despliegue (proyecto de Railway con 3 servicios)

1. **PostgreSQL**: en el proyecto de Railway, *+ New → Database → PostgreSQL*.
2. **Portal web** (`eyesofterweb`) desplegado primero: crea las tablas y la cuenta de Dirección.
3. **Este servidor** → *Variables*:
   - `DATABASE_URL` = `${{Postgres.DATABASE_URL}}` (referencia; usa la red privada de Railway)
   - `EYESOFTER_CLAVE_DATOS` = **la misma** que el portal
   - `JWT_SECRET`, `ESCRITORIO_API_KEY` (generadas con `openssl rand -base64 32`)
   - `URL_PORTAL` = la URL pública del portal
   - `FIREBASE_SERVICE_ACCOUNT` y, si habrá correo, `RESEND_API_KEY` y `CORREO_REMITENTE`
4. **Firebase** (el mismo proyecto que la app): consola → ⚙ *Configuración del proyecto* →
   *Cuentas de servicio* → *Generar nueva clave privada*; pega el JSON completo en
   `FIREBASE_SERVICE_ACCOUNT`. Para iOS, sube la llave APNs en *Cloud Messaging*.
   **Nunca subas ese JSON al repositorio.**
5. Revisa `GET /salud`: `db: true` y `push`/`correo`/`escritorio` en `true`.
6. Ya **no** hace falta el volumen `/app/data`: todo vive en PostgreSQL. Si lo creaste antes, puedes quitarlo.

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

## Pendientes conocidos

- **Firebase**: falta crear el proyecto y cargar `FIREBASE_SERVICE_ACCOUNT`; mientras tanto los avisos
  quedan en la bandeja (la app los muestra al abrirla) y, si hay Resend, llegan por correo.
- El sistema de escritorio debe adoptar el contrato de `docs/API.md` §13 (matrícula en lugar de
  `tutorUsername`, cola de reintentos e incidencias).
- CORS permite cualquier dominio: la API usa tokens Bearer (no cookies), así que una página ajena
  no puede actuar en nombre de nadie sin su token.
- `esquema.sql` es una copia manual del esquema del portal (ver "Pruebas").
