# syntax=docker/dockerfile:1
# The app's image: built with the Maven wrapper on a JDK, run on a JRE, one JVM.
# Temurin 25.0.5+8 is the current patch release (2026-11-17); move both tags forward with each patch release.

FROM eclipse-temurin:25.0.5_8-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
# The wrapper downloads Maven into /root/.m2, cached between builds with the dependencies
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q -DskipTests package

FROM eclipse-temurin:25.0.5_8-jre
WORKDIR /app

# Production settings as real environment variables, which win over settings.env (copied below). .env isn't copied
# (see .dockerignore): docker compose passes the private keys in.
# Listen on every interface and keep the data on the volume. The production profile caches templates, hides error
# details and logs to stdout; Tomcat trusts X-Forwarded-* from the proxy in front (server.forward-headers-strategy).
ENV APP_HOST=0.0.0.0 APP_PORT=8000 DATA_DIR=/app/storage SPRING_PROFILES_ACTIVE=production

COPY --from=build /build/target/surveyjs-java-spring-boot.jar ./
# What the app reads at run time from its working directory: shared/ (pages, seed, definitions), the manifest, and
# the source files the Code tab cuts its regions from, so it shows the code that runs
COPY surveyjs-integration.json settings.env ./
COPY shared shared
COPY src/main/java/io/surveyjs/examples src/main/java/io/surveyjs/examples
COPY src/main/java/io/surveyjs/relay src/main/java/io/surveyjs/relay

# Not root: the app only writes to /app/storage (the H2 database, uploads, visitor sandboxes, relay snapshots)
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/storage && chown app:app /app/storage
USER app

EXPOSE 8000
# One JVM, PID 1 (exec form), so it gets SIGTERM and shuts down gracefully. It creates and seeds the database on start.
CMD ["java", "-jar", "surveyjs-java-spring-boot.jar"]
