# Backend Dockerfile - Spring Boot
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/attachments /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-Xms1g", "-Xmx32g", "-jar", "app.jar"]
