package com.example.eyesofterapp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Un usuario tal como está guardado en users.json.
 * Es distinto de UserDto porque este SÍ trae la contraseña,
 * y por eso nunca se manda directo a la app.
 */
@Serializable
data class UserRecord(
    val username: String,
    val password: String,
    val fullName: String,
    val role: String
)

/**
 * Encargado de leer los usuarios. Es la única parte del servidor que sabe
 * que los usuarios viven en un archivo JSON. Cuando se conecte MySQL,
 * solo habrá que cambiar este archivo; las rutas no se enteran.
 */
object UserRepository {

    private val jsonDecoder = Json {
        ignoreUnknownKeys = true // Si el JSON trae campos extra, los ignora
        isLenient = true         // Tolera JSON un poco "flojo"
    }

    /**
     * Busca users.json en disco, en este orden:
     *  1. La ruta que diga la variable de entorno USERS_FILE (útil en Railway).
     *  2. src/main/resources/users.json (cuando corres ./gradlew run desde la raíz).
     * En el proyecto original también se probaba "server/src/main/resources/users.json";
     * esa ruta ya no existe porque ahora el servidor está en la raíz.
     */
    private fun findUsersFile(): File? {
        val candidates = listOfNotNull(
            System.getenv("USERS_FILE"),
            "src/main/resources/users.json"
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    /**
     * Lee la lista de usuarios. Se llama en CADA intento de login, a propósito:
     * así puedes editar users.json y probar sin reiniciar el servidor.
     * Si no hay archivo en disco (por ejemplo dentro del JAR en Docker),
     * usa la copia empaquetada en los recursos.
     */
    fun loadUsers(): List<UserRecord> {
        val file = findUsersFile()
        return try {
            val content = if (file != null) {
                println("Leyendo usuarios desde: ${file.absolutePath}")
                file.readText(Charsets.UTF_8)
            } else {
                println("users.json no encontrado en disco; usando la copia empaquetada")
                UserRepository::class.java.classLoader.getResourceAsStream("users.json")
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: return emptyList()
            }
            jsonDecoder.decodeFromString<List<UserRecord>>(content)
        } catch (e: Exception) {
            println("ERROR al leer users.json: ${e.message}")
            emptyList()
        }
    }

    /** Busca un usuario por nombre sin importar mayúsculas/minúsculas. */
    fun findByUsername(username: String): UserRecord? =
        loadUsers().firstOrNull { it.username.equals(username, ignoreCase = true) }
}
