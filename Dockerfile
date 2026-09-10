# ---- Build stage ----
# Uses the official Maven image so no wrapper (mvnw) needs to be checked into the repo.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the POM first and resolve dependencies as their own layer — code changes won't
# invalidate this layer, so rebuilds after a source-only edit skip the dependency download.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user — standard container hardening, no reason the JVM needs root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
