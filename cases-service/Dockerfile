# Build context: project root (needs the parent POM and common-lib)
# docker build -t cases-img -f cases-service/Dockerfile .

# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# POMs first to leverage layer caching
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY cases-service/pom.xml cases-service/

# Update the parent pom to only build modules that exist (drop lines for modules that don't)
RUN sed -i '/<module>auth-service<\/module>/d; /<module>rules-service<\/module>/d; /<module>classification-service<\/module>/d; /<module>reports-service<\/module>/d' pom.xml

RUN mvn dependency:go-offline -pl common-lib,cases-service -am -q

COPY common-lib/src common-lib/src
COPY cases-service/src cases-service/src
RUN mvn -pl cases-service -am clean package -DskipTests -q

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/cases-service/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
