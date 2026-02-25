FROM ubuntu:latest
LABEL authors="FabianM"

ENTRYPOINT ["top", "-b"]

# Használj OpenJDK 17 alapú képet
FROM eclipse-temurin:17-jdk

# Az alkalmazás fájljainak másolása a konténerbe
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

# Az alkalmazás futtatásához szükséges port megnyitása
EXPOSE 6969

# Az alkalmazás indítása
ENTRYPOINT ["java", "-jar", "/app.jar"]