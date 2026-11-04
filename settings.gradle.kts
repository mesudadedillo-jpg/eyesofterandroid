// settings.gradle.kts
// Es el primer archivo que lee Gradle. Aquí se dice cómo se llama el proyecto
// y de dónde se descargan los plugins y las librerías.

// Nombre del proyecto. Antes era "Eyesofterapp" y contenía app + servidor;
// ahora este proyecto es SOLO el servidor.
rootProject.name = "eyesofter-backend"

pluginManagement {
    repositories {
        // Ya no necesitamos google() porque aquí no hay nada de Android.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    // Permite que Gradle descargue solo el JDK 21 que piden
    // gradle/gradle-daemon-jvm.properties y jvmToolchain(21) si no lo tienes.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
