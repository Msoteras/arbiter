# Build context: project root (needs the parent POM and common-lib)
# docker build -t rules-img -f rules-service/Dockerfile .

# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# POMs first to leverage layer caching
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY rules-service/pom.xml rules-service/

# Update the parent pom to only build modules that exist (drop lines for modules that don't)
RUN sed -i '/<module>auth-service<\/module>/d; /<module>cases-service<\/module>/d; /<module>classification-service<\/module>/d; /<module>reports-service<\/module>/d' pom.xml

RUN mvn dependency:go-offline -pl common-lib,rules-service -am -q

COPY common-lib/src common-lib/src
COPY rules-service/src rules-service/src
RUN mvn -pl rules-service -am clean package -DskipTests -q

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/rules-service/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
