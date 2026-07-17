# Contexto de build: raíz del proyecto (necesita el POM padre y common-lib)
# docker build -t expedientes-img -f expedientes-service/Dockerfile .

# --- Etapa 1: construcción ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# POMs primero para aprovechar cache de capas
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY expedientes-service/pom.xml expedientes-service/

# Actualizar pom padre para compilar solo módulos que existen (eliminar líneas de módulos inexistentes)
RUN sed -i '/<module>auth-service<\/module>/d; /<module>reglas-service<\/module>/d; /<module>siniestros-service<\/module>/d; /<module>reportes-service<\/module>/d' pom.xml

RUN mvn dependency:go-offline -pl common-lib,expedientes-service -am -q

COPY common-lib/src common-lib/src
COPY expedientes-service/src expedientes-service/src
RUN mvn -pl expedientes-service -am package -DskipTests -q

# --- Etapa 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/expedientes-service/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
