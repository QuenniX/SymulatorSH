
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B || true

COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app


COPY --from=build /build/target/platform-0.1.0-SNAPSHOT.jar app.jar

# Port aplikacji (Spring Boot)
EXPOSE 8080

# Profil produkcyjny - bierze konfigurację z env vars
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
