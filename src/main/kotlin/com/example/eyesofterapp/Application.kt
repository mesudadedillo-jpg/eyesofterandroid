package com.example.eyesofterapp

import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.db.PreparacionBase
import com.example.eyesofterapp.plugins.configureCors
import com.example.eyesofterapp.plugins.configureErrores
import com.example.eyesofterapp.plugins.configureProteccion
import com.example.eyesofterapp.plugins.configureSecurity
import com.example.eyesofterapp.plugins.configureSerialization
import com.example.eyesofterapp.routes.configureRouting
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

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
    val log = LoggerFactory.getLogger("com.example.eyesofterapp.Main")
    // Cualquier error que escape de un hilo queda en los logs de Railway con su causa,
    // en vez de perderse.
    Thread.setDefaultUncaughtExceptionHandler { hilo, e -> log.error("Error no atrapado en el hilo ${hilo.name}", e) }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    try {
        embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
            .start(wait = true)
    } catch (e: Throwable) {
        log.error("El servidor no pudo arrancar o se detuvo por un error", e)
        throw e
    }
}

/** Configuración real: lee las variables de entorno y arranca el despachador y los recordatorios. */
fun Application.module() {
    val config = Configuracion.desdeEntorno()
    val servicios = Servicios(config)
    modulo(servicios)
    PreparacionBase(servicios.db, config.crearEsquema, config.adminEmail, config.adminPassword).iniciar(this)
    // Application es un CoroutineScope: lo que se lance aquí se cancela al apagar el servidor.
    config.despachoSegundos?.let { servicios.despachador.iniciar(this, it) }
    config.recordatorioRevisionMin?.let { servicios.recordatorios.iniciar(this, it) }
    monitor.subscribe(ApplicationStopped) { servicios.cerrar() }
}

/**
 * Configura el servidor con las piezas que se le den. Las pruebas llaman a
 * esta función con una configuración hecha a mano.
 */
fun Application.modulo(servicios: Servicios) {
    configureSerialization()
    configureErrores()
    configureCors()
    configureProteccion()
    configureSecurity(servicios.tokens, servicios.usuarios)
    configureRouting(servicios)
}
