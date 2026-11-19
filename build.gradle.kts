// build.gradle.kts
// Dice CÓMO se compila el servidor: qué plugins usa, de qué librerías depende
// y cuál es la función main que arranca todo.
// Antes vivía en server/build.gradle.kts dentro del proyecto grande; ahora el
// servidor ES el proyecto, por eso este archivo está en la raíz.

plugins {
    alias(libs.plugins.kotlinJvm)           // Compila Kotlin para la JVM (Java)
    alias(libs.plugins.ktor)                // Agrega las tareas run y buildFatJar
    alias(libs.plugins.kotlinSerialization) // Genera el código de @Serializable
}

group = "com.example.eyesofterapp"
version = "1.0.0"

application {
    // Archivo Application.kt → clase ApplicationKt. Ahí está main().
    mainClass = "com.example.eyesofterapp.ApplicationKt"
}

kotlin {
    // Compila con Java 21, el mismo que usa la imagen de Docker.
    jvmToolchain(21)
}

ktor {
    fatJar {
        // Nombre fijo del JAR "gordo" (incluye todas las librerías).
        // El Dockerfile lo busca con este nombre.
        archiveFileName.set("eyesofter-backend.jar")
    }
}

dependencies {
    // Ya NO dependemos de :core ni de :app:shared. Los DTOs del contrato
    // tienen su propia copia en model/AuthDtos.kt.
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverStatusPages) // Errores → {"error": "..."} con el código correcto
    implementation(libs.ktor.serverAuth)        // authenticate { } en las rutas
    implementation(libs.ktor.serverAuthJwt)     // Tokens JWT firmados
    implementation(libs.kotlinx.serializationJson)

    // Para que el servidor llame a Firebase (push) y a Resend (correo).
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.google.authOauth2)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
}

tasks.test {
    // Las pruebas usan sus propios usuarios fijos (src/test/resources/users-test.json)
    // para no romperse cuando alguien cambia las contraseñas demo de users.json.
    systemProperty("users.file", "src/test/resources/users-test.json")
}
