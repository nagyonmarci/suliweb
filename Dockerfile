# Backend Dockerfile - Spring Boot
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app
# Copy pom.xml first — dependency layer is cached as long as pom.xml doesn't change
COPY pom.xml .
# Pre-fetch all dependencies (cached layer, skipped when pom.xml is unchanged)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q
COPY src ./src
# Dummy secret so tests can run during Docker build (overridden at runtime)
ENV JWT_SECRET=docker-build-dummy-not-for-production
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -q && mkdir -p /app/attachments /app/logs
COPY src/main/docker/HealthCheck.java /app/HealthCheck.java
RUN javac /app/HealthCheck.java -d /app

FROM cgr.dev/chainguard/jre:latest@sha256:aec912a20efff9133df2abd4c29f7ad57644db8c1a07a2fccc967967a0926784 AS runtime
WORKDIR /app
COPY --chown=65532:65532 --from=build /app/target/*.jar app.jar
COPY --chown=65532:65532 --from=build /app/HealthCheck.class /app/
COPY --chown=65532:65532 --from=build /app/attachments /app/attachments
COPY --chown=65532:65532 --from=build /app/logs /app/logs
USER 65532
EXPOSE 8080
ENTRYPOINT ["java", "-Xms1g", "-Xmx32g", "-jar", "app.jar"]
