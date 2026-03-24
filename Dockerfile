# Backend Dockerfile - Spring Boot
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Install Maven 3.9.x instead of outdated apt version
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/attachments /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
