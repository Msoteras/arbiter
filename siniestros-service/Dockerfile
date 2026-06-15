# Contexto de build: raíz del proyecto (necesita el POM padre y common-lib)
# docker build -t siniestros-img -f siniestros-service/Dockerfile .

# --- Etapa 1: construcción ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# POMs primero para aprovechar cache de capas
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY siniestros-service/pom.xml siniestros-service/
RUN mvn dependency:go-offline -pl common-lib,siniestros-service -am -q

COPY common-lib/src common-lib/src
COPY siniestros-service/src siniestros-service/src
RUN mvn -pl siniestros-service -am package -DskipTests -q

# --- Etapa 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/siniestros-service/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
