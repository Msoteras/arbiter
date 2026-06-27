# Build context: project root (needs the parent POM and common-lib)
# docker build -t classification-img -f classification-service/Dockerfile .

# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# POMs first to leverage layer caching
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY classification-service/pom.xml classification-service/

# Update the parent pom to only build modules that exist (drop lines for modules that don't)
RUN sed -i '/<module>auth-service<\/module>/d; /<module>rules-service<\/module>/d; /<module>cases-service<\/module>/d; /<module>reports-service<\/module>/d' pom.xml

RUN mvn dependency:go-offline -pl common-lib,classification-service -am -q

COPY common-lib/src common-lib/src
COPY classification-service/src classification-service/src
RUN mvn -pl classification-service -am package -DskipTests -q

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/classification-service/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
