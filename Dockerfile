# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline

COPY fixtures fixtures
COPY src src
RUN ./mvnw -B test package

FROM eclipse-temurin:17-jre

WORKDIR /app

RUN useradd --create-home --uid 10001 payerlab

COPY --from=build /workspace/target/careparse-payerlab-0.1.0-SNAPSHOT.jar /app/careparse-payerlab.jar
COPY fixtures/payer /app/fixtures/payer

ENV PAYERLAB_PORT=8080

EXPOSE 8080

USER payerlab

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -Dpayerlab.port=${PAYERLAB_PORT:-8080} -jar /app/careparse-payerlab.jar"]
