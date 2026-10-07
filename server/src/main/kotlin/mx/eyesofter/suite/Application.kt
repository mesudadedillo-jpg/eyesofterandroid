// Punto de entrada principal del backend Ktor ejecutado sobre el motor Netty en el puerto 8080.
// Depende de los módulos de configuración mx.eyesofter.suite.plugins.*.
package mx.eyesofter.suite

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import mx.eyesofter.suite.plugins.configureCORS
import mx.eyesofter.suite.plugins.configureRouting
import mx.eyesofter.suite.plugins.configureSerialization

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureCORS()
    configureRouting()
}
