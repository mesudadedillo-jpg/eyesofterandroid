// Objeto de configuración global que expone la URL base del backend Ktor.
// Depende de la clase autogenerada mx.eyesofter.suite.config.BuildKonfig basada en local.properties.
package mx.eyesofter.suite.config

object AppConfig {
    val baseUrl: String = try {
        BuildKonfig.API_BASE_URL
    } catch (e: Throwable) {
        "http://10.0.2.2:8080"
    }
}
