// Registro y orquestación de todas las rutas HTTP del servidor backend.
// Depende de las rutas definidas en mx.eyesofter.suite.routes.resultadoRoutes.
package mx.eyesofter.suite.plugins

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import mx.eyesofter.suite.routes.resultadoRoutes

fun Application.configureRouting() {
    routing {
        resultadoRoutes()
    }
}
