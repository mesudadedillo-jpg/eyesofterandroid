import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.example.eyesofterapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.eyesofterapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

abstract class AutoStartKtorServerTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        // 1. Configurar redirección de puertos ADB reverse
        try {
            val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            val adbCmd = if (androidHome != null) "$androidHome/platform-tools/adb" else "adb"
            execOperations.exec {
                commandLine(adbCmd, "reverse", "tcp:8080", "tcp:8080")
            }
            println("ADB reverse tcp:8080 tcp:8080 configurado con éxito.")
        } catch (_: Exception) {
            // Ignorar si no hay dispositivo conectado durante este paso de compilación
        }

        // 2. Verificar si el servidor Ktor responde en el puerto 8080
        val isServerRunning = try {
            val url = URI("http://127.0.0.1:8080/").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (_: Exception) {
            false
        }

        if (!isServerRunning) {
            println("Servidor Ktor no detectado en puerto 8080. Iniciando el servidor Ktor backend automáticamente...")
            try {
                val rootDir = project.rootDir.absolutePath
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                if (isWindows) {
                    val gradlewPath = "$rootDir\\gradlew.bat"
                    ProcessBuilder("cmd.exe", "/c", "start", "/b", gradlewPath, ":server:run")
                        .directory(project.rootDir)
                        .start()
                } else {
                    val gradlewPath = "$rootDir/gradlew"
                    ProcessBuilder("sh", "-c", "$gradlewPath :server:run &")
                        .directory(project.rootDir)
                        .start()
                }
                println("Servidor Ktor iniciado en segundo plano automáticamente.")
            } catch (e: Exception) {
                println("Aviso al iniciar servidor Ktor: ${e.message}")
            }
        } else {
            println("Servidor Ktor activo y respondiendo en puerto 8080.")
        }
    }
}

tasks.register<AutoStartKtorServerTask>("autoStartKtorServer")

tasks.matching { it.name.startsWith("install") || it.name == "preBuild" }.configureEach {
    dependsOn("autoStartKtorServer")
}