# ledger service — Java / Spring Boot 3 (multi-stage build).
#
# Stage 1 builds the fat jar with Maven; stage 2 runs it on a slim Temurin JRE.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is used by the docker-compose healthcheck.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/ledger-1.0.0.jar app.jar

ENV LEDGER_DB=/data/ledger.db
RUN mkdir -p /data

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
