# ---- Stage 1: Build Frontend Dashboard ----
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ---- Stage 2: Build Backend ----
# Uses the official Maven image so no wrapper (mvnw) needs to be checked into the repo.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the POM first and resolve dependencies as their own layer — code changes won't
# invalidate this layer, so rebuilds after a source-only edit skip the dependency download.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Copy static frontend assets built in stage 1 into Spring Boot static resources
COPY --from=frontend-build /app/src/main/resources/static ./src/main/resources/static
RUN mvn -B package -DskipTests

# ---- Stage 3: Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user — standard container hardening, no reason the JVM needs root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
