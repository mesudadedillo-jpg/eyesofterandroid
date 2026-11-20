package com.example.eyesofterapp.repos

/**
 * La base guarda los enums con el nombre de C# ("EnSeguimiento", "ComprobanteLentes");
 * la app los recibe en MAYÚSCULAS_CON_GUION ("EN_SEGUIMIENTO", "COMPROBANTE_LENTES"),
 * como estaban en el contrato antes de unir las bases.
 */
fun aApp(valorBase: String): String = valorBase.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

/** "en_seguimiento" o "EN_SEGUIMIENTO" → "EnSeguimiento". null si no está entre [validos]. */
fun aBase(valorApp: String?, validos: Collection<String>): String? {
    val limpio = valorApp?.trim()?.replace("_", "")?.lowercase() ?: return null
    return validos.firstOrNull { it.lowercase() == limpio }
}

object Semaforos {
    val TODOS = listOf("Verde", "Amarillo", "Rojo")
    fun gravedad(s: String) = TODOS.indexOf(s)

    /** Mismos textos del portal (TextosSemaforo.cs) y de la app (C-RC-09, C-RC-10). */
    fun titulo(s: String) = when (s) {
        "Verde" -> "Saludable"
        "Amarillo" -> "En observación"
        else -> "Atención urgente requerida"
    }
}

object EstadosCaso { const val ABIERTO = "Abierto"; const val EN_SEGUIMIENTO = "EnSeguimiento"; const val CERRADO = "Cerrado" }
object EstadosPase { const val VIGENTE = "Vigente"; const val USADO = "Usado"; const val CANCELADO = "Cancelado" }
object TiposComprobante { val TODOS = listOf("Receta", "ComprobanteLentes") }
object EstadosComprobante { const val PENDIENTE = "Pendiente"; const val APROBADO = "Aprobado"; const val RECHAZADO = "Rechazado" }

object TipoEvento {
    const val TAMIZAJE = "Tamizaje"; const val AVISO_TUTOR = "AvisoTutor"; const val CITA = "Cita"; const val COMPROBANTE = "Comprobante"
    const val DESTINO = "Destino"; const val ATENCION = "Atencion"; const val SEGUIMIENTO = "Seguimiento"
}
