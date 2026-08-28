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

# Unprivileged: nothing in here needs root.
RUN addgroup -S arbiter && adduser -S -G arbiter arbiter

WORKDIR /app
COPY --from=build /build/cases-service/target/*.jar app.jar

USER arbiter

EXPOSE 8083

# Explicit -Duser.timezone: containers default to UTC while the team's laptops run UTC-3, and this
# domain evaluates legal deadlines (Ley 17.418 art. 56, the 72h police-report window) off local
# dates — see the ZoneId.systemDefault() calls in this module. Java ships its own tzdb, so no
# tzdata package is needed. Set here and not in JAVA_TOOL_OPTIONS because the compose files
# already use that variable for heap sizing and would overwrite it.
ENTRYPOINT ["java", "-Duser.timezone=America/Argentina/Buenos_Aires", "-jar", "app.jar"]
