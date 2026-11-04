# EYESOFTER Suite — Backend (Ktor)

Servidor Ktor de la app móvil EYESOFTER Suite. Separado del proyecto
`eyesofterandroid`. *(README provisional: se completa en la Fase 4.)*

## Rutas

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/` | Texto de prueba `Hello, Ktor Server!` |
| GET | `/salud` | `{"status":"ok","db":false}` |
| POST | `/api/login` | Valida contra `users.json` y responde un token simulado |
| GET | `/opticas/cercanas?lat=&lng=` | Ópticas ordenadas por distancia (Haversine) |
| GET | `/opticas/cercanas?query=` | Ópticas filtradas por nombre, ciudad, dirección o código postal |

## Dónde entrará MySQL (todavía NO está conectado)

El servidor está organizado para que la base de datos solo toque la carpeta `data/`:

1. **Dependencias:** volver a agregar a `gradle/libs.versions.toml` y a
   `build.gradle.kts` HikariCP (`com.zaxxer:HikariCP:6.2.1`), MySQL Connector/J
   (`com.mysql:mysql-connector-j:9.2.0`) y bcrypt (`at.favre.lib:bcrypt:0.10.2`),
   las mismas versiones que tenía el proyecto original.
2. **Conexión:** un archivo nuevo `plugins/Database.kt` que cree el pool de
   HikariCP leyendo las variables de entorno que Railway da al agregar MySQL
   (`MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`).
   Se llamaría desde `Application.module()`.
3. **`data/UserRepository.kt`:** cambiar la lectura de `users.json` por una
   consulta a la tabla de usuarios, comparando la contraseña con bcrypt.
4. **`data/OpticaRepository.kt`:** cambiar la lectura de `opticas.json` por una
   consulta a la tabla de ópticas.
5. **`routes/SaludRoutes.kt`:** hacer un `SELECT 1` y responder `db: true` si funciona.

Las rutas (`routes/`) y los DTOs (`model/`) no cambian, así que la app no se entera.
