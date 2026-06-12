# Backend Dockerfile - Spring Boot
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Dummy secret so tests can run during Docker build (overridden at runtime)
ENV JWT_SECRET=docker-build-dummy-not-for-production
RUN mvn clean package && mkdir -p /app/attachments /app/logs

FROM cgr.dev/chainguard/jre:latest@sha256:aec912a20efff9133df2abd4c29f7ad57644db8c1a07a2fccc967967a0926784 AS runtime
WORKDIR /app
COPY --chown=65532:65532 --from=build /app/target/*.jar app.jar
COPY --chown=65532:65532 --from=build /app/attachments /app/attachments
COPY --chown=65532:65532 --from=build /app/logs /app/logs
USER 65532
EXPOSE 8080
ENTRYPOINT ["java", "-Xms1g", "-Xmx32g", "-jar", "app.jar"]
