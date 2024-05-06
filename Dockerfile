FROM ubuntu:latest
LABEL authors="FabianM"

ENTRYPOINT ["top", "-b"]

# Használj OpenJDK 11 alapú képet
FROM openjdk:11-jdk

# Az alkalmazás fájljainak másolása a konténerbe
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

# Az alkalmazás futtatásához szükséges port megnyitása
EXPOSE 6969

# Az alkalmazás indítása
ENTRYPOINT ["java", "-jar", "/app.jar"]