package com.example.eyesofterapp.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Conexión a la base PostgreSQL que comparte con el portal web. El esquema lo crean las
 * migraciones de EF Core del portal (eyesofterweb); este servidor NO crea tablas.
 * Para las pruebas existe una copia del esquema en src/main/resources/esquema.sql.
 *
 * Se usa JDBC directo (sin ORM) con consultas parametrizadas: nunca se concatena lo que
 * escribe el usuario en el SQL (inyección SQL).
 */
class BaseDeDatos(val fuente: DataSource) {

    /** Ejecuta en un hilo de E/S, dentro de una transacción. Si algo falla, se deshace todo. */
    suspend fun <T> transaccion(bloque: (Connection) -> T): T = withContext(Dispatchers.IO) {
        fuente.connection.use { c ->
            c.autoCommit = false
            try {
                val r = bloque(c)
                c.commit()
                r
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }

    suspend fun <T> leer(bloque: (Connection) -> T): T = withContext(Dispatchers.IO) {
        fuente.connection.use { c -> c.isReadOnly = true; bloque(c) }
    }

    fun cerrar() { (fuente as? HikariDataSource)?.close() }

    companion object {
        /**
         * Acepta DATABASE_URL de Railway (postgresql://usuario:clave@host:puerto/base)
         * o una URL JDBC (jdbc:postgresql://...).
         */
        fun desdeUrl(url: String, maxConexiones: Int = 20): BaseDeDatos {
            val config = HikariConfig()
            if (url.startsWith("jdbc:")) {
                config.jdbcUrl = url
            } else {
                val uri = URI(url)
                val (usuario, clave) = (uri.rawUserInfo ?: ":").split(":", limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) }.let { it[0] to it.getOrElse(1) { "" } }
                val puerto = if (uri.port > 0) uri.port else 5432
                // En la red privada de Railway no hay TLS; fuera de ella se exige (C-RC-03).
                val ssl = if (uri.host.endsWith(".railway.internal")) "disable" else "require"
                config.jdbcUrl = "jdbc:postgresql://${uri.host}:$puerto${uri.path}?sslmode=$ssl"
                config.username = usuario
                config.password = clave
            }
            config.maximumPoolSize = maxConexiones
            config.poolName = "eyesofter"
            // Por defecto Hikari intenta conectarse al crear el pool y, si la base no responde
            // (Postgres reiniciándose, mantenimiento de Railway, red privada aún sin DNS), lanza
            // una excepción y el servidor se cae al arrancar. Tras unos reintentos Railway deja
            // el servicio en CRASHED. Con -1 el servidor arranca igual, /salud dice "degradado"
            // y las conexiones se abren solas en cuanto la base vuelve.
            config.initializationFailTimeout = -1
            // Cuánto espera una consulta por una conexión antes de fallar (no tumba el proceso).
            config.connectionTimeout = 10_000
            // Revisa las conexiones ociosas para descartar las que la base o la red cerraron.
            config.keepaliveTime = 60_000
            return BaseDeDatos(HikariDataSource(config))
        }
    }
}

// ---------- Ayudantes JDBC ----------

fun Connection.preparar(sql: String, vararg params: Any?): PreparedStatement =
    prepareStatement(sql).apply { params.forEachIndexed { i, p -> poner(i + 1, p) } }

private fun PreparedStatement.poner(i: Int, p: Any?) {
    when (p) {
        null -> setNull(i, Types.NULL)
        is Instant -> setTimestamp(i, Timestamp.from(p))
        is LocalDate -> setObject(i, p)
        is java.util.UUID -> setObject(i, p)
        is ByteArray -> setBytes(i, p)
        is Boolean -> setBoolean(i, p)
        is Int -> setInt(i, p)
        is Long -> setLong(i, p)
        is Double -> setDouble(i, p)
        else -> setString(i, p.toString())
    }
}

fun Connection.ejecutar(sql: String, vararg params: Any?): Int = preparar(sql, *params).use { it.executeUpdate() }

fun <T> Connection.lista(sql: String, vararg params: Any?, fila: (ResultSet) -> T): List<T> =
    preparar(sql, *params).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(fila(rs)) } } }

fun <T> Connection.uno(sql: String, vararg params: Any?, fila: (ResultSet) -> T): T? = lista(sql, *params, fila = fila).firstOrNull()

fun ResultSet.instante(col: String): Instant? = getTimestamp(col)?.toInstant()
fun ResultSet.fecha(col: String): LocalDate? = getObject(col, LocalDate::class.java)
fun ResultSet.entero(col: String): Int? = getInt(col).takeUnless { wasNull() }
fun ResultSet.doble(col: String): Double? = getDouble(col).takeUnless { wasNull() }
