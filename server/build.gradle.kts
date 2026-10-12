// Depende de: gradle/libs.versions.toml, project(":shared")
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

group = "mx.eyesofter.suite"
version = "1.0.0"

application {
    mainClass.set("mx.eyesofter.suite.ApplicationKt")
}

// Permite a Android Studio ejecutar la función main() directamente desde Application.kt
tasks.register<JavaExec>("mx.eyesofter.suite.ApplicationKt.main()") {
    group = "application"
    mainClass.set("mx.eyesofter.suite.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.logback)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.testJunit)
}
