// DTO que representa la información completa del resultado de tamizaje visual de un alumno.
// Depende de la enumeración mx.eyesofter.suite.model.Semaforo para calificar el nivel de riesgo.
package mx.eyesofter.suite.model

import kotlinx.serialization.Serializable

@Serializable
data class ResultadoTamizaje(
    val alumnoId: Int,
    val nombreAlumno: String,
    val semaforo: Semaforo,
    val observaciones: String,
    val fecha: String
)
