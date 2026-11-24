package com.example.eyesofterapp.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// CONTRATO con el sistema de escritorio (entrada) y con la app (salida).
// Ver docs/API.md §8-§12. Fechas de calendario AAAA-MM-DD; instantes ISO-8601 UTC.
// ---------------------------------------------------------------------------

/** Lo que manda el sistema de escritorio en POST /api/escritorio/tamizajes (C-RFN-02). */
@Serializable
data class TamizajeEntradaDto(
    /** Identificador del tamizaje en el escritorio. Si se reenvía, no se duplica (reintentos, C-RFNN-03). */
    val folio: String,
    /** Matrícula del alumno, tal como está en el portal. El tutor se obtiene de ahí. */
    val matricula: String,
    /** Fecha del tamizaje, AAAA-MM-DD. */
    val fecha: String,
    /** VERDE, AMARILLO o ROJO. */
    val semaforo: String,
    /** Agudeza Snellen, p. ej. "20/40". */
    val agudezaOjoDerecho: String? = null,
    val agudezaOjoIzquierdo: String? = null,
    val observaciones: String? = null,
)

@Serializable
data class TamizajeRecibidoDto(
    val tamizajeId: String,
    /** Caso abierto o actualizado (amarillo/rojo). null = verde sin caso. */
    val casoId: Int? = null,
    val casoFolio: String? = null,
    /** Cuántas cuentas del tutor recibieron el aviso. 0 = el tutor no tiene cuenta. */
    val tutoresAvisados: Int = 0,
    /** true = ese folio ya se había recibido; no se volvió a notificar. */
    val duplicado: Boolean,
)

/** POST /api/escritorio/incidencias: el escritorio avisa que no pudo sincronizar (C-RFNN-03). */
@Serializable
data class IncidenciaEscritorioDto(
    val mensaje: String,
    /** Cuántos tamizajes siguen sin enviarse. */
    val pendientes: Int = 0,
    /** Desde cuándo falla (ISO-8601), si lo sabe. */
    val desde: String? = null,
    val equipo: String? = null,
)

/**
 * GET /api/escritorio/alumnos?q= y /api/escritorio/grupo: para que el escritorio valide la
 * matrícula y llene el registro (alumno y tutor) sin volver a capturarlo. Los campos nuevos
 * son opcionales para que los escritorios anteriores sigan funcionando.
 */
@Serializable
data class AlumnoEscritorioDto(
    val matricula: String,
    val nombre: String,
    val apellidos: String,
    val nivel: String,
    val grado: Int,
    val grupo: String,
    /** AAAA-MM-DD. */
    val fechaNacimiento: String? = null,
    val usaLentes: Boolean = false,
    val tutorNombre: String? = null,
    val tutorTelefono: String? = null,
    val tutorCorreo: String? = null,
    val tutorParentesco: String? = null,
    /** Fecha (AAAA-MM-DD) y semáforo del último tamizaje registrado en la Suite. */
    val ultimoTamizaje: String? = null,
    val ultimoSemaforo: String? = null,
    /** Estado del caso abierto (Abierto / EnSeguimiento): aún no lo atiende un especialista. */
    val casoAbierto: String? = null,
)

/** GET /api/escritorio/grupos: grupos de la escuela y avance de la jornada. */
@Serializable
data class GrupoEscritorioDto(val nivel: String, val grado: Int, val grupo: String, val alumnos: Int, val evaluados: Int)

/** POST /api/escritorio/vinculaciones: el escritorio pide vincularse. */
@Serializable
data class VinculacionEntradaDto(val equipo: String = "")

@Serializable
data class VinculacionCreadaDto(
    /** Secreto: solo lo conoce el escritorio y lo usa para preguntar si ya se aprobó. */
    val solicitud: String,
    /** Código que se muestra en el escritorio y en la página, p. ej. "K7QM-3RTA". */
    val codigo: String,
    /** Ruta de la página para aprobar, relativa a la URL del servidor. */
    val ruta: String,
    val expiraEnSegundos: Long,
    val intervaloSegundos: Int = 3,
)

/** POST /api/escritorio/vinculaciones/estado */
@Serializable
data class VinculacionEstadoEntradaDto(val solicitud: String = "")

@Serializable
data class VinculacionEstadoDto(
    /** PENDIENTE o APROBADA. */
    val estado: String,
    /** Solo cuando estado = APROBADA, y solo esa vez. */
    val token: String? = null,
    val usuario: String? = null,
    val nombre: String? = null,
    val equipo: String? = null,
)

/** GET /api/escritorio/equipo: ¿sigue vinculado este equipo? ¿quién lo autorizó? */
@Serializable
data class EquipoEscritorioDto(val equipo: String, val usuario: String, val nombre: String, val rol: String)

/** Lo que ve el tutor (C-RFN-06: fecha, semáforo y observaciones, sin diagnóstico). */
@Serializable
data class TamizajeDto(
    val id: String,
    val folio: String,
    val alumnoId: Int = 0,
    val alumnoNombre: String,
    /** "3" (grado escolar). */
    val grado: String,
    val grupo: String,
    val fecha: String,
    /** VERDE, AMARILLO o ROJO. */
    val semaforo: String,
    /** Texto que acompaña al color (C-RC-10: el semáforo no depende solo del color). */
    val semaforoTexto: String,
    val observaciones: String,
    val campana: String? = null,
    /** Seguimiento del caso (solo amarillo o rojo). En la lista viene sin eventos ni citas. */
    val caso: CasoDto? = null,
)

@Serializable
data class CasoDto(
    val id: Int,
    val folio: String,
    /** ABIERTO, EN_SEGUIMIENTO o CERRADO. */
    val estado: String,
    /** true = ya se eligió a dónde acudirá el alumno. */
    val destinoDefinido: Boolean,
    val clinicaId: Int? = null,
    val clinicaNombre: String? = null,
    /** true = la clínica de destino es asociada y activa: hay pase con QR (M-RFN-10). false = solo resumen (M-RFN-11). */
    val clinicaAsociada: Boolean = false,
    val pase: PaseDto? = null,
    val fechaAtencion: String? = null,
    val comprobanteAprobado: Boolean = false,
    val citas: List<CitaDto> = emptyList(),
    /** Línea de tiempo del caso, de la más antigua a la más reciente. */
    val eventos: List<EventoDto> = emptyList(),
)

@Serializable
data class PaseDto(
    /** 26 caracteres; lo que codifica el QR dentro de urlValidacion. */
    val codigo: String,
    /** El mismo código en grupos de 4, para dictarlo. */
    val codigoLegible: String,
    /** VIGENTE, USADO, CANCELADO o EXPIRADO. */
    val estado: String,
    val creadoEn: String,
    val expiraEn: String,
    val usadoEn: String? = null,
    val clinicaId: Int,
    val clinicaNombre: String,
    val clinicaDireccion: String,
    val clinicaTelefono: String,
    /** Lo que contiene el QR: la página del portal donde el especialista lo valida. */
    val urlValidacion: String,
    /** Beneficio del convenio de la escuela con esa clínica (p. ej. "20 % en armazones"). null = sin convenio. */
    val convenio: String? = null,
)

@Serializable
data class CitaDto(
    val id: Int,
    val clinicaNombre: String? = null,
    val fechaPreferida: String,
    val horario: String? = null,
    val comentario: String? = null,
    /** SOLICITADA, CONFIRMADA, ATENDIDA o CANCELADA. */
    val estado: String,
    val creadaEn: String,
)

@Serializable
data class EventoDto(val fecha: String, val tipo: String, val descripcion: String)

/** POST /api/tamizajes/{id}/destino (M-RFN-09). Uno de los dos. */
@Serializable
data class DestinoEntradaDto(
    val clinicaId: Int? = null,
    /** Nombre escrito a mano si la clínica no está en el directorio (M-RFNN-06). */
    val nombreManual: String? = null,
)

@Serializable
data class DestinoDto(
    /** true = pase con QR; false = solo el resumen del tamizaje. */
    val conPase: Boolean,
    /** Explicación para el tutor (p. ej. "no está asociada al programa..."). */
    val mensaje: String,
    val tamizaje: TamizajeDto,
)

/** POST /api/tamizajes/{id}/citas (M-RFN-08). */
@Serializable
data class CitaEntradaDto(
    val clinicaId: Int,
    /** AAAA-MM-DD, de hoy en adelante. */
    val fechaPreferida: String,
    /** MANANA, TARDE o texto libre corto. */
    val horario: String? = null,
    val comentario: String? = null,
)

/** Un comprobante como lo ven la app y el portal (docs/API.md §11). */
@Serializable
data class ComprobanteDto(
    val id: String,
    val tamizajeId: String,
    val casoId: Int = 0,
    val alumno: String = "",
    /** Correo de quien lo subió. */
    val tutor: String,
    /** RECETA o COMPROBANTE_LENTES. */
    val tipo: String,
    val contentType: String,
    val tamanoBytes: Int,
    val subidoEn: String,
    /** PENDIENTE, APROBADO o RECHAZADO. */
    val estado: String,
    val revisadoEn: String? = null,
    val motivoRechazo: String? = null,
)

/** Cuerpo de POST /api/comprobantes/{id}/revision (Enfermería). */
@Serializable
data class RevisionComprobanteDto(val aprobado: Boolean, val motivo: String? = null)

@Serializable
data class CitaCreadaDto(val id: Int, val mensaje: String)
