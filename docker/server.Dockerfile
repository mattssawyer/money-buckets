# syntax=docker/dockerfile:1
# Build from the repository root: docker build -f docker/server.Dockerfile .
# The jar is the same on every CPU, so it is built once on the build machine's platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY server/gradlew server/settings.gradle server/build.gradle ./
COPY server/gradle gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY server/src src
RUN ./gradlew bootJar --no-daemon && cp build/libs/*.jar app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
