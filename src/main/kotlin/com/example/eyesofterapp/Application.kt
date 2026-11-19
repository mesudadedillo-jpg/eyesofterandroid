package com.example.eyesofterapp

import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.plugins.configureCors
import com.example.eyesofterapp.plugins.configureErrores
import com.example.eyesofterapp.plugins.configureSecurity
import com.example.eyesofterapp.plugins.configureSerialization
import com.example.eyesofterapp.routes.configureRouting
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 *  Punto de entrada del servidor.
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

/** Configuración real: lee las variables de entorno y arranca los recordatorios. */
fun Application.module() {
    val config = Configuracion.desdeEntorno()
    // Application es un CoroutineScope: lo que se lance aquí se cancela al apagar el servidor.
    val servicios = Servicios(config, scope = this)
    modulo(servicios)
    config.recordatorioRevisionMin?.let { servicios.recordatorios.iniciar(this, it) }
}

/**
 * Configura el servidor con las piezas que se le den. Las pruebas llaman a
 * esta función con una configuración hecha a mano.
 */
fun Application.modulo(servicios: Servicios) {
    configureSerialization()
    configureErrores()
    configureCors()
    configureSecurity(servicios.tokens)
    configureRouting(servicios)
}
