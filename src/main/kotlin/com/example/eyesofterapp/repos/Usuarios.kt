package com.example.eyesofterapp.repos

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.entero
import com.example.eyesofterapp.db.instante
import com.example.eyesofterapp.db.lista
import com.example.eyesofterapp.db.uno
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/** Roles de Identity (los nombres internos del portal web). */
object RolesIdentity {
    const val DIRECCION = "Administrador"
    const val ENFERMERIA = "Enfermeria"
    const val TUTOR = "Tutor"
    const val ALUMNO = "Alumno"
    const val ESPECIALISTA = "Especialista"

    /** "CLIENTE" → "Tutor". También acepta el nombre de Identity tal cual. null si no existe. */
    fun desdeApp(rol: String): String? = when (rol.trim().uppercase()) {
        "ADMINISTRADOR", "DIRECCION", "DIRECCIÓN" -> DIRECCION
        "SUPERVISOR", "ENFERMERIA", "ENFERMERÍA" -> ENFERMERIA
        "CLIENTE", "TUTOR" -> TUTOR
        "DOCTOR", "ESPECIALISTA" -> ESPECIALISTA
        "ALUMNO" -> ALUMNO
        else -> null
    }
}

/**
 * Una cuenta del ecosistema (tabla asp_net_users del portal). [rolApp] es el nombre que
 * espera la app móvil desde antes de unir las bases (contrato docs/API.md §3).
 */
data class Usuario(
    val id: String,
    val email: String,
    val nombre: String,
    val rol: String,
    val tutorId: Int?,
    val alumnoId: Int?,
    val clinicaId: Int?,
    val activo: Boolean,
    /** security_stamp de Identity: cambia al cambiar contraseña o rol; invalida los tokens viejos. */
    val sello: String? = null,
    /** Dirección que administra TODO el sistema (todas las escuelas). */
    val administradorGlobal: Boolean = false,
    /** Escuelas del personal escolar (tabla usuario_planteles del portal). */
    val planteles: List<Int> = emptyList(),
) {
    val esPersonal: Boolean get() = rol == RolesIdentity.DIRECCION || rol == RolesIdentity.ENFERMERIA

    /**
     * Escuelas que puede ver el personal escolar (C-RE-01). null = todas (administrador global).
     * Mismo criterio que Seguridad/AlcanceEscuela.cs del portal: si cambias uno, cambia el otro.
     */
    val alcance: IntArray? get() = if (rol == RolesIdentity.DIRECCION && administradorGlobal) null else planteles.toIntArray()

    /** ¿Este miembro del personal puede ver datos de esa escuela? */
    fun permite(plantelId: Int): Boolean = esPersonal && (alcance?.contains(plantelId) ?: true)
    val rolApp: String get() = when (rol) {
        RolesIdentity.DIRECCION -> "ADMINISTRADOR"
        RolesIdentity.ENFERMERIA -> "SUPERVISOR"
        RolesIdentity.TUTOR -> "CLIENTE"
        RolesIdentity.ESPECIALISTA -> "DOCTOR"
        RolesIdentity.ALUMNO -> "ALUMNO"
        else -> "DESCONOCIDO"
    }
}

class Credenciales(val usuario: Usuario, val passwordHash: String?, val bloqueadaHasta: Instant?, val bloqueoHabilitado: Boolean)

/**
 * Cuentas de Identity. El servidor NO crea cuentas: las crea Dirección en el portal.
 * El bloqueo tras intentos fallidos usa las MISMAS columnas que Identity
 * (access_failed_count, lockout_end): una cuenta bloqueada en la app también lo está en la web.
 */
class UsuarioRepository(private val db: BaseDeDatos) {

    private val select = """
        select u.id, u.email, u.nombre_completo, u.tutor_id, u.alumno_id, u.clinica_id, u.activo, u.security_stamp,
               u.password_hash, u.lockout_end, u.lockout_enabled, u.administrador_global,
               array(select up.plantel_id from usuario_planteles up where up.usuario_id = u.id order by up.plantel_id) as planteles,
               (select r.name from asp_net_user_roles ur join asp_net_roles r on r.id = ur.role_id where ur.user_id = u.id order by r.name limit 1) as rol
        from asp_net_users u
    """.trimIndent()

    private fun usuario(rs: ResultSet) = Usuario(
        id = rs.getString("id"), email = rs.getString("email") ?: "", nombre = rs.getString("nombre_completo"),
        rol = rs.getString("rol") ?: "", tutorId = rs.entero("tutor_id"), alumnoId = rs.entero("alumno_id"),
        clinicaId = rs.entero("clinica_id"), activo = rs.getBoolean("activo"), sello = rs.getString("security_stamp"),
        administradorGlobal = rs.getBoolean("administrador_global"),
        planteles = (rs.getArray("planteles")?.array as? Array<*>)?.map { (it as Number).toInt() }.orEmpty(),
    )

    /** Busca por correo o por nombre de usuario, sin importar mayúsculas (igual que Identity). */
    suspend fun credenciales(login: String): Credenciales? = db.leer { c ->
        val n = login.trim().uppercase()
        c.uno("$select where u.normalized_email = ? or u.normalized_user_name = ?", n, n) { rs ->
            Credenciales(usuario(rs), rs.getString("password_hash"), rs.instante("lockout_end"), rs.getBoolean("lockout_enabled"))
        }
    }

    suspend fun porId(id: String): Usuario? = db.leer { c -> c.uno("$select where u.id = ?", id, fila = ::usuario) }

    suspend fun estaActivo(id: String): Boolean = db.leer { c -> c.uno("select activo from asp_net_users where id = ?", id) { it.getBoolean(1) } ?: false }

    suspend fun conRol(rol: String): List<Usuario> = db.leer { c ->
        c.lista("$select join asp_net_user_roles ur2 on ur2.user_id = u.id join asp_net_roles r2 on r2.id = ur2.role_id where r2.name = ? and u.activo", rol, fila = ::usuario)
    }

    /** Cuentas activas con ese rol que son visibles para esas escuelas (null = todas). */
    suspend fun conRolVisible(rol: String, alcance: IntArray?): List<Usuario> = db.leer { c ->
        c.lista(
            "$select join asp_net_user_roles ur2 on ur2.user_id = u.id join asp_net_roles r2 on r2.id = ur2.role_id " +
                "where r2.name = ? and u.activo and (?::int[] is null or $VISIBLE_PARA)",
            rol, alcance, alcance, alcance, alcance, alcance, alcance, fila = ::usuario
        )
    }

    /** ¿La cuenta es visible para esas escuelas? (null = todas). */
    suspend fun esVisible(id: String, alcance: IntArray?): Boolean = alcance == null || db.leer { c ->
        c.uno("select 1 from asp_net_users u where u.id = ? and $VISIBLE_PARA", id, alcance, alcance, alcance, alcance, alcance) { true } ?: false
    }

    /** Devuelve true si con este fallo la cuenta quedó bloqueada (mismo criterio que Identity). */
    suspend fun registrarFallo(id: String, intentosMax: Int, minutos: Long, ahora: Instant): Boolean = db.transaccion { c ->
        val fallos = c.uno("update asp_net_users set access_failed_count = access_failed_count + 1 where id = ? and lockout_enabled returning access_failed_count", id) { it.getInt(1) } ?: return@transaccion false
        if (fallos >= intentosMax) {
            c.ejecutar("update asp_net_users set lockout_end = ?, access_failed_count = 0 where id = ?", ahora.plusSeconds(minutos * 60), id)
            true
        } else false
    }

    suspend fun registrarExito(id: String) = db.transaccion { c ->
        c.ejecutar("update asp_net_users set access_failed_count = 0, lockout_end = null where id = ? and (access_failed_count <> 0 or lockout_end is not null)", id)
    }

    companion object {
        /**
         * Cuenta visible para un conjunto de escuelas (5 parámetros, el mismo arreglo): personal
         * asignado a ellas, tutores y alumnos de ellas, y especialistas de clínicas con convenio.
         * Mismo criterio que UsuariosController.Visibles() del portal.
         */
        const val VISIBLE_PARA = """(exists (select 1 from usuario_planteles up where up.usuario_id = u.id and up.plantel_id = any(?::int[]))
             or u.tutor_id in (select a.tutor_id from alumnos a where a.plantel_id = any(?::int[]))
             or u.tutor_id in (select t.id from tutores t where t.plantel_id = any(?::int[]))
             or u.alumno_id in (select a.id from alumnos a where a.plantel_id = any(?::int[]))
             or u.clinica_id in (select cv.clinica_id from convenios cv where cv.plantel_id = any(?::int[])))"""

        /** Personal activo con ese rol asignado a ESA escuela (las alertas de un alumno van a su escuela). */
        fun idsConRolEnPlantel(c: Connection, rol: String, plantelId: Int): List<String> =
            c.lista(
                """select u.id from asp_net_users u join asp_net_user_roles ur on ur.user_id = u.id join asp_net_roles r on r.id = ur.role_id
                   join usuario_planteles up on up.usuario_id = u.id where r.name = ? and u.activo and up.plantel_id = ?""",
                rol, plantelId
            ) { it.getString(1) }.distinct()

        /** Personal activo con ese rol en alguna de esas escuelas (null = todas). */
        fun idsConRolEnPlanteles(c: Connection, rol: String, alcance: IntArray?): List<String> =
            if (alcance == null) idsConRol(c, rol) else c.lista(
                """select distinct u.id from asp_net_users u join asp_net_user_roles ur on ur.user_id = u.id join asp_net_roles r on r.id = ur.role_id
                   join usuario_planteles up on up.usuario_id = u.id where r.name = ? and u.activo and up.plantel_id = any(?::int[])""",
                rol, alcance
            ) { it.getString(1) }

        /** Administradores globales; si aún no hay, toda Dirección (como antes). */
        fun idsAdministracionSistema(c: Connection): List<String> =
            c.lista("select id from asp_net_users where administrador_global and activo") { it.getString(1) }
                .ifEmpty { idsConRol(c, RolesIdentity.DIRECCION) }

        fun cuentasDelTutor(c: Connection, tutorId: Int): List<String> =
            c.lista("select id from asp_net_users where tutor_id = ? and activo", tutorId) { it.getString(1) }

        fun idsConRol(c: Connection, rol: String): List<String> =
            c.lista("select u.id from asp_net_users u join asp_net_user_roles ur on ur.user_id = u.id join asp_net_roles r on r.id = ur.role_id where r.name = ? and u.activo", rol) { it.getString(1) }
    }
}

/** Bitácora de auditoría compartida con el portal (C-RC-07, C-RFNN-06). Origen "app" o "escritorio". */
class Auditoria(private val db: BaseDeDatos) {
    suspend fun registrar(usuario: String?, accion: String, entidad: String? = null, entidadId: Any? = null, detalle: String? = null,
                          ip: String? = null, origen: String = "app", exito: Boolean = true, plantelId: Int? = null) =
        db.transaccion { c -> registrarEn(c, usuario, accion, entidad, entidadId, detalle, ip, origen, exito, plantelId) }

    companion object {
        fun registrarEn(c: Connection, usuario: String?, accion: String, entidad: String? = null, entidadId: Any? = null, detalle: String? = null,
                        ip: String? = null, origen: String = "app", exito: Boolean = true, plantelId: Int? = null) {
            // plantel_id: escuela a la que se refiere; sin escuela solo lo ve el administrador global en el portal.
            c.ejecutar(
                "insert into auditoria (fecha, usuario, accion, entidad, entidad_id, detalle, ip, origen, exito, plantel_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Instant.now(), usuario, accion, entidad, entidadId?.toString(), detalle?.take(500), ip, origen, exito, plantelId
            )
        }
    }
}

/** Tokens de Firebase de los teléfonos. */
class DispositivoRepository(private val db: BaseDeDatos) {
    /** Si el teléfono tenía sesión con otra cuenta, el token pasa a la nueva (no recibe avisos ajenos). */
    suspend fun registrar(token: String, usuarioId: String, plataforma: String, ahora: Instant) = db.transaccion { c ->
        c.ejecutar(
            """insert into dispositivos (token, usuario_id, plataforma, registrado_en) values (?, ?, ?, ?)
               on conflict (token) do update set usuario_id = excluded.usuario_id, plataforma = excluded.plataforma, registrado_en = excluded.registrado_en""",
            token, usuarioId, plataforma, ahora
        )
    }

    suspend fun eliminar(token: String, usuarioId: String) = db.transaccion { c -> c.ejecutar("delete from dispositivos where token = ? and usuario_id = ?", token, usuarioId) }
    suspend fun eliminarToken(token: String) = db.transaccion { c -> c.ejecutar("delete from dispositivos where token = ?", token) }
    suspend fun deUsuario(usuarioId: String): List<String> = db.leer { c -> c.lista("select token from dispositivos where usuario_id = ?", usuarioId) { it.getString(1) } }
}
