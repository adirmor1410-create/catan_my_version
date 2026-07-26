# Builds the game server into a self-contained image.
#
# The Android app is not part of this build: the server only needs :core and :server, so no
# Android SDK is involved and the image stays small.
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core ./core
COPY server ./server
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server ./
# Hosting platforms assign a port through $PORT; Main.kt reads it and falls back to 8080.
ENV PORT=8080
EXPOSE 8080
CMD ["./bin/server"]
