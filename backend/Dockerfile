# ============================================================
# STAGE 1: Build - Maven + JDK 21
# ============================================================
# Używamy oficjalnego obrazu z Mavenem i Eclipse Temurin JDK 21.
# Ten stage buduje JAR-a; w finalnym obrazie będzie tylko skompilowany
# artefakt + slim JRE, bez Mavena ani sourców.
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Kopiujemy pom.xml jako pierwszy i pobieramy zależności w osobnej warstwie.
# Dzięki temu kolejne buildy (gdy zmienia się tylko kod, nie zależności)
# nie pobierają z nowa pełnych ~500 MB z Maven Central.
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Teraz kopiujemy źródła i budujemy
COPY src ./src
RUN mvn package -DskipTests -B

# ============================================================
# STAGE 2: Runtime - slim JRE
# ============================================================
# Final image: tylko JRE + nasz JAR. Brak Mavena, brak źródeł.
# Wielkość rzędu 200-250 MB zamiast 1.5 GB build image.
# ============================================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Skopiuj zbudowany JAR z stage 1
COPY --from=build /build/target/platform-0.1.0-SNAPSHOT.jar app.jar

# Port aplikacji (Spring Boot)
EXPOSE 8080

# Profil produkcyjny - bierze konfigurację z env vars
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
