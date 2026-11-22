package com.example.eyesofterapp.repos

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.entero
import com.example.eyesofterapp.db.instante
import com.example.eyesofterapp.db.lista
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.seguridad.CifradoDatos
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class ComprobanteFila(
    val id: UUID,
    val casoId: Int,
    val tamizajeId: Int?,
    val tutorId: Int,
    val alumno: String,
    /** Escuela del alumno (C-RE-01). */
    val plantelId: Int = 1,
    val subidoPor: String,
    val tipo: String,
    val contentType: String,
    val tamanoBytes: Int,
    val subidoEn: Instant,
    val estado: String,
    val revisadoEn: Instant?,
    val motivoRechazo: String?,
)

/**
 * Recetas y comprobantes de lentes (M-RFN-05). El archivo se guarda CIFRADO en la base
 * (C-RC-04), igual que cuando el tutor lo sube desde el portal.
 */
class ComprobanteRepository(private val db: BaseDeDatos, private val cifrado: CifradoDatos) {

    private val select = """
        select cp.id, cp.caso_id, cs.tamizaje_id, a.tutor_id, a.plantel_id, a.nombre || ' ' || a.apellidos as alumno,
               coalesce(u.email, cp.subido_por_id) as subido_por, cp.tipo, cp.content_type, cp.tamano_bytes,
               cp.subido_en, cp.estado, cp.revisado_en, cp.motivo_rechazo
        from comprobantes cp join casos cs on cs.id = cp.caso_id join alumnos a on a.id = cs.alumno_id
        left join asp_net_users u on u.id = cp.subido_por_id
    """.trimIndent()

    private fun fila(rs: ResultSet) = ComprobanteFila(
        id = rs.getObject("id", UUID::class.java), casoId = rs.getInt("caso_id"), tamizajeId = rs.entero("tamizaje_id"),
        tutorId = rs.getInt("tutor_id"), alumno = rs.getString("alumno"), subidoPor = rs.getString("subido_por"),
        tipo = rs.getString("tipo"), contentType = rs.getString("content_type"), tamanoBytes = rs.getInt("tamano_bytes"),
        subidoEn = rs.instante("subido_en")!!, estado = rs.getString("estado"), revisadoEn = rs.instante("revisado_en"),
        motivoRechazo = rs.getString("motivo_rechazo"), plantelId = rs.getInt("plantel_id"),
    )

    suspend fun guardar(casoId: Int, alumno: String, usuarioId: String, usuario: String, tipo: String, contentType: String,
                        bytes: ByteArray, ahora: Instant): ComprobanteFila = db.transaccion { c ->
        val id = UUID.randomUUID()
        c.ejecutar(
            """insert into comprobantes (id, caso_id, subido_por_id, tipo, content_type, tamano_bytes, contenido, subido_en, estado)
               values (?, ?, ?, ?, ?, ?, ?, ?, 'Pendiente')""",
            id, casoId, usuarioId, tipo, contentType, bytes.size, cifrado.cifrarArchivo(bytes), ahora
        )
        val nombre = if (tipo == "Receta") "la receta" else "el comprobante de lentes"
        ExpedienteRepository.evento(c, cifrado, casoId, TipoEvento.COMPROBANTE, "El tutor subió $nombre desde la app.", usuario, ExpedienteRepository.hoy(ahora))
        val plantel = c.uno("select a.plantel_id from casos cs join alumnos a on a.id = cs.alumno_id where cs.id = ?", casoId) { it.getInt(1) }
        UsuarioRepository.idsAlertaEscuela(c, plantel).forEach {
            NotificacionRepository.crearEn(c, it, TipoNotificacion.COMPROBANTE_RECIBIDO, "Nuevo comprobante por revisar",
                "El tutor de $alumno subió ${if (tipo == "Receta") "una receta" else "un comprobante de lentes"}.",
                mapOf("ruta" to "comprobantes", "comprobanteId" to "$id", "casoId" to "$casoId"), ahora)
        }
        Auditoria.registrarEn(c, usuario, "SUBIR_COMPROBANTE", "Caso", casoId, "$id", plantelId = plantel)
        c.uno("$select where cp.id = ?", id, fila = ::fila)!!
    }

    suspend fun buscar(id: UUID): ComprobanteFila? = db.leer { c -> c.uno("$select where cp.id = ?", id, fila = ::fila) }

    /** tutorId = null → todos (personal escolar). */
    /** [alcance]: escuelas del personal (null = todas). Para el tutor se filtra por [tutorId]. */
    suspend fun listar(tutorId: Int?, estado: String?, alcance: IntArray? = null): List<ComprobanteFila> = db.leer { c ->
        c.lista(
            """$select where (?::int is null or a.tutor_id = ?) and (?::text is null or cp.estado = ?)
               and (?::int[] is null or a.plantel_id = any(?::int[])) order by cp.subido_en desc limit 300""",
            tutorId, tutorId, estado, estado, alcance, alcance, fila = ::fila
        )
    }

    suspend fun archivo(id: UUID): ByteArray? = db.leer { c ->
        c.uno("select contenido from comprobantes where id = ?", id) { it.getBytes(1) }?.let(cifrado::descifrarArchivo)
    }

    /** null si no existe o ya estaba revisado (no se revisa dos veces). */
    suspend fun revisar(id: UUID, aprobado: Boolean, revisor: String, motivo: String?, ahora: Instant): ComprobanteFila? = db.transaccion { c ->
        val cambiadas = c.ejecutar(
            "update comprobantes set estado = ?, revisado_por = ?, revisado_en = ?, motivo_rechazo = ? where id = ? and estado = 'Pendiente'",
            if (aprobado) EstadosComprobante.APROBADO else EstadosComprobante.RECHAZADO, revisor.take(256), ahora,
            if (aprobado) null else motivo?.take(300), id
        )
        if (cambiadas == 0) return@transaccion null
        val f = c.uno("$select where cp.id = ?", id, fila = ::fila)!!
        val nombre = if (f.tipo == "Receta") "Receta" else "Comprobante de lentes"
        ExpedienteRepository.evento(c, cifrado, f.casoId, TipoEvento.COMPROBANTE,
            if (aprobado) "$nombre validada por Enfermería." else "$nombre rechazada: ${f.motivoRechazo}. Se pidió un nuevo envío.",
            revisor, ExpedienteRepository.hoy(ahora))
        val datos = buildMap {
            put("comprobanteId", "$id"); put("casoId", "${f.casoId}")
            f.tamizajeId?.let { put("ruta", "tamizaje/$it"); put("tamizajeId", "$it") }
        }
        UsuarioRepository.cuentasDelTutor(c, f.tutorId).forEach {
            NotificacionRepository.crearEn(c, it, TipoNotificacion.COMPROBANTE_REVISADO,
                if (aprobado) "Comprobante aceptado" else "Comprobante rechazado",
                if (aprobado) "Enfermería aceptó el comprobante de ${f.alumno}. ¡Gracias!"
                else "Enfermería no pudo aceptar el comprobante de ${f.alumno}: ${f.motivoRechazo}. Sube uno nuevo.",
                datos, ahora)
        }
        Auditoria.registrarEn(c, revisor, if (aprobado) "APROBAR_COMPROBANTE" else "RECHAZAR_COMPROBANTE", "Comprobante", id)
        f
    }
}

/** Aviso de privacidad aceptado (C-RC-08) y reportes de fallas o dudas (C-RS-04). */
class ConsentimientoRepository(private val db: BaseDeDatos) {
    suspend fun aceptado(usuarioId: String, version: String): Instant? = db.leer { c ->
        c.uno("select max(aceptado_en) from consentimientos where usuario_id = ? and version_aviso = ?", usuarioId, version) { it.instante("max") }
    }

    suspend fun aceptar(usuarioId: String, version: String, ip: String?, ahora: Instant) = db.transaccion { c ->
        c.ejecutar("insert into consentimientos (usuario_id, version_aviso, aceptado_en, medio, ip) values (?, ?, ?, 'app', ?)", usuarioId, version, ahora, ip?.take(45))
        Auditoria.registrarEn(c, usuarioId, "ACEPTAR_AVISO_PRIVACIDAD", "Consentimiento", null, version, ip)
    }

    suspend fun reportar(usuarioId: String?, correo: String?, tipo: String, mensaje: String, plataforma: String, ahora: Instant): Int = db.transaccion { c ->
        val id = c.uno(
            "insert into reportes_falla (usuario_id, correo, tipo, mensaje, plataforma, creado_en, estado) values (?, ?, ?, ?, ?, ?, 'Nuevo') returning id",
            usuarioId, correo?.take(256), tipo, mensaje.take(1000), plataforma, ahora
        ) { it.getInt(1) }!!
        // Los reportes de fallas los atiende el equipo que administra el sistema.
        UsuarioRepository.idsAdministracionSistema(c).forEach {
            NotificacionRepository.crearEn(c, it, TipoNotificacion.AVISO, "Nuevo reporte de ${tipo.lowercase()}",
                "Llegó un reporte desde $plataforma: ${mensaje.take(120)}", mapOf("ruta" to "reportes", "reporteId" to "$id"), ahora)
        }
        id
    }
}
