# Dockerfile de DOS ETAPAS
# Etapa 1 ("build"): una imagen grande con el JDK completo compila el fat JAR.
# Etapa 2: una imagen pequeña con solo el JRE (lo necesario para EJECUTAR Java)
#          copia ese JAR y lo arranca. Así la imagen final pesa mucho menos
#          y no lleva el código fuente ni Gradle.

# ---------- Etapa 1: compilar ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Primero copiamos solo lo de Gradle. Docker guarda cada paso en caché:
# si solo cambia el código, no vuelve a descargar Gradle ni las librerías.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Por si el archivo llegó con finales de línea de Windows (CRLF) o sin permiso
# de ejecución: quita los \r y lo marca como ejecutable.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon dependencies > /dev/null

# Ahora sí el código, y compilamos el JAR con todas sus librerías adentro.
COPY src ./src
RUN ./gradlew --no-daemon buildFatJar

# ---------- Etapa 2: ejecutar ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/eyesofter-backend.jar ./eyesofter-backend.jar

# Solo es informativo: Railway define PORT y el servidor lo lee.
EXPOSE 8080
CMD ["java", "-jar", "eyesofter-backend.jar"]
