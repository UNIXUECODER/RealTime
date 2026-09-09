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
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
