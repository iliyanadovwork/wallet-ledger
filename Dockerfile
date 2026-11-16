# Build and run stages are separate so the image that ships has a JRE and a jar in it and not a
# JDK, a Gradle distribution and a dependency cache.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# The wrapper and the build scripts are copied first. They change far less often than the source,
# so the layer that downloads Gradle and the dependencies survives most rebuilds.
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# Not root. A container that is compromised should not also be privileged inside itself.
RUN useradd --system --create-home --shell /usr/sbin/nologin wallet
USER wallet

COPY --from=build --chown=wallet:wallet /build/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
