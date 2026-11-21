package com.example.eyesofterapp.db

import com.example.eyesofterapp.repos.RolesIdentity
import com.example.eyesofterapp.seguridad.PasswordIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Deja la base lista para usarse aunque el portal web todavía no se haya desplegado.
 *
 *  1. Tablas: corre esquema.sql, que es el script IDEMPOTENTE de las migraciones del portal
 *     (EF Core). Cada bloque revisa "__EFMigrationsHistory": si el portal ya creó las tablas no
 *     hace nada, y si las crea este servidor queda anotada la migración, así que el portal
 *     después no intenta crearlas otra vez.
 *  2. Roles de Identity (Administrador, Enfermeria, ...) si faltan.
 *  3. Cuenta de administrador inicial si se definieron ADMIN_EMAIL y ADMIN_PASSWORD y esa
 *     cuenta no existe. Sin cuentas nadie puede iniciar sesión en la app.
 *
 * Si la base aún no responde, se reintenta cada pocos segundos sin tumbar el servidor.
 */
class PreparacionBase(
    private val db: BaseDeDatos,
    private val crearEsquema: Boolean,
    private val adminEmail: String?,
    private val adminPassword: String?,
    private val adminNombre: String = "Administrador",
) {
    private val log = LoggerFactory.getLogger(PreparacionBase::class.java)

    /** Hace todo una vez. Lanza la excepción si la base no responde. */
    suspend fun preparar() {
        if (crearEsquema) {
            val existia = db.leer { c -> tablaExiste(c, "asp_net_users") }
            val sql = PreparacionBase::class.java.getResource("/esquema.sql")!!.readText().removePrefix("﻿")
            // El script trae su propio START TRANSACTION/COMMIT: va en autocommit, fuera de transaccion { }.
            withContext(Dispatchers.IO) {
                db.fuente.connection.use { c -> c.autoCommit = true; c.createStatement().use { it.execute(sql) } }
            }
            if (!existia) log.info("La base estaba vacía: se crearon las tablas del portal (esquema.sql)")
        }
        db.transaccion { c ->
            listOf(RolesIdentity.DIRECCION, RolesIdentity.ENFERMERIA, RolesIdentity.TUTOR, RolesIdentity.ALUMNO, RolesIdentity.ESPECIALISTA)
                .forEach { rol ->
                    val existe = c.uno("select 1 from asp_net_roles where normalized_name = ?", rol.uppercase()) { true } ?: false
                    if (!existe) {
                        c.ejecutar(
                            "insert into asp_net_roles (id, name, normalized_name, concurrency_stamp) values (?, ?, ?, ?)",
                            UUID.randomUUID().toString(), rol, rol.uppercase(), UUID.randomUUID().toString(),
                        )
                        log.info("Se creó el rol $rol")
                    }
                }
        }
        crearAdministrador()
    }

    private suspend fun crearAdministrador() {
        val email = adminEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null || adminPassword.isNullOrEmpty()) {
            val hayCuentas = db.leer { c -> c.uno("select count(*) from asp_net_users") { it.getInt(1) } ?: 0 } > 0
            if (!hayCuentas) log.warn("No hay ninguna cuenta en la base: define ADMIN_EMAIL y ADMIN_PASSWORD para crear la primera.")
            return
        }
        db.transaccion { c ->
            val existe = c.uno("select 1 from asp_net_users where normalized_email = ? or normalized_user_name = ?", email.uppercase(), email.uppercase()) { true } ?: false
            if (existe) return@transaccion
            val id = UUID.randomUUID().toString()
            c.ejecutar(
                """insert into asp_net_users (id, nombre_completo, activo, user_name, normalized_user_name, email, normalized_email,
                     email_confirmed, password_hash, security_stamp, concurrency_stamp, phone_number_confirmed, two_factor_enabled,
                     lockout_enabled, access_failed_count)
                   values (?, ?, true, ?, ?, ?, ?, true, ?, ?, ?, false, false, true, 0)""",
                id, adminNombre, email, email.uppercase(), email, email.uppercase(),
                PasswordIdentity.hash(adminPassword), UUID.randomUUID().toString().replace("-", "").uppercase(), UUID.randomUUID().toString(),
            )
            val rolId = c.uno("select id from asp_net_roles where normalized_name = ?", RolesIdentity.DIRECCION.uppercase()) { it.getString(1) }!!
            c.ejecutar("insert into asp_net_user_roles (user_id, role_id) values (?, ?)", id, rolId)
            log.info("Se creó la cuenta de administrador inicial $email")
        }
    }

    /** Reintenta [preparar] hasta que funcione (la base puede tardar en arrancar). */
    fun iniciar(scope: CoroutineScope, reintentoSegundos: Long = 10): Job = scope.launch {
        while (true) {
            try {
                preparar()
                log.info("Base de datos lista")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("No se pudo preparar la base de datos; se reintenta en $reintentoSegundos s", e)
            }
            delay(reintentoSegundos * 1000)
        }
    }

    private fun tablaExiste(c: java.sql.Connection, nombre: String): Boolean =
        c.uno("select to_regclass(?) is not null", "public.$nombre") { it.getBoolean(1) } ?: false
}
