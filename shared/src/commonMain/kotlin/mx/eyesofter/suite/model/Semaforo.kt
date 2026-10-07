// Enum que define los tres niveles de riesgo del semáforo de tamizaje visual (VERDE, AMARILLO, ROJO).
// Este archivo es una entidad base y no depende de ningún otro archivo del proyecto.
package mx.eyesofter.suite.model

import kotlinx.serialization.Serializable

@Serializable
enum class Semaforo {
    VERDE,
    AMARILLO,
    ROJO
}
