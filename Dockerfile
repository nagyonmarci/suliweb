# Backend Dockerfile - Spring Boot
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/attachments /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
