package com.example.eyesofterapp

import com.example.eyesofterapp.plugins.configureCors
import com.example.eyesofterapp.plugins.configureSerialization
import com.example.eyesofterapp.routes.configureRouting
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Punto de entrada del servidor.
 *
 * El puerto se lee de la variable de entorno PORT porque Railway asigna uno
 * distinto cada vez que despliega. En tu computadora no existe PORT,
 * así que se usa 8080.
 *
 * host = "0.0.0.0" significa "escucha en todas las redes", no solo en
 * localhost. Sin esto, Railway o el emulador de Android no podrían conectarse.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * Configura el servidor. Las pruebas también llaman a esta función,
 * por eso está separada de main().
 */
fun Application.module() {
    configureSerialization()
    configureCors()
    configureRouting()
}
