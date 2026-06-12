# Backend Dockerfile - Spring Boot
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Dummy secret so tests can run during Docker build (overridden at runtime)
ENV JWT_SECRET=docker-build-dummy-not-for-production
RUN mvn clean package

FROM eclipse-temurin:25-jre-noble AS runtime
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/attachments /app/logs && chown -R appuser:appgroup /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-Xms1g", "-Xmx32g", "-jar", "app.jar"]
