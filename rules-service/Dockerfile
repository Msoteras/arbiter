# Build context: project root (needs the parent POM and common-lib)
# docker build -t rules-img -f rules-service/Dockerfile .

# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# POMs first to leverage layer caching
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY rules-service/pom.xml rules-service/

# Drop the modules this image doesn't copy, so the reactor only builds what exists
RUN sed -i '/<module>auth-service<\/module>/d; /<module>cases-service<\/module>/d; /<module>classification-service<\/module>/d; /<module>reports-service<\/module>/d' pom.xml

RUN mvn dependency:go-offline -pl common-lib,rules-service -am -q

COPY common-lib/src common-lib/src
COPY rules-service/src rules-service/src
RUN mvn -pl rules-service -am clean package -DskipTests -q

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

# Unprivileged: nothing in here needs root.
RUN addgroup -S arbiter && adduser -S -G arbiter arbiter

WORKDIR /app
COPY --from=build /build/rules-service/target/*.jar app.jar

USER arbiter

EXPOSE 8081

# Containers default to UTC, but legal deadlines are evaluated on local dates. Set here and not in
# JAVA_TOOL_OPTIONS because the compose files already use that variable for heap sizing.
ENTRYPOINT ["java", "-Duser.timezone=America/Argentina/Buenos_Aires", "-jar", "app.jar"]
