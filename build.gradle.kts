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
    implementation(libs.kotlinx.serializationJson)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.kotlin.testJunit)
}
