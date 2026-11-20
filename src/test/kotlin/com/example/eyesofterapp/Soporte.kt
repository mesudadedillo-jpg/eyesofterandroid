package com.example.eyesofterapp

import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.LoginResponseDto
import com.example.eyesofterapp.notificaciones.CanalNotificacion
import com.example.eyesofterapp.notificaciones.Destinatario
import com.example.eyesofterapp.notificaciones.ResultadoEnvio
import com.example.eyesofterapp.repos.NotificacionRecord
import com.example.eyesofterapp.seguridad.CifradoDatos
import com.example.eyesofterapp.seguridad.PasswordIdentity
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

const val API_KEY_PRUEBA = "clave-escritorio-prueba"
const val PASSWORD = "Eyesofter123!"
const val VERSION_AVISO = "2026-11"

/** Configuración de pruebas: sin canales reales ni ciclos automáticos (las pruebas los llaman a mano). */
fun configPrueba() = Configuracion(
    databaseUrl = "",
    claveDatos = CifradoDatos.CLAVE_DESARROLLO,
    jwtSecret = "secreto-de-prueba",
    apiKeyEscritorio = API_KEY_PRUEBA,
    firebaseServiceAccountJson = null,
    resendApiKey = null,
    correoRemitente = null,
    urlPortal = "https://portal.prueba",
    versionAviso = VERSION_AVISO,
    recordatorioRevisionMin = null,
    despachoSegundos = null,
)

/**
 * PostgreSQL para las pruebas, con el MISMO esquema que crea el portal (esquema.sql).
 *  - Si existe PRUEBAS_DATABASE_URL (jdbc:postgresql://...), se usa esa base y se BORRA su
 *    contenido. Útil en contenedores que corren como root (PostgreSQL embebido no arranca como root).
 *  - Si no, se levanta un PostgreSQL embebido (io.zonky) que se apaga al terminar.
 */
object BasePrueba {
    val db: BaseDeDatos by lazy {
        val externa = System.getenv("PRUEBAS_DATABASE_URL")
        val fuente = if (externa != null) {
            HikariDataSource(HikariConfig().apply { jdbcUrl = externa; maximumPoolSize = 8 })
        } else {
            EmbeddedPostgres.start().also { pg -> Runtime.getRuntime().addShutdownHook(Thread { pg.close() }) }.postgresDatabase
        }
        fuente.connection.use { c ->
            c.createStatement().use { it.execute("drop schema if exists public cascade; create schema public;") }
            val esquema = BasePrueba::class.java.getResource("/esquema.sql")!!.readText().removePrefix("﻿")
            c.createStatement().use { it.execute(esquema) }
        }
        BaseDeDatos(fuente)
    }

    private val hash by lazy { PasswordIdentity.hash(PASSWORD) }
    private val cifrado = CifradoDatos(CifradoDatos.CLAVE_DESARROLLO)

    /** Deja la base con los datos de ejemplo de siempre. Se llama antes de cada prueba. */
    fun reiniciar(ahora: Instant = Instant.now()) = runBlocking {
        db.transaccion { c ->
            val tablas = c.createStatement().use { st ->
                st.executeQuery("select tablename from pg_tables where schemaname = 'public' and tablename <> '__EFMigrationsHistory'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
            c.createStatement().use { it.execute("truncate ${tablas.joinToString { "\"$it\"" }} restart identity cascade") }
            sembrar(c, ahora)
        }
    }

    private fun sembrar(c: Connection, ahora: Instant) {
        listOf("Administrador", "Enfermeria", "Tutor", "Alumno", "Especialista").forEach {
            c.ejecutar("insert into asp_net_roles (id, name, normalized_name) values (?, ?, ?)", "rol-$it", it, it.uppercase())
        }
        c.ejecutar("insert into planteles (id, nombre, activo) overriding system value values (1, 'Primaria Benito Juárez', true)")
        c.ejecutar("insert into campanas (id, nombre, ciclo_escolar, fecha_inicio, fecha_fin, plantel_id) overriding system value values (1, 'Campaña otoño 2026', '2026-2027', '2026-08-01', null, 1)")
        c.ejecutar("insert into tutores (id, nombre, apellidos, telefono, correo, parentesco) overriding system value values (1, 'María', 'López', '7751110000', 'tutor@eyesofter.mx', 'Madre'), (2, 'Pedro', 'Ruiz', '7752220000', 'tutor2@eyesofter.mx', 'Padre')")
        c.ejecutar("""insert into alumnos (id, matricula, nombre, apellidos, fecha_nacimiento, nivel, grado, grupo, usa_lentes, tutor_id, plantel_id) overriding system value values
            (1, 'A001', 'Ana', 'López', '2017-03-01', 'Primaria', 3, 'B', false, 1, 1),
            (2, 'A002', 'Luis', 'Ruiz', '2016-05-01', 'Primaria', 4, 'A', false, 2, 1),
            (3, 'A003', 'Sofía', 'López', '2018-01-10', 'Primaria', 2, 'A', true, 1, 1)""")
        c.ejecutar("""insert into clinicas (id, nombre, tipo, direccion, telefono, correo, ciudad, codigo_postal, latitud, longitud, horario, es_asociada, activa) overriding system value values
            (1, 'Óptica Tulancingo Centro', 'Optica', 'Av. Juárez 10', '7751000001', null, 'Tulancingo', '43600', 20.0830, -98.3630, 'L-V 9 a 18', true, true),
            (2, 'Consultorio Valle', 'Consultorio', 'Calle 5 de Mayo 20', '7751000002', null, 'Tulancingo', '43610', 20.0700, -98.3500, null, false, true),
            (3, 'Óptica Cerrada', 'Optica', 'Calle Hidalgo 3', '7751000003', null, 'Tulancingo', '43600', 20.0831, -98.3631, null, true, false),
            (4, 'Oftalmología Norte', 'Oftalmologia', 'Blvd. Norte 100', '7751000004', null, 'Tulancingo', '43620', null, null, null, false, true),
            (5, 'Óptica Lejana Asociada', 'Optica', 'Carretera 1', '7751000005', null, 'Pachuca', '42000', 20.1200, -98.7300, null, true, true)""")
        cuenta(c, "direccion@eyesofter.mx", "Dirección Escolar", "Administrador")
        cuenta(c, "enfermeria@eyesofter.mx", "Enfermería Escolar", "Enfermeria")
        cuenta(c, "tutor@eyesofter.mx", "María López", "Tutor", tutorId = 1)
        cuenta(c, "tutor2@eyesofter.mx", "Pedro Ruiz", "Tutor", tutorId = 2)
        cuenta(c, "alumno@eyesofter.mx", "Ana López", "Alumno", alumnoId = 1)
        cuenta(c, "especialista@eyesofter.mx", "Dra. Óptica", "Especialista", clinicaId = 1)
        cuenta(c, "inactivo@eyesofter.mx", "Cuenta Baja", "Tutor", tutorId = 1, activo = false)
        // Los tutores de las pruebas ya aceptaron el aviso; la prueba de consentimiento lo borra.
        listOf("tutor@eyesofter.mx", "tutor2@eyesofter.mx").forEach {
            c.ejecutar("insert into consentimientos (usuario_id, version_aviso, aceptado_en, medio) values (?, ?, ?, 'web')", idDe(it), VERSION_AVISO, ahora.minus(1, ChronoUnit.DAYS))
        }
    }

    private fun cuenta(c: Connection, email: String, nombre: String, rol: String, tutorId: Int? = null, alumnoId: Int? = null, clinicaId: Int? = null, activo: Boolean = true) {
        c.ejecutar(
            """insert into asp_net_users (id, nombre_completo, tutor_id, alumno_id, clinica_id, activo, user_name, normalized_user_name, email, normalized_email,
                 email_confirmed, password_hash, security_stamp, phone_number_confirmed, two_factor_enabled, lockout_enabled, access_failed_count)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, false, false, true, 0)""",
            idDe(email), nombre, tutorId, alumnoId, clinicaId, activo, email, email.uppercase(), email, email.uppercase(), hash, "SELLO-$email"
        )
        c.ejecutar("insert into asp_net_user_roles (user_id, role_id) values (?, ?)", idDe(email), "rol-$rol")
    }

    fun idDe(email: String) = "id-" + email.substringBefore('@')

    fun <T> consulta(sql: String, vararg p: Any?, fila: (java.sql.ResultSet) -> T): T? = runBlocking { db.leer { it.uno(sql, *p, fila = fila) } }
    fun ejecutar(sql: String, vararg p: Any?): Int = runBlocking { db.transaccion { it.ejecutar(sql, *p) } }
    fun cuenta(sql: String, vararg p: Any?): Int = consulta(sql, *p) { it.getInt(1) } ?: 0
    fun descifrar(valor: String?) = cifrado.descifrar(valor)
}

/** Canal que no envía nada: anota lo que le pidieron y responde lo que se le diga. */
class CanalFalso(override val nombre: String, var exito: Boolean = true) : CanalNotificacion {
    data class Envio(val usuario: String, val notificacion: NotificacionRecord, val esRecordatorio: Boolean)

    val envios = mutableListOf<Envio>()

    override suspend fun enviar(notificacion: NotificacionRecord, destinatario: Destinatario, esRecordatorio: Boolean): ResultadoEnvio {
        synchronized(envios) { envios += Envio(destinatario.email ?: destinatario.id, notificacion, esRecordatorio) }
        return ResultadoEnvio(exito, if (exito) "ok" else "falló a propósito")
    }
}

/** Reloj que las pruebas pueden adelantar. */
class RelojFalso(var ahora: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)) {
    fun avanzarDias(dias: Long) { ahora = ahora.plus(dias, ChronoUnit.DAYS) }
}

/** Servicios sobre la base de prueba recién reiniciada. */
fun serviciosPrueba(reloj: RelojFalso = RelojFalso(), canales: List<CanalNotificacion> = emptyList(), config: Configuracion = configPrueba()): Servicios {
    BasePrueba.reiniciar(reloj.ahora)
    return Servicios(config, db = BasePrueba.db, canales = { canales }, reloj = { reloj.ahora })
}

/** Levanta el servidor en memoria y devuelve un cliente JSON. */
fun ApplicationTestBuilder.servidor(servicios: Servicios): HttpClient {
    application { modulo(servicios) }
    return createClient { install(ContentNegotiation) { json() } }
}

suspend fun HttpClient.login(usuario: String, password: String = PASSWORD): HttpResponse =
    post("/api/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequestDto(usuario, password))
    }

suspend fun HttpClient.tokenDe(usuario: String, password: String = PASSWORD): String = login(usuario, password).body<LoginResponseDto>().token
