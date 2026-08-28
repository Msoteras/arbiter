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
RUN mvn -pl classification-service -am clean package -DskipTests -q

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine

# Unprivileged: nothing in here needs root.
RUN addgroup -S arbiter && adduser -S -G arbiter arbiter

WORKDIR /app
COPY --from=build /build/classification-service/target/*.jar app.jar

USER arbiter

EXPOSE 8082

# Explicit -Duser.timezone: containers default to UTC while the team's laptops run UTC-3, and this
# domain evaluates legal deadlines off local dates — see the ZoneId.systemDefault() calls in this
# module. Java ships its own tzdb, so no tzdata package is needed. Set here and not in
# JAVA_TOOL_OPTIONS because the compose files already use that variable for heap sizing.
ENTRYPOINT ["java", "-Duser.timezone=America/Argentina/Buenos_Aires", "-jar", "app.jar"]
