// Definición de las rutas Ktor para consultar los resultados de tamizaje visual de un alumno.
// Depende de los modelos mx.eyesofter.suite.model.ResultadoTamizaje y mx.eyesofter.suite.model.Semaforo.
package mx.eyesofter.suite.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mx.eyesofter.suite.model.ResultadoTamizaje
import mx.eyesofter.suite.model.Semaforo

fun Route.resultadoRoutes() {
    route("/api/alumnos") {
        get("/{id}/resultado") {
            val alumnoId = call.parameters["id"]?.toIntOrNull()
            if (alumnoId == null) {
                call.respond(HttpStatusCode.BadRequest, "ID de alumno inválido")
                return@get
            }

            val resultadoPrueba = when (alumnoId % 3) {
                0 -> ResultadoTamizaje(
                    alumnoId = alumnoId,
                    nombreAlumno = "Sofía Martínez",
                    semaforo = Semaforo.VERDE,
                    observaciones = "Agudeza visual normal en ambos ojos. Sin necesidad de corrección.",
                    fecha = "2026-09-30"
                )
                1 -> ResultadoTamizaje(
                    alumnoId = alumnoId,
                    nombreAlumno = "Mateo Hernández",
                    semaforo = Semaforo.AMARILLO,
                    observaciones = "Ligera disminución de agudeza visual en ojo izquierdo. Se sugiere valoración por optometrista.",
                    fecha = "2026-09-30"
                )
                else -> ResultadoTamizaje(
                    alumnoId = alumnoId,
                    nombreAlumno = "Camila López",
                    semaforo = Semaforo.ROJO,
                    observaciones = "Deficiencia visual significativa detectada. Requiere atención médica especializada prioritaria.",
                    fecha = "2026-09-30"
                )
            }

            call.respond(HttpStatusCode.OK, resultadoPrueba)
        }
    }
}
