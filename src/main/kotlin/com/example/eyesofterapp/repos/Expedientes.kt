package com.example.eyesofterapp.repos

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.entero
import com.example.eyesofterapp.db.fecha
import com.example.eyesofterapp.db.instante
import com.example.eyesofterapp.db.lista
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.seguridad.CifradoDatos
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Zona horaria del plantel: las fechas "de calendario" (hoy, año del folio) se toman aquí. */
val ZONA_MX: ZoneId = ZoneId.of("America/Mexico_City")

data class TamizajeFila(
    val id: Int,
    val folio: String,
    val fecha: LocalDate,
    val semaforo: String,
    val observaciones: String?,
    val alumnoId: Int,
    val alumnoNombre: String,
    val grado: Int,
    val grupo: String,
    val tutorId: Int,
    val campana: String?,
    val casoId: Int?,
)

data class PaseFila(
    val id: Int,
    val codigo: String,
    val clinica: Clinica,
    val creadoEn: Instant,
    val expiraEn: Instant,
    val estado: String,
    val usadoEn: Instant?,
)

data class CitaFila(val id: Int, val clinicaNombre: String?, val fechaPreferida: LocalDate, val horario: String?, val comentario: String?, val estado: String, val creadaEn: Instant)
data class EventoFila(val fecha: LocalDate, val tipo: String, val descripcion: String)

data class CasoFila(
    val id: Int,
    val folio: String,
    val estado: String,
    val semaforo: String,
    val clinica: Clinica?,
    val clinicaManual: String?,
    val fechaAtencion: LocalDate?,
    /** El último pase generado (vigente, usado o cancelado). */
    val pase: PaseFila?,
    val citas: List<CitaFila>,
    val eventos: List<EventoFila>,
    val comprobantesAprobados: Int,
)

data class TamizajeDetalle(val tamizaje: TamizajeFila, val caso: CasoFila?)

/** Lo que manda el escritorio, ya validado por la ruta. */
data class TamizajeEntrante(
    val folio: String,
    val matricula: String,
    val fecha: LocalDate,
    val semaforo: String,
    val agudezaOjoDerecho: String?,
    val agudezaOjoIzquierdo: String?,
    val observaciones: String?,
)

sealed interface ResultadoIngesta {
    data class Duplicado(val tamizajeId: Int, val casoId: Int?) : ResultadoIngesta
    data class AlumnoDesconocido(val matricula: String) : ResultadoIngesta
    data class Registrado(val tamizajeId: Int, val casoId: Int?, val casoFolio: String?, val tutoresAvisados: Int) : ResultadoIngesta
}

sealed interface ResultadoDestino {
    data class ConPase(val pase: PaseFila) : ResultadoDestino
    data class SoloResumen(val clinicaNombre: String, val registrada: Boolean) : ResultadoDestino
}

/**
 * El expediente del alumno: tamizajes, casos, pases, citas y línea de tiempo.
 * Aplica las MISMAS reglas que Servicios/Expedientes.cs del portal web: si cambias una aquí,
 * cámbiala también allá.
 */
class ExpedienteRepository(private val db: BaseDeDatos, private val cifrado: CifradoDatos, private val diasVigenciaPase: Long) {

    private fun evento(c: Connection, casoId: Int, tipo: String, descripcion: String, usuario: String?, fecha: LocalDate) =
        evento(c, cifrado, casoId, tipo, descripcion, usuario, fecha)

    private val selectTamizaje = """
        select t.id, t.folio, t.fecha, t.semaforo, t.observaciones, t.alumno_id,
               a.nombre || ' ' || a.apellidos as alumno, a.grado, a.grupo, a.tutor_id, cp.nombre as campana,
               (select cs.id from casos cs where cs.tamizaje_id = t.id order by cs.id desc limit 1) as caso_id
        from tamizajes t join alumnos a on a.id = t.alumno_id left join campanas cp on cp.id = t.campana_id
    """.trimIndent()

    private fun tamizaje(rs: ResultSet) = TamizajeFila(
        id = rs.getInt("id"), folio = rs.getString("folio"), fecha = rs.fecha("fecha")!!, semaforo = rs.getString("semaforo"),
        observaciones = cifrado.descifrar(rs.getString("observaciones")), alumnoId = rs.getInt("alumno_id"),
        alumnoNombre = rs.getString("alumno"), grado = rs.getInt("grado"), grupo = rs.getString("grupo"),
        tutorId = rs.getInt("tutor_id"), campana = rs.getString("campana"), casoId = rs.entero("caso_id"),
    )

    // ------------------------------------------------------------------ consultas

    suspend fun deTutor(tutorId: Int): List<TamizajeFila> = db.leer { c ->
        c.lista("$selectTamizaje where a.tutor_id = ? order by t.fecha desc, t.id desc", tutorId, fila = ::tamizaje)
    }

    suspend fun deAlumno(alumnoId: Int): List<TamizajeFila> = db.leer { c ->
        c.lista("$selectTamizaje where t.alumno_id = ? order by t.fecha desc, t.id desc", alumnoId, fila = ::tamizaje)
    }

    suspend fun recientes(limite: Int = 200): List<TamizajeFila> = db.leer { c ->
        c.lista("$selectTamizaje order by t.fecha desc, t.id desc limit ?", limite, fila = ::tamizaje)
    }

    suspend fun tamizaje(id: Int): TamizajeFila? = db.leer { c -> tamizajeEn(c, id) }

    suspend fun detalle(id: Int): TamizajeDetalle? = db.leer { c ->
        val t = tamizajeEn(c, id) ?: return@leer null
        TamizajeDetalle(t, t.casoId?.let { casoEn(c, it) })
    }

    private fun tamizajeEn(c: Connection, id: Int) = c.uno("$selectTamizaje where t.id = ?", id, fila = ::tamizaje)

    private fun casoEn(c: Connection, casoId: Int): CasoFila? = c.uno(
        """select cs.*, (select count(*) from comprobantes cp where cp.caso_id = cs.id and cp.estado = 'Aprobado') as aprobados
           from casos cs where cs.id = ?""", casoId
    ) { rs ->
        CasoFila(
            id = rs.getInt("id"), folio = rs.getString("folio"), estado = rs.getString("estado"), semaforo = rs.getString("semaforo"),
            clinica = rs.entero("clinica_id")?.let { ClinicaRepository.porId(c, it) }, clinicaManual = rs.getString("clinica_destino_manual"),
            fechaAtencion = rs.fecha("fecha_atencion"), pase = null, citas = emptyList(), eventos = emptyList(),
            comprobantesAprobados = rs.getInt("aprobados"),
        )
    }?.let { caso ->
        caso.copy(
            pase = c.uno("select * from pases where caso_id = ? order by creado_en desc, id desc limit 1", casoId) { pase(c, it) },
            citas = c.lista(
                """select ci.*, cl.nombre as clinica from citas ci left join clinicas cl on cl.id = ci.clinica_id
                   where ci.caso_id = ? order by ci.creada_en desc""", casoId
            ) {
                CitaFila(it.getInt("id"), it.getString("clinica"), it.fecha("fecha_preferida")!!, it.getString("horario"),
                    it.getString("comentario"), it.getString("estado"), it.instante("creada_en")!!)
            },
            eventos = c.lista("select fecha, tipo, descripcion from eventos_caso where caso_id = ? order by fecha, id", casoId) {
                EventoFila(it.fecha("fecha")!!, it.getString("tipo"), cifrado.descifrar(it.getString("descripcion")).orEmpty())
            },
        )
    }

    private fun pase(c: Connection, rs: ResultSet) = PaseFila(
        id = rs.getInt("id"), codigo = rs.getString("codigo"), clinica = ClinicaRepository.porId(c, rs.getInt("clinica_id"))!!,
        creadoEn = rs.instante("creado_en")!!, expiraEn = rs.instante("expira_en")!!, estado = rs.getString("estado"), usadoEn = rs.instante("usado_en"),
    )

    // ------------------------------------------------------------------ escritorio (C-RFN-02)

    /**
     * Guarda un tamizaje que llega del escritorio, en UNA transacción:
     *  - idempotente por folio: si el escritorio reintenta (C-RFNN-03), no se duplica;
     *  - se asigna a la campaña vigente del plantel del alumno;
     *  - amarillo o rojo abre caso (o actualiza el caso activo: un alumno tiene un solo caso abierto);
     *  - deja la notificación al tutor y el evento en la línea de tiempo.
     */
    suspend fun ingestar(e: TamizajeEntrante, ahora: Instant, enviadoPor: String = "escritorio"): ResultadoIngesta = db.transaccion { c ->
        // Dos envíos simultáneos del mismo folio: el segundo espera al primero y lo ve como duplicado.
        c.uno("select pg_advisory_xact_lock(hashtext(?))", "tamizaje:" + e.folio) { }
        c.uno("select t.id, (select cs.id from casos cs where cs.tamizaje_id = t.id limit 1) as caso from tamizajes t where t.folio = ?", e.folio) {
            ResultadoIngesta.Duplicado(it.getInt("id"), it.entero("caso"))
        }?.let { return@transaccion it }

        data class Alumno(val id: Int, val nombre: String, val tutorId: Int, val plantelId: Int)
        val alumno = c.uno("select id, nombre, apellidos, tutor_id, plantel_id from alumnos where upper(matricula) = upper(?)", e.matricula) {
            Alumno(it.getInt("id"), it.getString("nombre") + " " + it.getString("apellidos"), it.getInt("tutor_id"), it.getInt("plantel_id"))
        } ?: return@transaccion ResultadoIngesta.AlumnoDesconocido(e.matricula)

        val campanaId = c.uno(
            """select id from campanas where plantel_id = ? and fecha_inicio <= ? and (fecha_fin is null or fecha_fin >= ?)
               order by fecha_inicio desc limit 1""", alumno.plantelId, e.fecha, e.fecha
        ) { it.getInt(1) }

        val tamizajeId = c.uno(
            """insert into tamizajes (folio, alumno_id, campana_id, fecha, semaforo, agudeza_ojo_derecho, agudeza_ojo_izquierdo, observaciones, origen, recibido_en)
               values (?, ?, ?, ?, ?, ?, ?, ?, 'escritorio', ?) returning id""",
            e.folio, alumno.id, campanaId, e.fecha, e.semaforo, cifrado.cifrar(e.agudezaOjoDerecho), cifrado.cifrar(e.agudezaOjoIzquierdo),
            cifrado.cifrar(e.observaciones), ahora
        ) { it.getInt(1) }!!

        val descripcion = "Tamizaje Snellen (escritorio, folio ${e.folio}): OD ${e.agudezaOjoDerecho ?: "—"}, OI ${e.agudezaOjoIzquierdo ?: "—"}. Semáforo ${e.semaforo}."
        val activo = c.uno("select id, folio, semaforo from casos where alumno_id = ? and estado <> 'Cerrado' order by id desc limit 1", alumno.id) {
            Triple(it.getInt("id"), it.getString("folio"), it.getString("semaforo"))
        }
        var casoId: Int? = null
        var casoFolio: String? = null
        if (activo != null) {
            // Ya hay un caso abierto: el nuevo tamizaje se suma a su línea de tiempo.
            casoId = activo.first; casoFolio = activo.second
            evento(c, activo.first, TipoEvento.TAMIZAJE, descripcion, "escritorio", e.fecha)
            if (e.semaforo != "Verde") {
                c.ejecutar(
                    "update casos set tamizaje_id = ?, semaforo = ?, agudeza_ojo_derecho = ?, agudeza_ojo_izquierdo = ? where id = ?",
                    tamizajeId, e.semaforo, cifrado.cifrar(e.agudezaOjoDerecho ?: "—"), cifrado.cifrar(e.agudezaOjoIzquierdo ?: "—"), activo.first
                )
            }
        } else if (e.semaforo != "Verde") {
            casoFolio = nuevoFolioCaso(c, e.fecha.year)
            casoId = c.uno(
                """insert into casos (folio, alumno_id, semaforo, estado, fecha_deteccion, agudeza_ojo_derecho, agudeza_ojo_izquierdo, observaciones, registrado_por, tamizaje_id)
                   values (?, ?, ?, 'Abierto', ?, ?, ?, ?, 'escritorio', ?) returning id""",
                casoFolio, alumno.id, e.semaforo, e.fecha, cifrado.cifrar(e.agudezaOjoDerecho ?: "—"), cifrado.cifrar(e.agudezaOjoIzquierdo ?: "—"),
                cifrado.cifrar(e.observaciones), tamizajeId
            ) { it.getInt(1) }!!
            evento(c, casoId, TipoEvento.TAMIZAJE, descripcion, "escritorio", e.fecha)
        }

        // W-RFN-02 / M-RFN-02: aviso al tutor. Sin el color del semáforo: la push pasa por Google/Apple.
        val tutores = UsuarioRepository.cuentasDelTutor(c, alumno.tutorId)
        val datos = buildMap {
            put("ruta", "tamizaje/$tamizajeId"); put("tamizajeId", "$tamizajeId"); put("alumno", alumno.nombre)
            casoId?.let { put("casoId", "$it") }
        }
        tutores.forEach {
            NotificacionRepository.crearEn(c, it, TipoNotificacion.RESULTADO_TAMIZAJE, "Resultado del tamizaje visual",
                "Ya está disponible el resultado del tamizaje visual de ${alumno.nombre}. Tócala para verlo.", datos, ahora)
        }
        if (casoId != null) {
            evento(c, casoId, TipoEvento.AVISO_TUTOR,
                if (tutores.isNotEmpty()) "Se notificó el resultado al tutor por la app y el portal." else "El tutor no tiene cuenta: avísale por teléfono.",
                "sistema", hoy(ahora))
        }
        if (tutores.isEmpty() && e.semaforo != "Verde") {
            UsuarioRepository.idsConRol(c, RolesIdentity.ENFERMERIA).forEach {
                NotificacionRepository.crearEn(c, it, TipoNotificacion.ALERTA_ENFERMERIA, "Tutor sin cuenta",
                    "${alumno.nombre} tiene un resultado que requiere seguimiento y su tutor no tiene cuenta. Avísale por teléfono.",
                    mapOf("ruta" to "seguimiento", "tamizajeId" to "$tamizajeId"), ahora)
            }
        }
        Auditoria.registrarEn(c, enviadoPor, "RECIBIR_TAMIZAJE", "Tamizaje", tamizajeId, e.folio, origen = "escritorio")
        ResultadoIngesta.Registrado(tamizajeId, casoId, casoFolio, tutores.size)
    }

    // ------------------------------------------------------------------ destino y pase (M-RFN-09..11, C-RFN-04..06)

    /**
     * Guarda a dónde acudirá el alumno. Clínica registrada, asociada y activa → pase con QR.
     * Cualquier otro caso (no asociada o no registrada) → solo el resumen (C-RFNN-07).
     * Un pase anterior sin usar se cancela: solo puede haber uno vigente.
     */
    suspend fun asignarDestino(casoId: Int, clinicaId: Int?, nombreManual: String?, usuario: String, ahora: Instant): ResultadoDestino = db.transaccion { c ->
        val clinica = clinicaId?.let { ClinicaRepository.porId(c, it) }
        val manual = if (clinica == null) nombreManual?.trim()?.take(160)?.ifBlank { null } else null
        c.ejecutar(
            "update casos set clinica_id = ?, clinica_destino_manual = ?, estado = case when estado = 'Abierto' then 'EnSeguimiento' else estado end where id = ?",
            clinica?.id, manual, casoId
        )
        c.ejecutar("update pases set estado = 'Cancelado' where caso_id = ? and estado = 'Vigente'", casoId)

        val nombre = clinica?.nombre ?: manual ?: "sin especificar"
        val resultado = if (clinica != null && clinica.asociada && clinica.activa) {
            val id = c.uno(
                "insert into pases (caso_id, clinica_id, codigo, creado_en, expira_en, estado) values (?, ?, ?, ?, ?, 'Vigente') returning id",
                casoId, clinica.id, nuevoCodigoPase(), ahora, ahora.plusSeconds(diasVigenciaPase * 86_400)
            ) { it.getInt(1) }!!
            evento(c, casoId, TipoEvento.DESTINO, "Destino: $nombre (asociada). Se generó el pase médico con QR.", usuario, hoy(ahora))
            ResultadoDestino.ConPase(c.uno("select * from pases where id = ?", id) { pase(c, it) }!!)
        } else {
            evento(c, casoId, TipoEvento.DESTINO,
                "Destino: $nombre (no asociada${if (clinica == null) ", no registrada" else ""}). Se entrega el resumen del tamizaje, sin QR.",
                usuario, hoy(ahora))
            ResultadoDestino.SoloResumen(nombre, registrada = clinica != null)
        }
        Auditoria.registrarEn(c, usuario, "ASIGNAR_DESTINO", "Caso", casoId, nombre)
        resultado
    }

    /** M-RFN-08: el tutor pide cita; Enfermería y el especialista de esa clínica reciben el aviso. */
    suspend fun solicitarCita(casoId: Int, clinica: Clinica, fecha: LocalDate, horario: String?, comentario: String?,
                              usuarioId: String, usuario: String, alumno: String, ahora: Instant): Int = db.transaccion { c ->
        val id = c.uno(
            """insert into citas (caso_id, clinica_id, fecha_preferida, horario, comentario, estado, solicitada_por_id, creada_en)
               values (?, ?, ?, ?, ?, 'Solicitada', ?, ?) returning id""",
            casoId, clinica.id, fecha, horario?.trim()?.take(20)?.ifBlank { null }, comentario?.trim()?.take(300)?.ifBlank { null }, usuarioId, ahora
        ) { it.getInt(1) }!!
        evento(c, casoId, TipoEvento.CITA, "El tutor solicitó cita en ${clinica.nombre} para el $fecha (desde la app).", usuario, hoy(ahora))
        val datos = mapOf("ruta" to "seguimiento", "casoId" to "$casoId", "citaId" to "$id")
        val destinatarios = UsuarioRepository.idsConRol(c, RolesIdentity.ENFERMERIA) +
            c.lista("select id from asp_net_users where clinica_id = ? and activo", clinica.id) { it.getString(1) }
        destinatarios.distinct().forEach {
            NotificacionRepository.crearEn(c, it, TipoNotificacion.CITA, "Nueva solicitud de cita",
                "Cita solicitada para $alumno en ${clinica.nombre}, fecha preferida $fecha.", datos, ahora)
        }
        Auditoria.registrarEn(c, usuario, "SOLICITAR_CITA", "Caso", casoId, clinica.nombre)
        id
    }

    /**
     * C-RFN-08 (segunda parte): casos con amarillo o rojo que siguen sin atención ni comprobante
     * aprobado [dias] días después de detectarse, y a los que no se les recordó en los últimos [dias] días.
     */
    data class CasoPendiente(val casoId: Int, val tutorId: Int, val alumno: String, val tamizajeId: Int?)

    suspend fun casosSinAtender(dias: Long, maxRecordatorios: Int, ahora: Instant): List<CasoPendiente> = db.leer { c ->
        val limite = ahora.minusSeconds(dias * 86_400)
        c.lista(
            """select cs.id, cs.tamizaje_id, a.nombre || ' ' || a.apellidos as alumno, a.tutor_id
               from casos cs join alumnos a on a.id = cs.alumno_id
               where cs.estado <> 'Cerrado' and cs.fecha_deteccion <= ?
                 and not exists (select 1 from atenciones at where at.caso_id = cs.id)
                 and not exists (select 1 from comprobantes cp where cp.caso_id = cs.id and cp.estado = 'Aprobado')
                 and (select count(distinct n.creada_en) from notificaciones n where n.tipo = 'RECORDATORIO_CASO' and n.datos->>'casoId' = cs.id::text) < ?
                 and not exists (select 1 from notificaciones n where n.tipo = 'RECORDATORIO_CASO' and n.datos->>'casoId' = cs.id::text and n.creada_en > ?)""",
            limite.atZone(ZONA_MX).toLocalDate(), maxRecordatorios, limite
        ) { CasoPendiente(it.getInt("id"), it.getInt("tutor_id"), it.getString("alumno"), it.entero("tamizaje_id")) }
    }

    suspend fun recordarCaso(p: CasoPendiente, ahora: Instant): Int = db.transaccion { c ->
        val (casoId, tutorId, alumno, tamizajeId) = p
        val cuentas = UsuarioRepository.cuentasDelTutor(c, tutorId)
        val datos = buildMap {
            put("casoId", "$casoId"); put("alumno", alumno)
            tamizajeId?.let { put("ruta", "tamizaje/$it"); put("tamizajeId", "$it") }
        }
        cuentas.forEach {
            NotificacionRepository.crearEn(c, it, TipoNotificacion.RECORDATORIO_CASO, "Seguimiento pendiente",
                "La revisión de la vista de $alumno sigue pendiente. Elige una clínica en la app o sube la receta cuando la tengas.", datos, ahora)
        }
        if (cuentas.isNotEmpty()) evento(c, casoId, TipoEvento.SEGUIMIENTO, "Se envió un recordatorio de seguimiento al tutor.", "sistema", hoy(ahora))
        cuentas.size
    }

    // ------------------------------------------------------------------ escritorio: consultas de apoyo

    data class AlumnoEscritorio(val matricula: String, val nombre: String, val apellidos: String, val nivel: String, val grado: Int, val grupo: String)

    /**
     * Para que el escritorio valide la matrícula antes de capturar el tamizaje. Es una
     * BÚSQUEDA, no un listado: pide al menos 3 letras y devuelve pocos resultados, para
     * que un equipo no pueda descargar el padrón completo de alumnos.
     */
    suspend fun alumnos(texto: String, limite: Int = 20): List<AlumnoEscritorio> = db.leer { c ->
        val q = "%" + texto.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        c.lista(
            "select * from alumnos where matricula ilike ? or (nombre || ' ' || apellidos) ilike ? order by grado, grupo, apellidos limit ?", q, q, limite
        ) {
            AlumnoEscritorio(it.getString("matricula"), it.getString("nombre"), it.getString("apellidos"), it.getString("nivel"),
                it.getInt("grado"), it.getString("grupo"))
        }
    }

    companion object {
        private const val ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun hoy(ahora: Instant): LocalDate = ahora.atZone(ZONA_MX).toLocalDate()

        /** Línea de tiempo del caso. La descripción va cifrada, como en el portal (C-RC-04). */
        fun evento(c: Connection, cifrado: CifradoDatos, casoId: Int, tipo: String, descripcion: String, usuario: String?, fecha: LocalDate) {
            c.ejecutar(
                "insert into eventos_caso (caso_id, tipo, fecha, descripcion, registrado_por) values (?, ?, ?, ?, ?)",
                casoId, tipo, fecha, cifrado.cifrar(descripcion.take(500)), usuario?.take(256)
            )
        }

        /** Igual que Expedientes.GenerarFolioAsync del portal: EYS-AAAA-NNNN. */
        fun nuevoFolioCaso(c: Connection, anio: Int): String {
            c.uno("select pg_advisory_xact_lock(hashtext('folio-caso'))") { }
            val prefijo = "EYS-$anio-"
            val siguiente = c.lista("select folio from casos where folio like ?", "$prefijo%") { it.getString(1) }
                .maxOfOrNull { it.removePrefix(prefijo).toIntOrNull() ?: 0 }?.plus(1) ?: 1
            return prefijo + siguiente.toString().padStart(4, '0')
        }

        /** 26 caracteres base32 sin letras confundibles (128 bits de azar), igual que el portal. */
        fun nuevoCodigoPase(azar: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(16).also { azar.nextBytes(it) }
            return buildString {
                for (i in 0 until 26) {
                    val bit = i * 5
                    val alto = bytes[bit / 8].toInt() and 0xFF
                    val bajo = if (bit / 8 + 1 < bytes.size) bytes[bit / 8 + 1].toInt() and 0xFF else 0
                    append(ALFABETO[((alto shl 8) or bajo) shr (11 - bit % 8) and 31])
                }
            }
        }

        /** "ABCD EFGH ..." para leerlo o dictarlo por teléfono. */
        fun codigoLegible(codigo: String) = codigo.chunked(4).joinToString(" ")
    }
}
