# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy the POM first so Maven's dependency layer is cached separately from source.
# If only src/ changes, Docker reuses the cached layer and skips re-downloading deps.
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Never run a JVM as root inside a container.
RUN groupadd --system throttle && useradd --system --gid throttle throttle

COPY --from=builder /app/target/throttle-0.0.1-SNAPSHOT.jar app.jar

USER throttle

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
